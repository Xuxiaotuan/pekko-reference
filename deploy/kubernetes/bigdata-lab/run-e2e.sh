#!/usr/bin/env bash
set -euo pipefail

: "${WORKFLOW_DB_PASSWORD:?WORKFLOW_DB_PASSWORD must be set}"

namespace="${NAMESPACE:-bigdata-lab}"
kafka_pod="${KAFKA_POD:-kafka-0}"
mysql_pod="${MYSQL_POD:-mysql-0}"
api_service="${API_SERVICE:-pekko-workflow-api}"
topic="pekko-workflow-e2e"
existing_workflow_id="${EXECUTE_EXISTING_WORKFLOW_ID:-}"
produce_start_text="${PRODUCE_START:-1}"
produce_count_text="${PRODUCE_COUNT:-50}"
acceptance_run_id="acceptance-$(date +%s%N)-$$"
if [[ -n "${existing_workflow_id}" && -n "${WORKFLOW_ID:-}" ]]; then
  echo "set only one of EXECUTE_EXISTING_WORKFLOW_ID or WORKFLOW_ID" >&2
  exit 1
fi
workflow_id="${existing_workflow_id:-${WORKFLOW_ID:-kafka-gravitino-e2e-$(date +%s%N)-$$}}"
[[ "${workflow_id}" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo "workflow ID may contain only letters, digits, dot, underscore, and hyphen" >&2
  exit 1
}
[[ "${acceptance_run_id}" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo "acceptance run ID may contain only letters, digits, dot, underscore, and hyphen" >&2
  exit 1
}
[[ "${produce_start_text}" =~ ^[0-9]+$ && "${produce_count_text}" =~ ^[0-9]+$ ]] || {
  echo "PRODUCE_START and PRODUCE_COUNT must be decimal integers" >&2
  exit 1
}
(( ${#produce_start_text} <= 4 && ${#produce_count_text} <= 4 )) || {
  echo "PRODUCE_START and PRODUCE_COUNT must contain at most 4 decimal digits" >&2
  exit 1
}
produce_start=$((10#${produce_start_text}))
produce_count=$((10#${produce_count_text}))
produce_end=$((produce_start + produce_count - 1))
((produce_start >= 1 && produce_count >= 1 && produce_count <= 50 && produce_end <= 9999)) || {
  echo "PRODUCE_START must be at least 1, PRODUCE_COUNT must be 1..50, and the final event ID must not exceed 9999" >&2
  exit 1
}
printf -v first_event_id 'event-%04d' "${produce_start}"
printf -v last_event_id 'event-%04d' "${produce_end}"
expected_ledger_batches=$(((produce_count + 9) / 10))

kafka_command() {
  kubectl --namespace "${namespace}" exec -i "${kafka_pod}" -- sh -ceu '
    tool="$1"
    shift
    command -v "${tool}" >/dev/null 2>&1 || tool="/opt/kafka/bin/${tool}"
    exec "${tool}" "$@"
  ' -- "$@"
}

mysql_query() {
  local query="$1"
  printf '%s' "${WORKFLOW_DB_PASSWORD}" | kubectl --namespace "${namespace}" exec -i "${mysql_pod}" -- sh -ceu '
    app_value="$(cat)"
    [ -n "${app_value}" ] || { echo "workflow database credential is not available in the pod environment" >&2; exit 1; }
    MYSQL_PWD="${app_value}" exec mysql -upekko_workflow pekko_workflow -N -e "$1"
  ' -- "${query}"
}

topic_list="$(kafka_command kafka-topics.sh --bootstrap-server kafka:9092 --list)"
if ! grep -Fqx -- "${topic}" <<<"${topic_list}"; then
  kafka_command kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists \
    --topic "${topic}" --partitions 3 --replication-factor 1
fi

topic_description="$(kafka_command kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic "${topic}")"
grep -Eq 'PartitionCount:[[:space:]]*3([[:space:]]|$)' <<<"${topic_description}" || {
  echo "topic ${topic} must have exactly three partitions" >&2
  exit 1
}

target_total_before="$(mysql_query "SELECT COUNT(*) FROM pekko_kafka_e2e_sink;")"
target_scope_before="$(mysql_query "SELECT COUNT(*) FROM pekko_kafka_e2e_sink WHERE id >= '${first_event_id}' AND id <= '${last_event_id}';")"
[[ "${target_total_before}" =~ ^[0-9]+$ && "${target_scope_before}" =~ ^[0-9]+$ ]] || {
  echo "MySQL returned a non-numeric acceptance count" >&2
  exit 1
}
[[ "${target_scope_before}" == "0" ]] || {
  echo "event range ${first_event_id}..${last_event_id} already contains ${target_scope_before} rows; choose a fresh PRODUCE_START" >&2
  exit 1
}

for sequence in $(seq "${produce_start}" "${produce_end}"); do
  printf '{"id":"event-%04d","value":{"sequence":%d,"runId":"%s"}}\n' "${sequence}" "${sequence}" "${acceptance_run_id}"
done | kafka_command kafka-console-producer.sh --bootstrap-server kafka:9092 --topic "${topic}"

temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
port_forward_log="${temporary_directory}/port-forward.log"
kubectl --namespace "${namespace}" port-forward "service/${api_service}" 18080:8080 >"${port_forward_log}" 2>&1 &
port_forward_pid="$!"
trap 'kill "${port_forward_pid}" 2>/dev/null || true; rm -rf "${temporary_directory}"' EXIT
for _ in $(seq 1 30); do
  curl --silent --fail http://127.0.0.1:18080/health/ready >/dev/null && break
  sleep 1
done
curl --silent --fail http://127.0.0.1:18080/health/ready >/dev/null

workflow_definition="${temporary_directory}/workflow.json"
if [[ -z "${existing_workflow_id}" ]]; then
cat >"${workflow_definition}" <<JSON
{
  "id": "${workflow_id}",
  "name": "Kafka Gravitino E2E",
  "description": "Bounded Kafka to MySQL acceptance workflow",
  "version": "1",
  "author": "bigdata-lab",
  "tags": ["e2e"],
  "nodes": [
    {
      "id": "source-1", "type": "source", "nodeType": "kafka.consumer", "label": "Kafka",
      "position": {"x": 0, "y": 0},
      "config": {
        "topic": "pekko-workflow-e2e",
        "gravitino": {"uri": "http://gravitino:8090", "metalake": "pekko", "catalog": "bigdata-kafka"},
        "offsetReset": "earliest", "chunkSize": 10, "maxRecords": 50, "maxDurationSeconds": 120
      }
    },
    {
      "id": "sink-1", "type": "sink", "nodeType": "mysql.write", "label": "MySQL",
      "position": {"x": 300, "y": 0},
      "config": {
        "host": "mysql", "port": 3306, "database": "pekko_workflow", "table": "pekko_kafka_e2e_sink",
        "username": "pekko_workflow", "passwordEnv": "WORKFLOW_DB_PASSWORD", "batchSize": 10, "mode": "upsert"
      }
    }
  ],
  "edges": [{"id": "source-to-sink", "source": "source-1", "target": "sink-1"}],
  "metadata": {"createdAt": "2026-08-30T00:00:00Z", "updatedAt": "2026-08-30T00:00:00Z"}
}
JSON

define_status="$(curl --silent --output "${temporary_directory}/define.json" --write-out '%{http_code}' --header 'Content-Type: application/json' --data-binary "@${workflow_definition}" http://127.0.0.1:18080/api/v1/workflows)"
if [[ "${define_status}" != "201" ]]; then
  cat "${temporary_directory}/define.json" >&2
  echo "workflow definition was not created; HTTP ${define_status} is not safe to reuse" >&2
  exit 1
fi
printf 'workflow_id=%s\n' "${workflow_id}"
else
  printf 'workflow_id=%s\n' "${workflow_id}"
  echo "executing the explicitly supplied existing workflow without reposting its definition" >&2
fi
request_id="${workflow_id}-$(date +%s%N)-$$"
execute_status="$(curl --silent --output "${temporary_directory}/execute.json" --write-out '%{http_code}' --request POST "http://127.0.0.1:18080/api/v1/workflows/${workflow_id}/execute?requestId=${request_id}")"
[[ "${execute_status}" == "202" ]] || { cat "${temporary_directory}/execute.json" >&2; exit 1; }
execution_id="$(jq -er '.executionId' "${temporary_directory}/execute.json")"
[[ "${execution_id}" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo "execution ID may contain only letters, digits, dot, underscore, and hyphen" >&2
  exit 1
}

for _ in $(seq 1 120); do
  workflow_state="$(curl --silent --fail "http://127.0.0.1:18080/api/v1/workflows/${workflow_id}/status" | jq -r '.state')"
  [[ "${workflow_state}" == "completed" ]] && break
  [[ "${workflow_state}" != "failed" ]] || { echo "workflow execution failed" >&2; exit 1; }
  sleep 1
done
[[ "${workflow_state}" == "completed" ]] || { echo "workflow did not complete within 120 seconds" >&2; exit 1; }

target_total_after="$(mysql_query "SELECT COUNT(*) FROM pekko_kafka_e2e_sink;")"
target_scope_after="$(mysql_query "SELECT COUNT(*) FROM pekko_kafka_e2e_sink WHERE id >= '${first_event_id}' AND id <= '${last_event_id}';")"
matching_run_count="$(mysql_query "SELECT COUNT(*) FROM pekko_kafka_e2e_sink WHERE id >= '${first_event_id}' AND id <= '${last_event_id}' AND JSON_UNQUOTE(JSON_EXTRACT(value, '$.runId')) = '${acceptance_run_id}';")"
ledger_summary="$(mysql_query "SELECT CONCAT(COUNT(*), ':', COALESCE(SUM(source_rows), 0), ':', COALESCE(SUM(target_rows), 0)) FROM pekko_sync_batch_ledger WHERE workflow_id = '${workflow_id}' AND execution_id = '${execution_id}';")"
[[ "${target_total_after}" =~ ^[0-9]+$ && "${target_scope_after}" =~ ^[0-9]+$ && "${matching_run_count}" =~ ^[0-9]+$ ]] || {
  echo "MySQL returned a non-numeric acceptance result" >&2
  exit 1
}
target_delta=$((target_total_after - target_total_before))
expected_ledger_summary="${expected_ledger_batches}:${produce_count}:${produce_count}"
[[ "${target_scope_after}" == "${produce_count}" ]] || { echo "expected ${produce_count} IDs in ${first_event_id}..${last_event_id}, got ${target_scope_after}" >&2; exit 1; }
[[ "${matching_run_count}" == "${produce_count}" ]] || { echo "expected ${produce_count} rows from acceptance run ${acceptance_run_id}, got ${matching_run_count}" >&2; exit 1; }
[[ "${target_delta}" == "${produce_count}" ]] || { echo "expected total target count to grow by ${produce_count}, got ${target_delta}" >&2; exit 1; }
[[ "${ledger_summary}" == "${expected_ledger_summary}" ]] || { echo "expected ledger batches:source_rows:target_rows ${expected_ledger_summary} for execution ${execution_id}, got ${ledger_summary}" >&2; exit 1; }
printf 'acceptance_run_id=%s event_range=%s..%s execution_id=%s\n' "${acceptance_run_id}" "${first_event_id}" "${last_event_id}" "${execution_id}"
