#!/usr/bin/env bash
set -euo pipefail

api_base="${GRAVITINO_API_BASE:-http://gravitino:8090/api}"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT

request_status() {
  local method="$1"
  local path="$2"
  local body_file="$3"
  local response_file="$4"
  if [[ -n "${body_file}" ]]; then
    curl --silent --show-error --output "${response_file}" --write-out '%{http_code}' \
      --request "${method}" --header 'Accept: application/vnd.gravitino.v1+json' \
      --header 'Content-Type: application/json' \
      --data-binary "@${body_file}" "${api_base}${path}"
  else
    curl --silent --show-error --output "${response_file}" --write-out '%{http_code}' \
      --request "${method}" --header 'Accept: application/vnd.gravitino.v1+json' \
      --header 'Content-Type: application/json' "${api_base}${path}"
  fi
}

require_ok_or_not_found() {
  local status="$1"
  local response_file="$2"
  case "${status}" in
    200) return 0 ;;
    404) return 1 ;;
    *) echo "Gravitino returned HTTP ${status}" >&2; cat "${response_file}" >&2; exit 1 ;;
  esac
}

validate_metalake() {
  local response_file="$1"
  jq -e '.code == 0 and .metalake.name == "pekko" and .metalake.comment == "Pekko workflow metadata"' "${response_file}" >/dev/null || {
    echo "metalake pekko response does not match" >&2
    exit 1
  }
}

validate_catalog() {
  local response_file="$1"
  jq -e '.code == 0 and .catalog.name == "bigdata-kafka" and .catalog.type == "messaging" and .catalog.provider == "kafka" and .catalog.comment == "Pekko workflow Kafka catalog" and .catalog.properties["bootstrap.servers"] == "kafka:9092"' "${response_file}" >/dev/null || {
    echo "catalog bigdata-kafka response does not match the required messaging Kafka configuration" >&2
    exit 1
  }
}

metalake_response="${temporary_directory}/metalake.json"
metalake_status="$(request_status GET /metalakes/pekko '' "${metalake_response}")"
if require_ok_or_not_found "${metalake_status}" "${metalake_response}"; then
  validate_metalake "${metalake_response}"
else
  metalake_body="${temporary_directory}/metalake-body.json"
  cat >"${metalake_body}" <<'JSON'
{"name":"pekko","comment":"Pekko workflow metadata"}
JSON
  metalake_create_response="${temporary_directory}/metalake-create.json"
  metalake_create_status="$(request_status POST /metalakes "${metalake_body}" "${metalake_create_response}")"
  [[ "${metalake_create_status}" =~ ^20[01]$ ]] || { echo "failed to create metalake pekko: HTTP ${metalake_create_status}" >&2; cat "${metalake_create_response}" >&2; exit 1; }
  validate_metalake "${metalake_create_response}"
  metalake_status="$(request_status GET /metalakes/pekko '' "${metalake_response}")"
  require_ok_or_not_found "${metalake_status}" "${metalake_response}" || {
    echo "created metalake pekko could not be loaded" >&2
    exit 1
  }
  validate_metalake "${metalake_response}"
fi

catalog_response="${temporary_directory}/catalog.json"
catalog_status="$(request_status GET /metalakes/pekko/catalogs/bigdata-kafka '' "${catalog_response}")"
if require_ok_or_not_found "${catalog_status}" "${catalog_response}"; then
  validate_catalog "${catalog_response}"
else
  catalog_body="${temporary_directory}/catalog-body.json"
  cat >"${catalog_body}" <<'JSON'
{
  "name": "bigdata-kafka",
  "type": "messaging",
  "provider": "kafka",
  "comment": "Pekko workflow Kafka catalog",
  "properties": {"bootstrap.servers": "kafka:9092"}
}
JSON
  catalog_create_response="${temporary_directory}/catalog-create.json"
  catalog_create_status="$(request_status POST /metalakes/pekko/catalogs "${catalog_body}" "${catalog_create_response}")"
  [[ "${catalog_create_status}" =~ ^20[01]$ ]] || { echo "failed to create catalog bigdata-kafka: HTTP ${catalog_create_status}" >&2; cat "${catalog_create_response}" >&2; exit 1; }
  validate_catalog "${catalog_create_response}"
  catalog_status="$(request_status GET /metalakes/pekko/catalogs/bigdata-kafka '' "${catalog_response}")"
  require_ok_or_not_found "${catalog_status}" "${catalog_response}" || {
    echo "created catalog bigdata-kafka could not be loaded" >&2
    exit 1
  }
  validate_catalog "${catalog_response}"
fi
