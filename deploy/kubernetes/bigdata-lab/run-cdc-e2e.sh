#!/usr/bin/env bash
set -euo pipefail

namespace="${NAMESPACE:-bigdata-lab}"
api_service="${API_SERVICE:-pekko-cdc-single-api}"
api_port_text="${LOCAL_API_PORT:-18081}"
cdc_pod="pekko-cdc-single-0"
cdc_statefulset="pekko-cdc-single"
mysql_pod="${MYSQL_POD:-}"
database="pekko_cdc_acceptance"
source_table="pekko_cdc_source_acceptance"
target_table="pekko_cdc_target_acceptance"
poll_interval_seconds=1
poll_timeout_seconds=150

fail() {
  echo "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is not available: $1"
}

for command_name in kubectl curl jq awk od tr date grep sha256sum; do
  require_command "${command_name}"
done

[[ "${namespace}" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || fail "NAMESPACE is not a valid Kubernetes namespace"
[[ "${api_service}" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || fail "API_SERVICE is not a valid Kubernetes service name"
[[ "${api_port_text}" =~ ^[0-9]+$ && ${#api_port_text} -le 5 ]] || fail "LOCAL_API_PORT must be a decimal port"
api_port=$((10#${api_port_text}))
((api_port >= 1024 && api_port <= 65535)) || fail "LOCAL_API_PORT must be between 1024 and 65535"

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/pekko-cdc-e2e.XXXXXX")"
chmod 700 "${temporary_directory}"
port_forward_pid=""
offset_lock_pid=""

stop_port_forward() {
  if [[ "${port_forward_pid}" =~ ^[0-9]+$ ]]; then
    kill "${port_forward_pid}" 2>/dev/null || true
    wait "${port_forward_pid}" 2>/dev/null || true
    port_forward_pid=""
  fi
}

stop_offset_lock() {
  if [[ "${offset_lock_pid}" =~ ^[0-9]+$ ]]; then
    kill "${offset_lock_pid}" 2>/dev/null || true
    wait "${offset_lock_pid}" 2>/dev/null || true
    offset_lock_pid=""
  fi
}

cleanup() {
  stop_port_forward
  stop_offset_lock
  if [[ -n "${temporary_directory}" && -d "${temporary_directory}" ]]; then
    rm -rf -- "${temporary_directory}"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

workflow_password_file="${temporary_directory}/workflow-password"
cdc_password_file="${temporary_directory}/cdc-password"
port_forward_log="${temporary_directory}/port-forward.log"
workflow_definition="${temporary_directory}/workflow.json"

secret_to_file() {
  local secret_name="$1"
  local destination="$2"
  umask 077
  kubectl --namespace "${namespace}" get secret "${secret_name}" \
    -o go-template='{{index .data "password" | base64decode}}' >"${destination}"
  chmod 600 "${destination}"
  [[ -s "${destination}" ]] || fail "${secret_name}/password is absent or empty"
  if LC_ALL=C od -An -tx1 "${destination}" | grep -Eq '(^|[[:space:]])(0a|0d)([[:space:]]|$)'; then
    fail "${secret_name}/password contains CR or LF"
  fi
}

resolve_mysql_pod() {
  local running_pods
  local running_count
  if [[ -n "${mysql_pod}" ]]; then
    [[ "${mysql_pod}" =~ ^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$ ]] || fail "MYSQL_POD is not a valid pod name"
    return
  fi
  running_pods="$(kubectl --namespace "${namespace}" get pods --selector app=mysql \
    -o custom-columns=NAME:.metadata.name,PHASE:.status.phase --no-headers | awk '$2 == "Running" { print $1 }')"
  running_count="$(awk 'NF { count += 1 } END { print count + 0 }' <<<"${running_pods}")"
  [[ "${running_count}" == "1" ]] || fail "expected exactly one Running app=mysql pod, found ${running_count}"
  mysql_pod="${running_pods}"
}

pod_ready() {
  local pod_name="$1"
  local ready
  ready="$(kubectl --namespace "${namespace}" get pod "${pod_name}" \
    -o jsonpath='{range .status.conditions[?(@.type=="Ready")]}{.status}{end}' 2>/dev/null || true)"
  [[ "${ready}" == "True" ]]
}

mysql_query() {
  local query="$1"
  kubectl --namespace "${namespace}" exec -i "${mysql_pod}" -- sh -ceu '
    workflow_password="$(cat)"
    [ -n "${workflow_password}" ] || {
      echo "workflow database credential is not available" >&2
      exit 1
    }
    MYSQL_PWD="${workflow_password}" exec mysql \
      --user=pekko_workflow --database=pekko_cdc_acceptance \
      --batch --skip-column-names --raw --unbuffered --execute "$1"
  ' -- "${query}" <"${workflow_password_file}"
}

json_extract() {
  local file="$1"
  local expression="$2"
  jq -er "${expression}" "${file}"
}

api_get() {
  local path="$1"
  local output="$2"
  curl --silent --fail --output "${output}" "http://127.0.0.1:${api_port}${path}"
}

api_post() {
  local path="$1"
  local output="$2"
  local body_file="${3:-}"
  if [[ -n "${body_file}" ]]; then
    curl --silent --output "${output}" --write-out '%{http_code}' \
      --request POST --header 'Content-Type: application/json' \
      --data-binary "@${body_file}" "http://127.0.0.1:${api_port}${path}"
  else
    curl --silent --output "${output}" --write-out '%{http_code}' \
      --request POST "http://127.0.0.1:${api_port}${path}"
  fi
}

poll_until() {
  local description="$1"
  local timeout_seconds="$2"
  local check_function="$3"
  local diagnostic_function="$4"
  local deadline=$((SECONDS + timeout_seconds))
  while ((SECONDS < deadline)); do
    if "${check_function}"; then
      return 0
    fi
    sleep "${poll_interval_seconds}"
  done
  echo "timed out after ${timeout_seconds}s waiting for ${description}" >&2
  "${diagnostic_function}" >&2 || true
  return 1
}

start_port_forward() {
  stop_port_forward
  : >"${port_forward_log}"
  kubectl --namespace "${namespace}" port-forward "service/${api_service}" "${api_port}:8080" \
    >"${port_forward_log}" 2>&1 &
  port_forward_pid="$!"
}

api_ready() {
  [[ "${port_forward_pid}" =~ ^[0-9]+$ ]] && kill -0 "${port_forward_pid}" 2>/dev/null &&
    curl --silent --fail "http://127.0.0.1:${api_port}/health/ready" >/dev/null
}

api_readiness_diagnostic() {
  echo "diagnostic: CDC pod readiness and port-forward log"
  kubectl --namespace "${namespace}" get pod "${cdc_pod}" -o wide || true
  tail -n 40 "${port_forward_log}" || true
}

canonical_projection() {
  local table="$1"
  mysql_query "
    SELECT CONCAT(
      id, '|', HEX(run_id), '|', HEX(status), '|', CAST(amount AS CHAR), '|',
      IF(note IS NULL, 'NULL', CONCAT('HEX:', HEX(note))), '|',
      DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s.%fZ')
    )
    FROM ${table}
    WHERE id BETWEEN ${id_start} AND ${id_end} AND run_id = '${run_id}'
    ORDER BY id;"
}

scope_matches() {
  local expected_count="$1"
  local source_count
  local target_count
  local source_projection
  local target_projection
  source_count="$(mysql_query "SELECT COUNT(*) FROM ${source_table} WHERE id BETWEEN ${id_start} AND ${id_end} AND run_id = '${run_id}';")" || return 1
  target_count="$(mysql_query "SELECT COUNT(*) FROM ${target_table} WHERE id BETWEEN ${id_start} AND ${id_end} AND run_id = '${run_id}';")" || return 1
  [[ "${source_count}" == "${expected_count}" && "${target_count}" == "${expected_count}" ]] || return 1
  source_projection="$(canonical_projection "${source_table}")" || return 1
  target_projection="$(canonical_projection "${target_table}")" || return 1
  [[ "${source_projection}" == "${target_projection}" ]]
}

workflow_running() {
  local status_file="${temporary_directory}/workflow-status.json"
  api_get "/api/v1/workflows/${workflow_id}" "${status_file}" || return 1
  jq -e '.status == "running"' "${status_file}" >/dev/null
}

ledger_count() {
  mysql_query "SELECT COUNT(*) FROM pekko_sync_batch_ledger WHERE workflow_id = '${workflow_id}' AND execution_id = '${execution_id}';"
}

offset_count() {
  mysql_query "SELECT COUNT(*) FROM ${offset_table} WHERE LOCATE('${connector_id}', COALESCE(offset_key, '')) > 0;"
}

history_count() {
  mysql_query "SELECT COUNT(*) FROM ${history_table} WHERE LOCATE('${connector_id}', COALESCE(history_data, '')) > 0;"
}

offset_timestamp() {
  mysql_query "
    SELECT COALESCE(DATE_FORMAT(MAX(record_insert_ts), '%Y-%m-%dT%H:%i:%s.%fZ'), '')
    FROM ${offset_table}
    WHERE LOCATE('${connector_id}', COALESCE(offset_key, '')) > 0;"
}

offset_payload() {
  mysql_query "
    SELECT COALESCE(HEX(offset_val), '')
    FROM ${offset_table}
    WHERE LOCATE('${connector_id}', COALESCE(offset_key, '')) > 0
    ORDER BY record_insert_ts DESC, record_insert_seq DESC
    LIMIT 1;"
}

start_offset_lock() {
  local lock_log="${temporary_directory}/offset-lock.log"
  local probe_log="${temporary_directory}/offset-lock-probe.log"
  kubectl --namespace "${namespace}" exec -i "${mysql_pod}" -- sh -ceu '
    workflow_password="$(cat)"
    [ -n "${workflow_password}" ] || exit 1
    MYSQL_PWD="${workflow_password}" exec mysql \
      --user=pekko_workflow --database=pekko_cdc_acceptance \
      --batch --skip-column-names --raw --execute "$1"
  ' -- "
    START TRANSACTION;
    SELECT id
    FROM ${offset_table}
    WHERE LOCATE('${connector_id}', COALESCE(offset_key, '')) > 0
    ORDER BY record_insert_ts DESC, record_insert_seq DESC
    LIMIT 1
    FOR UPDATE;
    DO SLEEP(180);
    ROLLBACK;" <"${workflow_password_file}" >"${lock_log}" 2>&1 &
  offset_lock_pid="$!"

  sleep 1
  kill -0 "${offset_lock_pid}" 2>/dev/null || {
    cat "${lock_log}" >&2 || true
    fail "the connector offset-row lock exited before acquisition"
  }

  if kubectl --namespace "${namespace}" exec -i "${mysql_pod}" -- sh -ceu '
    workflow_password="$(cat)"
    [ -n "${workflow_password}" ] || exit 1
    MYSQL_PWD="${workflow_password}" exec mysql \
      --user=pekko_workflow --database=pekko_cdc_acceptance \
      --batch --skip-column-names --raw --execute "$1"
  ' -- "
    START TRANSACTION;
    SELECT id
    FROM ${offset_table}
    WHERE LOCATE('${connector_id}', COALESCE(offset_key, '')) > 0
    ORDER BY record_insert_ts DESC, record_insert_seq DESC
    LIMIT 1
    FOR UPDATE NOWAIT;
    ROLLBACK;" <"${workflow_password_file}" >"${probe_log}" 2>&1; then
    fail "the connector offset-row lock was not acquired"
  fi
  grep -q 'ERROR 3572' "${probe_log}" || {
    cat "${probe_log}" >&2 || true
    fail "the connector offset-row lock probe failed unexpectedly"
  }
}

initial_snapshot_ready() {
  local ledger_rows
  local offset_rows
  local history_rows
  scope_matches 3 || return 1
  workflow_running || return 1
  ledger_rows="$(ledger_count)" || return 1
  offset_rows="$(offset_count)" || return 1
  history_rows="$(history_count)" || return 1
  [[ "${ledger_rows}" =~ ^[0-9]+$ && "${offset_rows}" =~ ^[0-9]+$ && "${history_rows}" =~ ^[0-9]+$ ]] || return 1
  ((ledger_rows >= 1 && offset_rows >= 1 && history_rows >= 1))
}

acceptance_diagnostic() {
  local source_projection
  local target_projection
  echo "diagnostic query: run-scoped source projection"
  source_projection="$(canonical_projection "${source_table}")" || source_projection="query-failed"
  printf '%s\n' "${source_projection}"
  echo "diagnostic query: run-scoped target projection"
  target_projection="$(canonical_projection "${target_table}")" || target_projection="query-failed"
  printf '%s\n' "${target_projection}"
  echo "diagnostic query: ledger, offset, and history counts"
  mysql_query "
    SELECT CONCAT(
      (SELECT COUNT(*) FROM pekko_sync_batch_ledger WHERE workflow_id = '${workflow_id}' AND execution_id = '${execution_id}'), ':',
      (SELECT COUNT(*) FROM ${offset_table} WHERE LOCATE('${connector_id}', COALESCE(offset_key, '')) > 0), ':',
      (SELECT COUNT(*) FROM ${history_table} WHERE LOCATE('${connector_id}', COALESCE(history_data, '')) > 0)
    );" || true
  curl --silent "http://127.0.0.1:${api_port}/api/v1/workflows/${workflow_id}" || true
  echo
}

scope_three_ready() {
  scope_matches 3
}

scope_two_ready() {
  scope_matches 2
}

offset_advanced() {
  local current_timestamp
  current_timestamp="$(offset_timestamp)" || return 1
  [[ -n "${current_timestamp}" && "${current_timestamp}" > "${offset_baseline_timestamp}" ]]
}

ledger_advanced() {
  local current_ledger_rows
  current_ledger_rows="$(ledger_count)" || return 1
  [[ "${current_ledger_rows}" =~ ^[0-9]+$ ]] || return 1
  ((current_ledger_rows > ledger_before_mutation))
}

recovered_ready() {
  local current_ledger_rows
  workflow_running || return 1
  scope_matches 3 || return 1
  offset_advanced || return 1
  current_ledger_rows="$(ledger_count)" || return 1
  [[ "${current_ledger_rows}" =~ ^[0-9]+$ ]] || return 1
  ((current_ledger_rows > ledger_before_restart))
}

new_pod_ready() {
  local current_uid
  pod_ready "${cdc_pod}" || return 1
  current_uid="$(kubectl --namespace "${namespace}" get pod "${cdc_pod}" -o jsonpath='{.metadata.uid}' 2>/dev/null || true)"
  [[ -n "${current_uid}" && "${current_uid}" != "${old_pod_uid}" ]]
}

new_pod_diagnostic() {
  echo "diagnostic: exact replacement pod state"
  kubectl --namespace "${namespace}" get pod "${cdc_pod}" -o wide || true
  kubectl --namespace "${namespace}" describe pod "${cdc_pod}" | tail -n 80 || true
}

# Fail closed before the first data mutation.
replicas="$(kubectl --namespace "${namespace}" get statefulset "${cdc_statefulset}" -o jsonpath='{.spec.replicas}')"
[[ "${replicas}" == "1" ]] || fail "${cdc_statefulset} must have exactly one replica"

cdc_pods="$(kubectl --namespace "${namespace}" get pods --selector app=pekko-cdc-single \
  -o custom-columns=NAME:.metadata.name --no-headers | awk 'NF { print $1 }')"
cdc_pod_count="$(awk 'NF { count += 1 } END { print count + 0 }' <<<"${cdc_pods}")"
[[ "${cdc_pod_count}" == "1" && "${cdc_pods}" == "${cdc_pod}" ]] || \
  fail "expected only ${cdc_pod}, found ${cdc_pod_count} matching pods"
pod_ready "${cdc_pod}" || fail "${cdc_pod} is not Ready"

kubectl --namespace "${namespace}" exec "${cdc_pod}" -- sh -ceu '
  [ "${CDC_OFFSET_FLUSH_INTERVAL_MS:-}" = "60000" ]
' || fail "${cdc_pod} must run with CDC_OFFSET_FLUSH_INTERVAL_MS=60000"

resolve_mysql_pod
pod_ready "${mysql_pod}" || fail "${mysql_pod} is not Ready"
secret_to_file pekko-workflow-db "${workflow_password_file}"
secret_to_file pekko-cdc-db "${cdc_password_file}"

mysql_runtime="$(mysql_query "SELECT CONCAT(@@GLOBAL.log_bin, ':', @@GLOBAL.binlog_format, ':', @@GLOBAL.binlog_row_image);")"
[[ "${mysql_runtime}" == "1:ROW:FULL" ]] || fail "MySQL must use log_bin=1, binlog_format=ROW, and binlog_row_image=FULL"

start_port_forward
poll_until "the single-node API readiness endpoint" 45 api_ready api_readiness_diagnostic

random_value="$(od -An -N4 -tu4 /dev/urandom | tr -d '[:space:]')"
[[ "${random_value}" =~ ^[0-9]+$ ]] || fail "failed to generate a numeric run suffix"
printf -v random_hex '%08x' "${random_value}"
utc_stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
suffix="${utc_stamp}-${random_hex}"
run_id="cdc-${suffix}"
workflow_id="mysql-cdc-${suffix}"
connector_id="mysql-cdc-${suffix}"
request_id="${suffix}-start"
state_suffix="$(printf '%s' "${connector_id}" | sha256sum | awk '{print substr($1, 1, 32)}')"
[[ "${state_suffix}" =~ ^[0-9a-f]{32}$ ]] || fail "failed to derive connector state-table suffix"
offset_table="debezium_offset_storage_${state_suffix}"
history_table="debezium_database_history_${state_suffix}"

# The row range and server ID are the same unique allocation. Retained source
# rows therefore prevent server-ID reuse, and concurrent collisions fail on PK.
id_start=$((540000000 + random_value % 9999997))
id_end=$((id_start + 3))
server_id="${id_start}"
((id_start >= 540000000 && id_end <= 549999999)) || fail "generated ID allocation is outside the reserved range"

existing_rows="$(mysql_query "
  SELECT (
    (SELECT COUNT(*) FROM ${source_table} WHERE id BETWEEN ${id_start} AND ${id_end}) +
    (SELECT COUNT(*) FROM ${target_table} WHERE id BETWEEN ${id_start} AND ${id_end})
  );")"
[[ "${existing_rows}" == "0" ]] || fail "selected ID range ${id_start}..${id_end} is not empty in both acceptance tables"

mysql_query "
  START TRANSACTION;
  INSERT INTO ${source_table} (id, run_id, status, amount, note, updated_at) VALUES
    (${id_start}, '${run_id}', 'new', 10.00, 'baseline-1', UTC_TIMESTAMP(6)),
    ($((id_start + 1)), '${run_id}', 'new', 20.00, 'baseline-2', UTC_TIMESTAMP(6)),
    ($((id_start + 2)), '${run_id}', 'new', 30.00, 'baseline-3', UTC_TIMESTAMP(6));
  COMMIT;"

created_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
jq -n \
  --arg workflow_id "${workflow_id}" \
  --arg connector_id "${connector_id}" \
  --arg created_at "${created_at}" \
  --argjson server_id "${server_id}" '
  {
    id: $workflow_id,
    name: "MySQL CDC acceptance",
    description: "Run-scoped MySQL snapshot and binlog acceptance",
    version: "1",
    author: "bigdata-lab",
    tags: ["e2e", "mysql-cdc"],
    nodes: [
      {
        id: "source-1", type: "source", nodeType: "mysql.cdc", label: "MySQL CDC",
        position: {x: 0, y: 0},
        config: {
          connectorId: $connector_id,
          host: "mysql", port: 3306,
          database: "pekko_cdc_acceptance", table: "pekko_cdc_source_acceptance",
          username: "pekko_cdc", passwordEnv: "MYSQL_CDC_PASSWORD",
          serverId: $server_id, maxBatchSize: 10, pollIntervalMillis: 100
        }
      },
      {
        id: "sink-1", type: "sink", nodeType: "mysql.cdc.apply", label: "MySQL CDC apply",
        position: {x: 300, y: 0},
        config: {
          host: "mysql", port: 3306,
          database: "pekko_cdc_acceptance", table: "pekko_cdc_target_acceptance",
          username: "pekko_workflow", passwordEnv: "DB_PASSWORD"
        }
      }
    ],
    edges: [{id: "source-to-sink", source: "source-1", target: "sink-1"}],
    metadata: {createdAt: $created_at, updatedAt: $created_at}
  }
' >"${workflow_definition}"

define_status="$(api_post "/api/v1/workflows" "${temporary_directory}/define.json" "${workflow_definition}")"
if [[ "${define_status}" != "201" ]]; then
  jq -c . "${temporary_directory}/define.json" >&2 || true
  fail "workflow definition failed with HTTP ${define_status}; it is not safe to reuse"
fi

execute_status="$(api_post "/api/v1/workflows/${workflow_id}/execute?requestId=${request_id}" "${temporary_directory}/execute.json")"
if [[ "${execute_status}" != "202" ]]; then
  jq -c . "${temporary_directory}/execute.json" >&2 || true
  fail "workflow execution failed with HTTP ${execute_status}"
fi
execution_id="$(json_extract "${temporary_directory}/execute.json" '.executionId')"
[[ "${execution_id}" =~ ^[A-Za-z0-9._-]+$ ]] || fail "API returned an unsafe execution ID"

poll_until "initial snapshot, running workflow, ledger, offset, and history evidence" \
  "${poll_timeout_seconds}" initial_snapshot_ready acceptance_diagnostic

mysql_query "
  START TRANSACTION;
  INSERT INTO ${source_table} (id, run_id, status, amount, note, updated_at)
    VALUES (${id_end}, '${run_id}', 'new', 40.00, 'live-insert', UTC_TIMESTAMP(6));
  UPDATE ${source_table}
    SET status = 'paid', amount = 11.11, note = 'live-update', updated_at = UTC_TIMESTAMP(6)
    WHERE id = ${id_start} AND run_id = '${run_id}';
  DELETE FROM ${source_table}
    WHERE id = $((id_start + 1)) AND run_id = '${run_id}';
  COMMIT;"
poll_until "ordered live insert, update, and delete" \
  "${poll_timeout_seconds}" scope_three_ready acceptance_diagnostic

# Hold only this connector's offset row so the sink and actor ledger can commit
# while Debezium cannot persist the corresponding source offset.
offset_baseline_timestamp="$(offset_timestamp)"
[[ -n "${offset_baseline_timestamp}" ]] || fail "connector offset timestamp is absent before replay-window setup"
offset_baseline_payload="$(offset_payload)"
[[ "${offset_baseline_payload}" =~ ^[0-9A-F]+$ ]] || fail "connector offset payload is absent before replay-window setup"
start_offset_lock
ledger_before_mutation="$(ledger_count)"
[[ "${ledger_before_mutation}" =~ ^[0-9]+$ ]] || fail "ledger count is invalid before replay-window setup"

mysql_query "
  UPDATE ${source_table}
  SET status = 'shipped', amount = 33.33, note = 'replay-window', updated_at = UTC_TIMESTAMP(6)
  WHERE id = $((id_start + 2)) AND run_id = '${run_id}';"
poll_until "target visibility inside the delayed-offset replay window" \
  "${poll_timeout_seconds}" scope_three_ready acceptance_diagnostic
poll_until "the actor ledger commit inside the blocked-offset replay window" \
  "${poll_timeout_seconds}" ledger_advanced acceptance_diagnostic

old_pod_uid="$(kubectl --namespace "${namespace}" get pod "${cdc_pod}" -o jsonpath='{.metadata.uid}')"
[[ -n "${old_pod_uid}" ]] || fail "could not resolve the original CDC pod UID"
ledger_before_restart="$(ledger_count)"
[[ "${ledger_before_restart}" =~ ^[0-9]+$ ]] || fail "ledger count is invalid before the pod restart"
stop_port_forward
kubectl --namespace "${namespace}" delete pod pekko-cdc-single-0 --wait=true
stop_offset_lock
offset_after_crash="$(offset_payload)"
[[ "${offset_after_crash}" == "${offset_baseline_payload}" ]] || \
  fail "connector offset advanced despite the isolated table lock"
poll_until "a Ready replacement for exactly ${cdc_pod}" \
  "${poll_timeout_seconds}" new_pod_ready new_pod_diagnostic
new_pod_uid="$(kubectl --namespace "${namespace}" get pod "${cdc_pod}" -o jsonpath='{.metadata.uid}')"

start_port_forward
poll_until "the restarted single-node API readiness endpoint" 60 api_ready api_readiness_diagnostic
poll_until "the same running execution, advanced offset, and recovered target state" \
  "${poll_timeout_seconds}" recovered_ready acceptance_diagnostic

mysql_query "
  START TRANSACTION;
  UPDATE ${source_table}
    SET status = 'settled', amount = 44.44, note = 'post-restart-update', updated_at = UTC_TIMESTAMP(6)
    WHERE id = ${id_end} AND run_id = '${run_id}';
  DELETE FROM ${source_table}
    WHERE id = $((id_start + 2)) AND run_id = '${run_id}';
  COMMIT;"
poll_until "post-restart update and delete" \
  "${poll_timeout_seconds}" scope_two_ready acceptance_diagnostic

source_rows="$(mysql_query "SELECT COUNT(*) FROM ${source_table} WHERE id BETWEEN ${id_start} AND ${id_end} AND run_id = '${run_id}';")"
target_rows="$(mysql_query "SELECT COUNT(*) FROM ${target_table} WHERE id BETWEEN ${id_start} AND ${id_end} AND run_id = '${run_id}';")"
ledger_rows="$(ledger_count)"
offset_rows="$(offset_count)"
history_rows="$(history_count)"
cursor_evidence="$(mysql_query "
  SELECT CONCAT(
    COALESCE(JSON_UNQUOTE(JSON_EXTRACT(cursor_value, '$.offset.file')), 'snapshot'), ':',
    COALESCE(JSON_UNQUOTE(JSON_EXTRACT(cursor_value, '$.offset.pos')), 'unknown')
  )
  FROM pekko_sync_batch_ledger
  WHERE workflow_id = '${workflow_id}' AND execution_id = '${execution_id}'
  ORDER BY batch_sequence DESC
  LIMIT 1;")"
[[ "${cursor_evidence}" =~ ^[A-Za-z0-9._-]+:[0-9]+$ ]] || fail "sanitized binlog cursor evidence is unavailable"

printf 'CDC_E2E_PASS run_id=%s workflow_id=%s connector_id=%s server_id=%s execution_id=%s id_range=%s..%s source_rows=%s target_rows=%s ledger_rows=%s offset_rows=%s history_rows=%s old_pod_uid=%s new_pod_uid=%s cursor=%s\n' \
  "${run_id}" "${workflow_id}" "${connector_id}" "${server_id}" "${execution_id}" \
  "${id_start}" "${id_end}" "${source_rows}" "${target_rows}" "${ledger_rows}" \
  "${offset_rows}" "${history_rows}" "${old_pod_uid}" "${new_pod_uid}" "${cursor_evidence}"
