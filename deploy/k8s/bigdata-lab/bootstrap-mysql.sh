#!/usr/bin/env bash
set -euo pipefail

: "${WORKFLOW_DB_PASSWORD:?WORKFLOW_DB_PASSWORD must be set}"
: "${MYSQL_CDC_PASSWORD:?MYSQL_CDC_PASSWORD must be set}"

reject_line_breaks() {
  local variable_name="$1"
  local value="$2"
  case "${value}" in
    *$'\r'*|*$'\n'*)
      echo "${variable_name} must not contain CR or LF" >&2
      exit 1
      ;;
  esac
}

# POSIX process environments cannot represent NUL bytes. Reject the other line
# separators explicitly before the first kubectl invocation.
reject_line_breaks WORKFLOW_DB_PASSWORD "${WORKFLOW_DB_PASSWORD}"
reject_line_breaks MYSQL_CDC_PASSWORD "${MYSQL_CDC_PASSWORD}"

namespace="${NAMESPACE:-bigdata-lab}"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
umask 077
workflow_password_input_file="${temporary_directory}/workflow-password-input"
workflow_password_secret_file="${temporary_directory}/workflow-password-secret"
cdc_secret_password_file="${temporary_directory}/cdc-password"
printf '%s' "${WORKFLOW_DB_PASSWORD}" >"${workflow_password_input_file}"
printf '%s' "${MYSQL_CDC_PASSWORD}" >"${cdc_secret_password_file}"
chmod 0600 "${workflow_password_input_file}" "${cdc_secret_password_file}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "${script_dir}/../../.." && pwd)"
persistence_schema="${repository_root}/pekko-server/src/main/resources/db/mysql/pekko-persistence-schema.sql"
ledger_schema="${repository_root}/pekko-server/src/main/resources/db/mysql/pekko-sync-ledger-schema.sql"
cdc_schema="${repository_root}/pekko-server/src/main/resources/db/mysql/pekko-cdc-schema.sql"

for schema in "${persistence_schema}" "${ledger_schema}" "${cdc_schema}"; do
  [[ -f "${schema}" ]] || { echo "required schema is missing: ${schema}" >&2; exit 1; }
done

if [[ -n "${MYSQL_POD:-}" ]]; then
  mysql_pod="${MYSQL_POD}"
  mysql_pod_phase="$(kubectl --namespace "${namespace}" get pod "${mysql_pod}" -o jsonpath='{.status.phase}')"
  [[ "${mysql_pod_phase}" == "Running" ]] || {
    echo "MYSQL_POD must name a Running pod" >&2
    exit 1
  }
else
  running_mysql_pods="$(kubectl --namespace "${namespace}" get pods \
    --selector app=mysql \
    --field-selector status.phase=Running \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')"
  running_mysql_pod_count="$(printf '%s\n' "${running_mysql_pods}" | awk 'NF { count += 1 } END { print count + 0 }')"
  [[ "${running_mysql_pod_count}" == "1" ]] || {
    echo "expected exactly one Running pod with app=mysql; found ${running_mysql_pod_count}" >&2
    exit 1
  }
  mysql_pod="$(printf '%s\n' "${running_mysql_pods}" | awk 'NF { print; exit }')"
fi
printf '%s\n' "${mysql_pod}"

if ! kubectl --namespace "${namespace}" get secret pekko-workflow-db \
  -o go-template='{{index .data "password" | base64decode}}' >"${workflow_password_secret_file}"; then
  echo "existing pekko-workflow-db/password is required" >&2
  exit 1
fi
chmod 0600 "${workflow_password_secret_file}"
if ! cmp -s "${workflow_password_input_file}" "${workflow_password_secret_file}"; then
  echo "WORKFLOW_DB_PASSWORD does not match existing pekko-workflow-db/password" >&2
  exit 1
fi

mysql_as_root() {
  kubectl --namespace "${namespace}" exec -i "${mysql_pod}" -- sh -ceu '
    root_value="$(printenv | awk -F= '\''$1 ~ /^MYSQL_ROOT_.*PASSWORD$/ { print substr($0, index($0, "=") + 1); exit }'\'')"
    [ -n "${root_value}" ] || { echo "MySQL root credential is not available in the pod environment" >&2; exit 1; }
    MYSQL_PWD="${root_value}" exec mysql -uroot "$@"
  ' -- "$@"
}

escape_sql_literal() {
  sed -e 's/\\/\\\\/g' -e "s/'/''/g"
}

mysql_scalar() {
  local query="$1"
  mysql_as_root --batch --skip-column-names --raw -e "${query}"
}

require_no_mandatory_roles() {
  local mandatory_roles
  mandatory_roles="$(mysql_scalar 'SELECT @@GLOBAL.mandatory_roles;')"
  [[ -z "${mandatory_roles}" ]] || {
    echo "MySQL mandatory_roles must be empty for exact CDC privileges" >&2
    exit 1
  }
}

require_no_cdc_roles() {
  local role_count
  role_count="$(mysql_scalar "SELECT COUNT(*) FROM mysql.role_edges WHERE TO_USER = 'pekko_cdc' AND TO_HOST = '%';")"
  [[ "${role_count}" == "0" ]] || {
    echo "pekko_cdc must not have assigned roles" >&2
    exit 1
  }
}

require_no_mandatory_roles
require_no_cdc_roles

{
  printf "SET SESSION sql_mode='';\n"
  printf "CREATE DATABASE IF NOT EXISTS pekko_cdc_acceptance;\n"
  printf "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON pekko_cdc_acceptance.* TO 'pekko_workflow'@'%%';\n"
  printf "DROP USER IF EXISTS 'pekko_cdc'@'%%';\n"
  printf "CREATE USER 'pekko_cdc'@'%%' IDENTIFIED BY '"
  printf '%s' "${MYSQL_CDC_PASSWORD}" | escape_sql_literal
  printf "';\n"
  printf "GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'pekko_cdc'@'%%';\n"
} | mysql_as_root

require_no_mandatory_roles
require_no_cdc_roles
cdc_privileges="$(mysql_scalar "SELECT CONCAT(COUNT(*), ':', COALESCE(GROUP_CONCAT(PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE SEPARATOR ','), '')) FROM information_schema.USER_PRIVILEGES WHERE GRANTEE = CONCAT(CHAR(39), 'pekko_cdc', CHAR(39), '@', CHAR(39), '%', CHAR(39));")"
expected_cdc_privileges='5:RELOAD,REPLICATION CLIENT,REPLICATION SLAVE,SELECT,SHOW DATABASES'
[[ "${cdc_privileges}" == "${expected_cdc_privileges}" ]] || {
  echo "pekko_cdc global privileges do not match the required five-item set" >&2
  exit 1
}

# The checked-in schema has an unconditional index creation. Apply every other
# statement, then add that index only when information_schema says it is absent.
apply_persistence_schema() {
  local database="$1"
  sed '/^CREATE UNIQUE INDEX event_journal_ordering_idx ON event_journal(ordering);$/d' "${persistence_schema}" | mysql_as_root "${database}"
  mysql_as_root "${database}" <<'SQL'
SET @event_journal_ordering_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'event_journal'
    AND index_name = 'event_journal_ordering_idx'
);
SET @event_journal_ordering_index_sql = IF(
  @event_journal_ordering_index_exists = 0,
  'CREATE UNIQUE INDEX event_journal_ordering_idx ON event_journal(ordering)',
  'SELECT 1'
);
PREPARE event_journal_ordering_index_statement FROM @event_journal_ordering_index_sql;
EXECUTE event_journal_ordering_index_statement;
DEALLOCATE PREPARE event_journal_ordering_index_statement;
SQL
}

apply_persistence_schema pekko_cdc_acceptance
mysql_as_root pekko_cdc_acceptance <"${ledger_schema}"
mysql_as_root pekko_cdc_acceptance <"${cdc_schema}"

kubectl --namespace "${namespace}" create secret generic pekko-cdc-db \
  --from-file=password="${cdc_secret_password_file}" \
  --dry-run=client -o yaml | kubectl --namespace "${namespace}" apply -f -
