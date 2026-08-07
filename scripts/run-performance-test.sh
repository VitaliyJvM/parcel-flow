#!/usr/bin/env bash

set -Eeuo pipefail

# ParcelFlow performance test runner and report generator.
#
# Run from the repository root:
#   chmod +x scripts/run-performance-test.sh
#   ./scripts/run-performance-test.sh
#
# IMPORTANT:
#   RESET_STACK=true (the default) executes `docker compose down -v`.
#   This deletes the local Compose volumes so the measured dataset starts empty.
#
# Common overrides:
#   RESET_STACK=false ./scripts/run-performance-test.sh
#   SHIPMENT_COUNT=1000 VIRTUAL_USERS=50 TEST_DURATION=5m EVENT_RATE=500 \
#     DUPLICATE_PERCENTAGE=25 OUT_OF_ORDER_PERCENTAGE=20 \
#     ./scripts/run-performance-test.sh
#
# This script is compatible with the Bash 3.2 version shipped with macOS.

# -----------------------------------------------------------------------------
# Test configuration
# -----------------------------------------------------------------------------

PARCELFLOW_LOAD_TESTING_ENABLED="${PARCELFLOW_LOAD_TESTING_ENABLED:-true}"
RESET_STACK="${RESET_STACK:-true}"
START_STACK="${START_STACK:-true}"

SHIPMENT_COUNT="${SHIPMENT_COUNT:-200}"
VIRTUAL_USERS="${VIRTUAL_USERS:-20}"
TEST_DURATION="${TEST_DURATION:-2m}"
EVENT_RATE="${EVENT_RATE:-200}"
EVENTS_PER_REQUEST="${EVENTS_PER_REQUEST:-10}"
DUPLICATE_PERCENTAGE="${DUPLICATE_PERCENTAGE:-10}"
OUT_OF_ORDER_PERCENTAGE="${OUT_OF_ORDER_PERCENTAGE:-10}"

K6_IMAGE="${K6_IMAGE:-grafana/k6:0.57.0}"
K6_SERVICE="${K6_SERVICE:-k6}"
K6_PROFILE="${K6_PROFILE:-perf}"
K6_SCRIPT_HOST="${K6_SCRIPT_HOST:-performance/k6/parcelflow-load-test.js}"
K6_SCRIPT_CONTAINER="${K6_SCRIPT_CONTAINER:-/scripts/parcelflow-load-test.js}"
K6_COMPOSE_BASE_URL="${K6_COMPOSE_BASE_URL:-http://tracking-service:8080}"
K6_DOCKER_BASE_URL="${K6_DOCKER_BASE_URL:-http://host.docker.internal:8080}"

TRACKING_SERVICE="${TRACKING_SERVICE:-tracking-service}"
POSTGRES_SERVICE="${POSTGRES_SERVICE:-postgres}"
BROKER_SERVICE="${BROKER_SERVICE:-redpanda}"
REDIS_SERVICE="${REDIS_SERVICE:-redis}"

POSTGRES_USER="${POSTGRES_USER:-parcelflow}"
POSTGRES_DB="${POSTGRES_DB:-parcelflow}"
TRACKING_TOPIC="${TRACKING_TOPIC:-carrier-tracking-events}"

HOST_BASE_URL="${HOST_BASE_URL:-http://localhost:8080}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
DRAIN_TIMEOUT_SECONDS="${DRAIN_TIMEOUT_SECONDS:-60}"
PROMETHEUS_LOOKBACK="${PROMETHEUS_LOOKBACK:-10m}"

RESULTS_DIR="${RESULTS_DIR:-performance/results}"
REPORT_FILE="${REPORT_FILE:-docs/performance-results.md}"

# Optional configuration descriptions. The script attempts to discover these,
# but an explicit environment variable always wins.
APP_VERSION="${APP_VERSION:-}"
CONSUMER_CONCURRENCY="${CONSUMER_CONCURRENCY:-}"
HIKARI_POOL_SIZE="${HIKARI_POOL_SIZE:-}"
REDIS_TTL_DESCRIPTION="${REDIS_TTL_DESCRIPTION:-}"
DOCKER_RESOURCE_DESCRIPTION="${DOCKER_RESOURCE_DESCRIPTION:-}"

# The two lines of the report that describe the machine it ran on. Detected by
# default, and deliberately coarse: the report is committed to a public
# repository, so it records the class of machine a reader needs in order to
# judge the numbers, not a fingerprint of this one. The OS patch build is left
# out for that reason.
#
# Set either of these to say less, or to say something more useful than a
# detected string:
#   MACHINE_DESCRIPTION='Apple silicon laptop, 8 cores, 16 GB' \
#   OS_DESCRIPTION='macOS 26' ./scripts/run-performance-test.sh
MACHINE_DESCRIPTION="${MACHINE_DESCRIPTION:-}"
OS_DESCRIPTION="${OS_DESCRIPTION:-}"

# -----------------------------------------------------------------------------
# Paths
# -----------------------------------------------------------------------------

PROJECT_ROOT="$(pwd)"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RESULTS_PATH="${PROJECT_ROOT}/${RESULTS_DIR}"
REPORT_PATH="${PROJECT_ROOT}/${REPORT_FILE}"

SUMMARY_FILE="${RESULTS_PATH}/summary-${RUN_ID}.json"
OUTPUT_FILE="${RESULTS_PATH}/output-${RUN_ID}.txt"
CLIENT_SUMMARY_FILE="${RESULTS_PATH}/summary-${RUN_ID}.md"
METRICS_FILE="${RESULTS_PATH}/metrics-${RUN_ID}.txt"
PROM_BEFORE_FILE="${RESULTS_PATH}/prometheus-before-${RUN_ID}.txt"
PROM_AFTER_FILE="${RESULTS_PATH}/prometheus-after-${RUN_ID}.txt"

LATEST_SUMMARY_FILE="${RESULTS_PATH}/summary.json"
LATEST_CLIENT_SUMMARY_FILE="${RESULTS_PATH}/summary.md"

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

log() {
  printf '\n[%s] %s\n' "$(date '+%H:%M:%S')" "$*"
}

warn() {
  printf '\nWARNING: %s\n' "$*" >&2
}

fail() {
  printf '\nERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

is_true() {
  local normalized
  normalized="$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')"

  case "${normalized}" in
    true|1|yes|y) return 0 ;;
    *) return 1 ;;
  esac
}

is_number() {
  awk -v value="${1:-}" 'BEGIN {
    exit !(value ~ /^-?[0-9]+([.][0-9]+)?$/)
  }'
}

format_number() {
  local value="${1:-N/A}"
  local decimals="${2:-2}"

  if ! is_number "${value}"; then
    printf 'N/A'
  else
    awk -v value="${value}" -v decimals="${decimals}" \
      'BEGIN { printf "%.*f", decimals, value }'
  fi
}

format_integer() {
  local value="${1:-N/A}"

  if ! is_number "${value}"; then
    printf 'N/A'
  else
    awk -v value="${value}" 'BEGIN { printf "%.0f", value }'
  fi
}

format_percent_rate() {
  local value="${1:-N/A}"

  if ! is_number "${value}"; then
    printf 'N/A'
  else
    awk -v value="${value}" 'BEGIN { printf "%.2f%%", value * 100 }'
  fi
}

format_percent_value() {
  local value="${1:-N/A}"

  if ! is_number "${value}"; then
    printf 'N/A'
  else
    awk -v value="${value}" 'BEGIN { printf "%.1f%%", value }'
  fi
}

calculate_delta() {
  local before="${1:-N/A}"
  local after="${2:-N/A}"

  if ! is_number "${before}" || ! is_number "${after}"; then
    printf 'N/A'
  else
    awk -v before="${before}" -v after="${after}" \
      'BEGIN { printf "%.6f", after - before }'
  fi
}

safe_divide() {
  local numerator="${1:-N/A}"
  local denominator="${2:-N/A}"
  local scale="${3:-1}"

  if ! is_number "${numerator}" || ! is_number "${denominator}"; then
    printf 'N/A'
  elif awk -v denominator="${denominator}" 'BEGIN { exit !(denominator == 0) }'; then
    printf 'N/A'
  else
    awk -v numerator="${numerator}" -v denominator="${denominator}" -v scale="${scale}" \
      'BEGIN { printf "%.6f", (numerator / denominator) * scale }'
  fi
}

duration_to_seconds() {
  local duration="${1:-0}"

  awk -v duration="${duration}" '
    BEGIN {
      if (duration ~ /^[0-9]+s$/) {
        sub(/s$/, "", duration)
        print duration
      } else if (duration ~ /^[0-9]+m$/) {
        sub(/m$/, "", duration)
        print duration * 60
      } else if (duration ~ /^[0-9]+h$/) {
        sub(/h$/, "", duration)
        print duration * 3600
      } else {
        print 0
      }
    }
  '
}

compose_service_exists() {
  local service="$1"
  docker compose --profile "${K6_PROFILE}" config --services 2>/dev/null \
    | grep -qx "${service}"
}

container_image() {
  local service="$1"
  local container_id

  container_id="$(docker compose ps -q "${service}" 2>/dev/null | head -1 || true)"
  if [[ -z "${container_id}" ]]; then
    printf 'N/A'
    return
  fi

  docker inspect --format '{{.Config.Image}}' "${container_id}" 2>/dev/null || printf 'N/A'
}

compose_instance_count() {
  local service="$1"
  docker compose ps -q "${service}" 2>/dev/null | grep -c . | tr -d ' '
}

compose_exec_scalar() {
  local service="$1"
  shift

  docker compose exec -T "${service}" "$@" 2>/dev/null \
    | tr -d '\r' \
    | awk 'NF { value=$0 } END { if (value != "") print value }'
}

db_scalar() {
  local sql="$1"
  local value

  value="$(
    docker compose exec -T "${POSTGRES_SERVICE}" \
      psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
      -Atqc "${sql}" 2>/dev/null \
      | tr -d '\r' \
      | awk 'NF { value=$0 } END { if (value != "") print value }'
  )" || true

  if [[ -z "${value}" ]]; then
    printf 'N/A'
  else
    printf '%s' "${value}"
  fi
}

first_existing_table_count() {
  local table
  local value

  for table in "$@"; do
    value="$(db_scalar "select count(*) from ${table};")"
    if is_number "${value}"; then
      printf '%s' "${value}"
      return
    fi
  done

  printf 'N/A'
}

prom_scrape_sum() {
  local file="$1"
  local metric="$2"

  if [[ ! -s "${file}" ]]; then
    printf 'N/A'
    return
  fi

  awk -v metric="${metric}" '
    $0 !~ /^#/ && ($1 == metric || index($1, metric "{") == 1) {
      sum += $2
      found = 1
    }
    END {
      if (found) printf "%.6f", sum
      else printf "N/A"
    }
  ' "${file}"
}

metric_delta_any() {
  local metric
  local before
  local after
  local delta

  for metric in "$@"; do
    before="$(prom_scrape_sum "${PROM_BEFORE_FILE}" "${metric}")"
    after="$(prom_scrape_sum "${PROM_AFTER_FILE}" "${metric}")"
    delta="$(calculate_delta "${before}" "${after}")"

    if is_number "${delta}"; then
      printf '%s' "${delta}"
      return
    fi
  done

  printf 'N/A'
}

current_metric_any() {
  local file="$1"
  shift
  local metric
  local value

  for metric in "$@"; do
    value="$(prom_scrape_sum "${file}" "${metric}")"
    if is_number "${value}"; then
      printf '%s' "${value}"
      return
    fi
  done

  printf 'N/A'
}

prometheus_query() {
  local query="$1"
  local response
  local value

  response="$(
    curl -fsS --get \
      --data-urlencode "query=${query}" \
      "${PROMETHEUS_URL}/api/v1/query" 2>/dev/null
  )" || true

  if [[ -z "${response}" ]]; then
    printf 'N/A'
    return
  fi

  value="$(
    printf '%s' "${response}" \
      | jq -r '
          if .status == "success" and (.data.result | length) > 0 then
            .data.result[0].value[1]
          else
            "N/A"
          end
        ' 2>/dev/null
  )" || true

  if is_number "${value}"; then
    printf '%s' "${value}"
  else
    printf 'N/A'
  fi
}

first_prometheus_query() {
  local query
  local value

  for query in "$@"; do
    value="$(prometheus_query "${query}")"
    if is_number "${value}"; then
      printf '%s' "${value}"
      return
    fi
  done

  printf 'N/A'
}

k6_exact_value() {
  local metric="$1"
  local field="$2"

  if [[ ! -s "${SUMMARY_FILE}" ]]; then
    printf 'N/A'
    return
  fi

  # k6 summary-export formats differ by version:
  # - some use .metrics.<metric>.values.<field>
  # - k6 0.57 uses .metrics.<metric>.<field>
  jq -r --arg metric "${metric}" --arg field "${field}" '
    .metrics[$metric].values[$field]
      // .metrics[$metric][$field]
      // "N/A"
  ' "${SUMMARY_FILE}" 2>/dev/null || printf 'N/A'
}

k6_regex_value() {
  local regex="$1"
  local field="$2"

  if [[ ! -s "${SUMMARY_FILE}" ]]; then
    printf 'N/A'
    return
  fi

  jq -r --arg regex "${regex}" --arg field "${field}" '
    [
      .metrics
      | to_entries[]
      | select(.key | test($regex; "i"))
      | (.value.values[$field] // .value[$field])
      | select(. != null)
    ][0] // "N/A"
  ' "${SUMMARY_FILE}" 2>/dev/null || printf 'N/A'
}

k6_first_value() {
  local field="$1"
  shift
  local regex
  local value

  for regex in "$@"; do
    value="$(k6_regex_value "${regex}" "${field}")"
    if is_number "${value}"; then
      printf '%s' "${value}"
      return
    fi
  done

  printf 'N/A'
}

comparison_text() {
  local left="${1:-N/A}"
  local right="${2:-N/A}"
  local equal_text="$3"
  local mismatch_text="$4"

  if ! is_number "${left}" || ! is_number "${right}"; then
    printf '%s Measurement unavailable for one or both sides.' "${mismatch_text}"
  elif awk -v left="${left}" -v right="${right}" \
    'BEGIN { exit !(int(left + 0.5) == int(right + 0.5)) }'; then
    printf '%s' "${equal_text}"
  else
    printf '%s' "${mismatch_text}"
  fi
}

find_config_number() {
  local key_regex="$1"
  local value

  value="$(
    find . -type f \
      \( -path '*/src/main/resources/application*.yml' \
         -o -path '*/src/main/resources/application*.yaml' \
         -o -path '*/src/main/resources/application*.properties' \) \
      -not -path '*/target/*' \
      -print0 2>/dev/null \
      | xargs -0 grep -Ehi "${key_regex}" 2>/dev/null \
      | grep -Eo '[0-9]+' \
      | head -1
  )" || true

  if [[ -z "${value}" ]]; then
    printf 'N/A'
  else
    printf '%s' "${value}"
  fi
}

find_config_text() {
  local key_regex="$1"
  local value

  value="$(
    find . -type f \
      \( -path '*/src/main/resources/application*.yml' \
         -o -path '*/src/main/resources/application*.yaml' \
         -o -path '*/src/main/resources/application*.properties' \) \
      -not -path '*/target/*' \
      -print0 2>/dev/null \
      | xargs -0 grep -Ehi "${key_regex}" 2>/dev/null \
      | head -1 \
      | sed -E 's/^[^:=]+[:=][[:space:]]*//'
  )" || true

  if [[ -z "${value}" ]]; then
    printf 'N/A'
  else
    printf '%s' "${value}"
  fi
}

# -----------------------------------------------------------------------------
# Preconditions
# -----------------------------------------------------------------------------

require_command docker
require_command curl
require_command jq
require_command awk
require_command git

docker info >/dev/null 2>&1 || fail "Docker is not running."
[[ -f "${PROJECT_ROOT}/${K6_SCRIPT_HOST}" ]] \
  || fail "k6 script not found: ${K6_SCRIPT_HOST}"

mkdir -p "${RESULTS_PATH}" "$(dirname "${REPORT_PATH}")"

if [[ -f "${REPORT_PATH}" ]]; then
  cp "${REPORT_PATH}" "${RESULTS_PATH}/performance-results-before-${RUN_ID}.md"
fi

export PARCELFLOW_LOAD_TESTING_ENABLED

# -----------------------------------------------------------------------------
# Start clean environment
# -----------------------------------------------------------------------------

if is_true "${START_STACK}"; then
  if is_true "${RESET_STACK}"; then
    warn "RESET_STACK=true: Docker Compose volumes will be deleted."
    log "Removing existing Compose containers and volumes"
    docker compose down -v
  fi

  log "Starting ParcelFlow with load-testing support enabled"
  docker compose up -d --build
  docker compose ps
fi

READINESS_URL="${HOST_BASE_URL}/actuator/health/readiness"
GENERAL_HEALTH_URL="${HOST_BASE_URL}/actuator/health"

log "Waiting for application readiness"
WAITED=0
HEALTH_URL="${READINESS_URL}"

until curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; do
  if [[ "${HEALTH_URL}" == "${READINESS_URL}" ]] \
     && curl -fsS "${GENERAL_HEALTH_URL}" >/dev/null 2>&1; then
    HEALTH_URL="${GENERAL_HEALTH_URL}"
    break
  fi

  if (( WAITED >= HEALTH_TIMEOUT_SECONDS )); then
    docker compose ps || true
    docker compose logs --tail=150 "${TRACKING_SERVICE}" || true
    fail "Application did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s."
  fi

  sleep 2
  WAITED=$((WAITED + 2))
done

log "Application is ready: ${HEALTH_URL}"

ACTUATOR_PROMETHEUS_URL="${HOST_BASE_URL}/actuator/prometheus"

if curl -fsS "${ACTUATOR_PROMETHEUS_URL}" > "${PROM_BEFORE_FILE}" 2>/dev/null; then
  log "Captured application metrics before the test"
else
  : > "${PROM_BEFORE_FILE}"
  warn "Actuator Prometheus endpoint is unavailable. Some server metrics will be N/A."
fi

# -----------------------------------------------------------------------------
# Run k6
# -----------------------------------------------------------------------------

TEST_STARTED_EPOCH="$(date +%s)"
K6_MODE="docker"
K6_EFFECTIVE_BASE_URL="${K6_DOCKER_BASE_URL}"

log "Running the main k6 test"

set +e

if compose_service_exists "${K6_SERVICE}"; then
  K6_MODE="compose"
  K6_EFFECTIVE_BASE_URL="${K6_COMPOSE_BASE_URL}"

  docker compose --profile "${K6_PROFILE}" run --rm \
    -e BASE_URL="${K6_EFFECTIVE_BASE_URL}" \
    -e SHIPMENT_COUNT="${SHIPMENT_COUNT}" \
    -e VIRTUAL_USERS="${VIRTUAL_USERS}" \
    -e TEST_DURATION="${TEST_DURATION}" \
    -e EVENT_RATE="${EVENT_RATE}" \
    -e EVENTS_PER_REQUEST="${EVENTS_PER_REQUEST}" \
    -e DUPLICATE_PERCENTAGE="${DUPLICATE_PERCENTAGE}" \
    -e OUT_OF_ORDER_PERCENTAGE="${OUT_OF_ORDER_PERCENTAGE}" \
    -v "${PROJECT_ROOT}/performance/k6:/scripts:ro" \
    -v "${RESULTS_PATH}:/results" \
    "${K6_SERVICE}" \
    run \
    --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
    --summary-export="/results/summary-${RUN_ID}.json" \
    "${K6_SCRIPT_CONTAINER}" \
    2>&1 | tee "${OUTPUT_FILE}"

  K6_EXIT_CODE=${PIPESTATUS[0]}
else
  warn "Compose service '${K6_SERVICE}' was not found. Falling back to ${K6_IMAGE}."

  docker run --rm -i \
    -e K6_NO_COLOR=true \
    -e BASE_URL="${K6_EFFECTIVE_BASE_URL}" \
    -e SHIPMENT_COUNT="${SHIPMENT_COUNT}" \
    -e VIRTUAL_USERS="${VIRTUAL_USERS}" \
    -e TEST_DURATION="${TEST_DURATION}" \
    -e EVENT_RATE="${EVENT_RATE}" \
    -e EVENTS_PER_REQUEST="${EVENTS_PER_REQUEST}" \
    -e DUPLICATE_PERCENTAGE="${DUPLICATE_PERCENTAGE}" \
    -e OUT_OF_ORDER_PERCENTAGE="${OUT_OF_ORDER_PERCENTAGE}" \
    -v "${PROJECT_ROOT}/performance/k6:/scripts:ro" \
    -v "${RESULTS_PATH}:/results" \
    "${K6_IMAGE}" \
    run \
    --summary-trend-stats="avg,min,med,max,p(90),p(95),p(99)" \
    --summary-export="/results/summary-${RUN_ID}.json" \
    "${K6_SCRIPT_CONTAINER}" \
    2>&1 | tee "${OUTPUT_FILE}"

  K6_EXIT_CODE=${PIPESTATUS[0]}
fi

set -e
TEST_LOAD_FINISHED_EPOCH="$(date +%s)"

[[ -s "${SUMMARY_FILE}" ]] \
  || warn "k6 summary JSON was not created at ${SUMMARY_FILE}."

# -----------------------------------------------------------------------------
# Wait for the consumer to drain
# -----------------------------------------------------------------------------

log "Waiting for event processing to drain"
DRAIN_STARTED_EPOCH="$(date +%s)"
DRAIN_SECONDS="N/A"
WAITED=0

while (( WAITED <= DRAIN_TIMEOUT_SECONDS )); do
  CURRENT_PROM_FILE="${RESULTS_PATH}/prometheus-current-${RUN_ID}.txt"

  if ! curl -fsS "${ACTUATOR_PROMETHEUS_URL}" > "${CURRENT_PROM_FILE}" 2>/dev/null; then
    break
  fi

  RECEIVED_NOW="$(current_metric_any "${CURRENT_PROM_FILE}" \
    parcelflow_tracking_events_received_total)"

  PROCESSED_NOW="$(current_metric_any "${CURRENT_PROM_FILE}" \
    parcelflow_tracking_events_processed_total)"

  RECEIVED_BEFORE="$(current_metric_any "${PROM_BEFORE_FILE}" \
    parcelflow_tracking_events_received_total)"

  PROCESSED_BEFORE="$(current_metric_any "${PROM_BEFORE_FILE}" \
    parcelflow_tracking_events_processed_total)"

  RECEIVED_DELTA_NOW="$(calculate_delta "${RECEIVED_BEFORE}" "${RECEIVED_NOW}")"
  PROCESSED_DELTA_NOW="$(calculate_delta "${PROCESSED_BEFORE}" "${PROCESSED_NOW}")"

  if is_number "${RECEIVED_DELTA_NOW}" \
     && is_number "${PROCESSED_DELTA_NOW}" \
     && awk -v received="${RECEIVED_DELTA_NOW}" -v processed="${PROCESSED_DELTA_NOW}" \
        'BEGIN { exit !(processed >= received) }'; then
    DRAIN_SECONDS="$(( $(date +%s) - DRAIN_STARTED_EPOCH ))"
    rm -f "${CURRENT_PROM_FILE}"
    break
  fi

  rm -f "${CURRENT_PROM_FILE}"
  sleep 1
  WAITED=$((WAITED + 1))
done

if [[ "${DRAIN_SECONDS}" == "N/A" ]]; then
  warn "Drain completion could not be confirmed from metrics."
fi

curl -fsS "${ACTUATOR_PROMETHEUS_URL}" > "${PROM_AFTER_FILE}" 2>/dev/null || : > "${PROM_AFTER_FILE}"

TEST_COMPLETED_EPOCH="$(date +%s)"
WALL_SECONDS="$((TEST_COMPLETED_EPOCH - TEST_STARTED_EPOCH))"

# -----------------------------------------------------------------------------
# Client-side metrics
# -----------------------------------------------------------------------------

HTTP_REQUESTS_RAW="$(k6_exact_value http_reqs count)"
REQUEST_RATE_RAW="$(k6_exact_value http_reqs rate)"
HTTP_ERROR_RATE_RAW="$(k6_exact_value http_req_failed rate)"
CHECK_RATE_RAW="$(k6_exact_value checks rate)"

# k6 0.57 summary-export stores the aggregate check ratio as "value".
if ! is_number "${CHECK_RATE_RAW}"; then
  CHECK_RATE_RAW="$(k6_exact_value checks value)"
fi

if ! is_number "${CHECK_RATE_RAW}"; then
  CHECK_PASSES="$(k6_exact_value checks passes)"
  CHECK_FAILS="$(k6_exact_value checks fails)"
  if is_number "${CHECK_PASSES}" && is_number "${CHECK_FAILS}"; then
    CHECK_RATE_RAW="$(safe_divide "${CHECK_PASSES}" "$(awk -v p="${CHECK_PASSES}" -v f="${CHECK_FAILS}" 'BEGIN {print p+f}')")"
  fi
fi

HTTP_P50_RAW="$(k6_exact_value http_req_duration med)"
if ! is_number "${HTTP_P50_RAW}"; then
  HTTP_P50_RAW="$(k6_exact_value http_req_duration 'p(50)')"
fi
HTTP_P95_RAW="$(k6_exact_value http_req_duration 'p(95)')"
HTTP_P99_RAW="$(k6_exact_value http_req_duration 'p(99)')"

STATUS_P95_RAW="$(k6_first_value 'p(95)' \
  'http_req_duration.*(shipment[_ -]?status|status[_ -]?read|shipment[_ -]?details)' \
  'http_req_duration.*GET.*/api/shipments/[^/}]+[}"]?$')"

HISTORY_P95_RAW="$(k6_first_value 'p(95)' \
  'http_req_duration.*(shipment[_ -]?history|tracking[_ -]?history|shipment[_ -]?events)' \
  'http_req_duration.*GET.*/api/shipments/.*/events')"

PUBLISH_P95_RAW="$(k6_first_value 'p(95)' \
  'http_req_duration.*(event[_ -]?publish|publish[_ -]?events|batch[_ -]?publish)' \
  'http_req_duration.*POST.*(events|publish|load-test)')"

EVENTS_PUBLISHED_RAW="$(k6_first_value count \
  '^events_published$' \
  'published_events' \
  'tracking_events_published' \
  'carrier_events_published')"

PUBLISH_RATE_RAW="$(k6_first_value rate \
  '^events_published$' \
  'published_events' \
  'tracking_events_published' \
  'carrier_events_published')"

DUPLICATES_PUBLISHED_RAW="$(k6_first_value count \
  'duplicate.*published' \
  'published.*duplicate' \
  '^duplicate_events$')"

OUT_OF_ORDER_PUBLISHED_RAW="$(k6_first_value count \
  'out[_ -]?of[_ -]?order.*published' \
  'published.*out[_ -]?of[_ -]?order' \
  '^out_of_order_events$')"

THRESHOLD_RESULT="$(
  if [[ -s "${SUMMARY_FILE}" ]]; then
    jq -r '
      [
        .metrics
        | to_entries[]
        | (.value.thresholds // {})
        | to_entries[]
        | (
            if (.value | type) == "object" and .value.ok != null then
              # Newer structure: ok=true means passed.
              (.value.ok | not)
            else
              # k6 0.57 summary-export: true means threshold was crossed/failed.
              .value
            end
          )
      ]
      | if length == 0 then "N/A"
        elif any then "FAIL"
        else "PASS"
        end
    ' "${SUMMARY_FILE}" 2>/dev/null || printf 'N/A'
  else
    printf 'N/A'
  fi
)"

HTTP_REQUESTS="$(format_integer "${HTTP_REQUESTS_RAW}")"
REQUEST_RATE="$(format_number "${REQUEST_RATE_RAW}" 1)"
HTTP_ERROR_RATE="$(format_percent_rate "${HTTP_ERROR_RATE_RAW}")"
CHECK_RATE="$(format_percent_rate "${CHECK_RATE_RAW}")"
HTTP_P50="$(format_number "${HTTP_P50_RAW}" 1)"
HTTP_P95="$(format_number "${HTTP_P95_RAW}" 1)"
HTTP_P99="$(format_number "${HTTP_P99_RAW}" 1)"
STATUS_P95="$(format_number "${STATUS_P95_RAW}" 1)"
HISTORY_P95="$(format_number "${HISTORY_P95_RAW}" 1)"
PUBLISH_P95="$(format_number "${PUBLISH_P95_RAW}" 1)"
EVENTS_PUBLISHED="$(format_integer "${EVENTS_PUBLISHED_RAW}")"
PUBLISH_RATE="$(format_number "${PUBLISH_RATE_RAW}" 1)"
DUPLICATES_PUBLISHED="$(format_integer "${DUPLICATES_PUBLISHED_RAW}")"
OUT_OF_ORDER_PUBLISHED="$(format_integer "${OUT_OF_ORDER_PUBLISHED_RAW}")"

# -----------------------------------------------------------------------------
# Server-side metrics
# -----------------------------------------------------------------------------

EVENTS_RECEIVED_RAW="$(metric_delta_any \
  parcelflow_tracking_events_received_total)"

EVENTS_PROCESSED_RAW="$(metric_delta_any \
  parcelflow_tracking_events_processed_total)"

EVENTS_APPLIED_RAW="$(metric_delta_any \
  parcelflow_tracking_events_applied_total \
  parcelflow_tracking_events_current_state_applied_total)"

DUPLICATES_DETECTED_RAW="$(metric_delta_any \
  parcelflow_tracking_events_duplicate_total \
  parcelflow_tracking_events_duplicates_total)"

OUT_OF_ORDER_DETECTED_RAW="$(metric_delta_any \
  parcelflow_tracking_events_out_of_order_total)"

EVENTS_FAILED_RAW="$(metric_delta_any \
  parcelflow_tracking_events_failed_total)"

EVENTS_DLT_RAW="$(metric_delta_any \
  parcelflow_tracking_events_dead_lettered_total \
  parcelflow_tracking_events_dlt_total)"

EVENTS_RECEIVED="$(format_integer "${EVENTS_RECEIVED_RAW}")"
EVENTS_PROCESSED="$(format_integer "${EVENTS_PROCESSED_RAW}")"
EVENTS_APPLIED="$(format_integer "${EVENTS_APPLIED_RAW}")"
DUPLICATES_DETECTED="$(format_integer "${DUPLICATES_DETECTED_RAW}")"
OUT_OF_ORDER_DETECTED="$(format_integer "${OUT_OF_ORDER_DETECTED_RAW}")"
EVENTS_FAILED="$(format_integer "${EVENTS_FAILED_RAW}")"
EVENTS_DLT="$(format_integer "${EVENTS_DLT_RAW}")"

PROCESSING_RATE_RAW="$(safe_divide "${EVENTS_PROCESSED_RAW}" "${WALL_SECONDS}")"
PROCESSING_RATE="$(format_number "${PROCESSING_RATE_RAW}" 1)"

PROCESSING_P50_SECONDS="$(first_prometheus_query \
  "histogram_quantile(0.50, sum by (le) (rate(parcelflow_tracking_event_processing_duration_seconds_bucket[${PROMETHEUS_LOOKBACK}])))")"

PROCESSING_P95_SECONDS="$(first_prometheus_query \
  "histogram_quantile(0.95, sum by (le) (rate(parcelflow_tracking_event_processing_duration_seconds_bucket[${PROMETHEUS_LOOKBACK}])))")"

PROCESSING_P99_SECONDS="$(first_prometheus_query \
  "histogram_quantile(0.99, sum by (le) (rate(parcelflow_tracking_event_processing_duration_seconds_bucket[${PROMETHEUS_LOOKBACK}])))")"

PROCESSING_P50_RAW="$(safe_divide "${PROCESSING_P50_SECONDS}" 1 1000)"
PROCESSING_P95_RAW="$(safe_divide "${PROCESSING_P95_SECONDS}" 1 1000)"
PROCESSING_P99_RAW="$(safe_divide "${PROCESSING_P99_SECONDS}" 1 1000)"

PROCESSING_P50="$(format_number "${PROCESSING_P50_RAW}" 1)"
PROCESSING_P95="$(format_number "${PROCESSING_P95_RAW}" 1)"
PROCESSING_P99="$(format_number "${PROCESSING_P99_RAW}" 1)"

MAX_CONSUMER_LAG_RAW="$(first_prometheus_query \
  "max(max_over_time(parcelflow_kafka_consumer_lag[${PROMETHEUS_LOOKBACK}:5s]))" \
  "max(max_over_time(kafka_consumer_fetch_manager_records_lag_max[${PROMETHEUS_LOOKBACK}:5s]))" \
  "max(max_over_time(kafka_consumer_records_lag_max[${PROMETHEUS_LOOKBACK}:5s]))")"
MAX_CONSUMER_LAG="$(format_integer "${MAX_CONSUMER_LAG_RAW}")"

PEAK_HEAP_BYTES="$(first_prometheus_query \
  "max_over_time(sum(jvm_memory_used_bytes{area=\"heap\"})[${PROMETHEUS_LOOKBACK}:5s])")"
PEAK_HEAP_MB_RAW="$(safe_divide "${PEAK_HEAP_BYTES}" 1048576)"
PEAK_HEAP_MB="$(format_number "${PEAK_HEAP_MB_RAW}" 0)"

MAX_HEAP_BYTES="$(first_prometheus_query \
  "max(jvm_memory_max_bytes{area=\"heap\"})")"
MAX_HEAP_GB_RAW="$(safe_divide "${MAX_HEAP_BYTES}" 1073741824)"
MAX_HEAP_GB="$(format_number "${MAX_HEAP_GB_RAW}" 1)"

PEAK_CPU_RATIO="$(first_prometheus_query \
  "max_over_time(process_cpu_usage[${PROMETHEUS_LOOKBACK}:5s])")"
PEAK_CPU_PERCENT_RAW="$(safe_divide "${PEAK_CPU_RATIO}" 1 100)"
PEAK_CPU_PERCENT="$(format_number "${PEAK_CPU_PERCENT_RAW}" 0)"

CACHE_HIT_RATIO_RAW="$(first_prometheus_query \
  "100 * sum(increase(cache_gets_total{result=\"hit\"}[${PROMETHEUS_LOOKBACK}])) / sum(increase(cache_gets_total[${PROMETHEUS_LOOKBACK}]))" \
  "100 * sum(increase(spring_cache_gets_total{result=\"hit\"}[${PROMETHEUS_LOOKBACK}])) / sum(increase(spring_cache_gets_total[${PROMETHEUS_LOOKBACK}]))")"
CACHE_HIT_RATIO="$(format_percent_value "${CACHE_HIT_RATIO_RAW}")"

# -----------------------------------------------------------------------------
# Dataset
# -----------------------------------------------------------------------------

SHIPMENTS_STORED="$(first_existing_table_count shipments shipment)"
TRACKING_EVENTS_STORED="$(first_existing_table_count tracking_events tracking_event)"
NOTIFICATIONS_STORED="$(first_existing_table_count notifications notification_records)"
FAILED_EVENTS_STORED="$(first_existing_table_count failed_events failed_event)"
DATABASE_SIZE="$(db_scalar "select pg_size_pretty(pg_database_size(current_database()));")"

# -----------------------------------------------------------------------------
# Environment
# -----------------------------------------------------------------------------

if [[ "$(uname -s)" == "Darwin" ]]; then
  CPU_MODEL="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"
  CPU_CORES="$(sysctl -n hw.ncpu 2>/dev/null || printf 'N/A')"
  MEMORY_BYTES="$(sysctl -n hw.memsize 2>/dev/null || printf 'N/A')"
  # The product version only. sw_vers -buildVersion identifies the exact patch
  # build of one machine and tells a reader of the report nothing about the
  # numbers.
  DETECTED_OS="macOS $(sw_vers -productVersion 2>/dev/null || printf 'N/A')"
else
  CPU_MODEL="$(awk -F: '/model name/ {gsub(/^[ \t]+/, "", $2); print $2; exit}' /proc/cpuinfo 2>/dev/null || uname -m)"
  CPU_CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf 'N/A')"
  MEMORY_BYTES="$(awk '/MemTotal/ {print $2 * 1024}' /proc/meminfo 2>/dev/null || printf 'N/A')"
  # Kernel name and architecture, without -r: the full kernel release string is
  # a build identifier of one host.
  DETECTED_OS="$(uname -s) ($(uname -m))"
fi

if is_number "${MEMORY_BYTES}"; then
  MEMORY_GB="$(awk -v bytes="${MEMORY_BYTES}" 'BEGIN { printf "%.0f GB", bytes / 1073741824 }')"
else
  MEMORY_GB="N/A"
fi

if [[ -z "${MACHINE_DESCRIPTION}" ]]; then
  MACHINE_DESCRIPTION="${CPU_MODEL}, ${CPU_CORES} cores, ${MEMORY_GB}"
fi

if [[ -z "${OS_DESCRIPTION}" ]]; then
  OS_DESCRIPTION="${DETECTED_OS}"
fi

DOCKER_VERSION="$(docker version --format '{{.Server.Version}}' 2>/dev/null || printf 'N/A')"
DOCKER_STORAGE_DRIVER="$(docker info --format '{{.Driver}}' 2>/dev/null || printf 'N/A')"
DOCKER_CPUS="$(docker info --format '{{.NCPU}}' 2>/dev/null || printf 'N/A')"
DOCKER_MEMORY_BYTES="$(docker info --format '{{.MemTotal}}' 2>/dev/null || printf 'N/A')"

if is_number "${DOCKER_MEMORY_BYTES}"; then
  DOCKER_MEMORY_GB="$(awk -v bytes="${DOCKER_MEMORY_BYTES}" 'BEGIN { printf "%.1f GB", bytes / 1073741824 }')"
else
  DOCKER_MEMORY_GB="N/A"
fi

if [[ -z "${DOCKER_RESOURCE_DESCRIPTION}" ]]; then
  DOCKER_RESOURCE_DESCRIPTION="${DOCKER_CPUS} CPUs, ${DOCKER_MEMORY_GB} memory available to the VM"
fi

JAVA_RUNTIME="$(
  docker compose exec -T "${TRACKING_SERVICE}" java -version 2>&1 \
    | head -1 \
    | tr -d '\r'
)" || true
[[ -n "${JAVA_RUNTIME}" ]] || JAVA_RUNTIME="N/A"

JAVA_OPTIONS="$(
  docker compose exec -T "${TRACKING_SERVICE}" sh -lc \
    'printf "%s %s" "${JAVA_TOOL_OPTIONS:-}" "${JDK_JAVA_OPTIONS:-}"' 2>/dev/null \
    | tr -d '\r' \
    | sed -E 's/^[[:space:]]+|[[:space:]]+$//g'
)" || true

if [[ -n "${JAVA_OPTIONS}" ]]; then
  RUNTIME_DESCRIPTION="${JAVA_RUNTIME} (${JAVA_OPTIONS})"
else
  RUNTIME_DESCRIPTION="${JAVA_RUNTIME}"
fi

if [[ -z "${APP_VERSION}" ]]; then
  if [[ -x ./mvnw ]]; then
    APP_VERSION="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version 2>/dev/null | tail -1 || true)"
  fi
  [[ -n "${APP_VERSION}" ]] || APP_VERSION="N/A"
fi

APP_INSTANCES="$(compose_instance_count "${TRACKING_SERVICE}")"
[[ -n "${APP_INSTANCES}" ]] || APP_INSTANCES="N/A"

if [[ -z "${CONSUMER_CONCURRENCY}" ]]; then
  CONSUMER_CONCURRENCY="$(find_config_number '(^|[.[:space:]-])concurrency[[:space:]]*[:=]')"
fi

if [[ -z "${HIKARI_POOL_SIZE}" ]]; then
  HIKARI_POOL_SIZE="$(find_config_number 'maximum[-.]pool[-.]size[[:space:]]*[:=]')"
fi

if [[ -z "${REDIS_TTL_DESCRIPTION}" ]]; then
  REDIS_TTL_DESCRIPTION="$(find_config_text '(time[-.]to[-.]live|redis.*ttl)[[:space:]]*[:=]')"
fi

POSTGRES_IMAGE="$(container_image "${POSTGRES_SERVICE}")"
BROKER_IMAGE="$(container_image "${BROKER_SERVICE}")"
REDIS_IMAGE="$(container_image "${REDIS_SERVICE}")"

TOPIC_DESCRIPTION="$(
  docker compose exec -T "${BROKER_SERVICE}" \
    rpk topic describe "${TRACKING_TOPIC}" -f json 2>/dev/null
)" || true

PARTITION_COUNT="$(
  printf '%s' "${TOPIC_DESCRIPTION}" \
    | jq -r '
        if type == "array" then
          (.[0].partitions // .[0].Partitions // []) | length
        elif type == "object" then
          (.partitions // .Partitions // []) | length
        else
          0
        end
      ' 2>/dev/null
)" || true

REPLICATION_FACTOR="$(
  printf '%s' "${TOPIC_DESCRIPTION}" \
    | jq -r '
        if type == "array" then
          ((.[0].partitions // .[0].Partitions // [])[0].replicas // []) | length
        elif type == "object" then
          (((.partitions // .Partitions // [])[0].replicas) // []) | length
        else
          0
        end
      ' 2>/dev/null
)" || true

[[ "${PARTITION_COUNT}" != "0" && -n "${PARTITION_COUNT}" ]] || PARTITION_COUNT="N/A"
[[ "${REPLICATION_FACTOR}" != "0" && -n "${REPLICATION_FACTOR}" ]] || REPLICATION_FACTOR="N/A"

K6_VERSION="$(
  if [[ "${K6_MODE}" == "compose" ]]; then
    docker compose --profile "${K6_PROFILE}" run --rm "${K6_SERVICE}" version 2>/dev/null | head -1
  else
    docker run --rm "${K6_IMAGE}" version 2>/dev/null | head -1
  fi
)" || true
[[ -n "${K6_VERSION}" ]] || K6_VERSION="${K6_IMAGE}"

TEST_DATE="$(date '+%Y-%m-%d')"
GIT_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || printf 'N/A')"
GIT_BRANCH="$(git branch --show-current 2>/dev/null || printf 'N/A')"

# -----------------------------------------------------------------------------
# Reconciliation and interpretation
# -----------------------------------------------------------------------------

PUBLISHED_RECEIVED_TEXT="$(comparison_text \
  "${EVENTS_PUBLISHED_RAW}" "${EVENTS_RECEIVED_RAW}" \
  "${EVENTS_PUBLISHED} published = ${EVENTS_RECEIVED} received. Nothing was dropped between k6 and the consumer." \
  "${EVENTS_PUBLISHED} published versus ${EVENTS_RECEIVED} received. Review the raw output, ingress endpoint and consumer metrics.")"

DUPLICATE_TEXT="$(comparison_text \
  "${DUPLICATES_PUBLISHED_RAW}" "${DUPLICATES_DETECTED_RAW}" \
  "${DUPLICATES_PUBLISHED} duplicates published = ${DUPLICATES_DETECTED} duplicates detected. Every measured redelivery took the idempotent path." \
  "${DUPLICATES_PUBLISHED} duplicates published versus ${DUPLICATES_DETECTED} duplicates detected. The idempotency path needs investigation.")"

OUT_OF_ORDER_TEXT="$(comparison_text \
  "${OUT_OF_ORDER_PUBLISHED_RAW}" "${OUT_OF_ORDER_DETECTED_RAW}" \
  "${OUT_OF_ORDER_PUBLISHED} out-of-order events published = ${OUT_OF_ORDER_DETECTED} recorded as out of order." \
  "${OUT_OF_ORDER_PUBLISHED} out-of-order events published versus ${OUT_OF_ORDER_DETECTED} detected. Review ordering classification.")"

EXPECTED_STORED_RAW="N/A"
if is_number "${EVENTS_PUBLISHED_RAW}" && is_number "${DUPLICATES_PUBLISHED_RAW}"; then
  EXPECTED_STORED_RAW="$(awk -v published="${EVENTS_PUBLISHED_RAW}" -v duplicates="${DUPLICATES_PUBLISHED_RAW}" \
    'BEGIN { print published - duplicates }')"
fi
EXPECTED_STORED="$(format_integer "${EXPECTED_STORED_RAW}")"

STORED_TEXT="$(comparison_text \
  "${EXPECTED_STORED_RAW}" "${TRACKING_EVENTS_STORED}" \
  "${EVENTS_PUBLISHED} published − ${DUPLICATES_PUBLISHED} duplicates = ${EXPECTED_STORED} rows in \`tracking_events\`, matching the database." \
  "Expected ${EXPECTED_STORED} stored tracking events from published minus duplicates; the database contains ${TRACKING_EVENTS_STORED}.")"

if is_number "${EVENTS_FAILED_RAW}" && is_number "${EVENTS_DLT_RAW}" \
   && awk -v failed="${EVENTS_FAILED_RAW}" -v dlt="${EVENTS_DLT_RAW}" \
      'BEGIN { exit !(failed == 0 && dlt == 0) }'; then
  FAILURE_TEXT="0 failed, 0 dead-lettered, and the failed-event table contains ${FAILED_EVENTS_STORED} rows."
else
  FAILURE_TEXT="${EVENTS_FAILED} failed and ${EVENTS_DLT} dead-lettered during the measured interval; the failed-event table contains ${FAILED_EVENTS_STORED} rows."
fi

TARGET_RATE_OBSERVATION="The configured target was ${EVENT_RATE} events/s."
if is_number "${PUBLISH_RATE_RAW}"; then
  TARGET_ACHIEVEMENT_RAW="$(safe_divide "${PUBLISH_RATE_RAW}" "${EVENT_RATE}" 100)"
  TARGET_ACHIEVEMENT="$(format_number "${TARGET_ACHIEVEMENT_RAW}" 1)"

  if is_number "${TARGET_ACHIEVEMENT_RAW}" \
     && awk -v ratio="${TARGET_ACHIEVEMENT_RAW}" 'BEGIN { exit !(ratio >= 95) }'; then
    TARGET_RATE_OBSERVATION="k6 delivered ${PUBLISH_RATE} events/s, or approximately ${TARGET_ACHIEVEMENT}% of the configured ${EVENT_RATE} events/s target."
  else
    TARGET_RATE_OBSERVATION="k6 delivered ${PUBLISH_RATE} events/s against the configured ${EVENT_RATE} events/s target. The difference should be investigated before treating the target as achieved."
  fi
fi

if [[ "${MAX_CONSUMER_LAG}" == "0" ]]; then
  TARGET_RATE_OBSERVATION="${TARGET_RATE_OBSERVATION} Maximum measured consumer lag was zero, so this run did not establish the system's throughput ceiling."
elif [[ "${MAX_CONSUMER_LAG}" != "N/A" ]]; then
  TARGET_RATE_OBSERVATION="${TARGET_RATE_OBSERVATION} Maximum measured consumer lag was ${MAX_CONSUMER_LAG}, indicating backlog during the run."
else
  TARGET_RATE_OBSERVATION="${TARGET_RATE_OBSERVATION} Consumer lag was unavailable, so the throughput ceiling cannot be inferred."
fi

LATENCY_OBSERVATION="Server-side processing quantiles were unavailable."
if [[ "${PROCESSING_P50}" != "N/A" ]]; then
  LATENCY_OBSERVATION="Measured event-processing latency was ${PROCESSING_P50} ms at p50, ${PROCESSING_P95} ms at p95, and ${PROCESSING_P99} ms at p99 in this local environment."
fi

TAIL_OBSERVATION="The all-endpoints latency distribution was unavailable."
if is_number "${HTTP_P50_RAW}" && is_number "${HTTP_P99_RAW}"; then
  TAIL_MULTIPLIER_RAW="$(safe_divide "${HTTP_P99_RAW}" "${HTTP_P50_RAW}")"
  TAIL_MULTIPLIER="$(format_number "${TAIL_MULTIPLIER_RAW}" 1)"
  TAIL_OBSERVATION="The all-endpoints p99 of ${HTTP_P99} ms was about ${TAIL_MULTIPLIER}× the p50 of ${HTTP_P50} ms. Review the first seconds of the run, JIT compilation, connection warm-up and outliers before attributing the gap to steady-state load."
fi

CACHE_OBSERVATION="Cache hit ratio was unavailable."
if [[ "${CACHE_HIT_RATIO}" != "N/A" ]]; then
  CACHE_OBSERVATION="The measured cache hit ratio was ${CACHE_HIT_RATIO}. This is a property of this read/write mix: frequent accepted events invalidate shipment cache entries."
fi

HEAP_OBSERVATION="Peak JVM heap was unavailable."
if [[ "${PEAK_HEAP_MB}" != "N/A" ]]; then
  HEAP_OBSERVATION="Peak JVM heap in use was ${PEAK_HEAP_MB} MB"
  if [[ "${MAX_HEAP_GB}" != "N/A" ]]; then
    HEAP_OBSERVATION="${HEAP_OBSERVATION} against a reported maximum of ${MAX_HEAP_GB} GB"
  fi
  HEAP_OBSERVATION="${HEAP_OBSERVATION}. A longer run is still required to assess retention or gradual growth."
fi

# -----------------------------------------------------------------------------
# Client summary artifact
# -----------------------------------------------------------------------------

cat > "${CLIENT_SUMMARY_FILE}" <<EOF
# ParcelFlow client-side performance summary

| Measurement | Value |
|---|---:|
| Requests | ${HTTP_REQUESTS} |
| Request rate | ${REQUEST_RATE}/s |
| HTTP error rate | ${HTTP_ERROR_RATE} |
| Checks passed | ${CHECK_RATE} |
| p50 latency, all endpoints | ${HTTP_P50} ms |
| p95 latency, all endpoints | ${HTTP_P95} ms |
| p99 latency, all endpoints | ${HTTP_P99} ms |
| p95 shipment status | ${STATUS_P95} ms |
| p95 shipment history | ${HISTORY_P95} ms |
| p95 event publish | ${PUBLISH_P95} ms |
| Events published | ${EVENTS_PUBLISHED} |
| Publish rate | ${PUBLISH_RATE} events/s |
| Duplicates published | ${DUPLICATES_PUBLISHED} |
| Out-of-order events published | ${OUT_OF_ORDER_PUBLISHED} |
| k6 thresholds | ${THRESHOLD_RESULT} |
| k6 exit code | ${K6_EXIT_CODE} |
EOF

cp "${CLIENT_SUMMARY_FILE}" "${LATEST_CLIENT_SUMMARY_FILE}"
if [[ -s "${SUMMARY_FILE}" ]]; then
  cp "${SUMMARY_FILE}" "${LATEST_SUMMARY_FILE}"
fi

# -----------------------------------------------------------------------------
# Full report in the requested format
# -----------------------------------------------------------------------------

cat > "${REPORT_PATH}" <<EOF
# Performance results

What ParcelFlow does under a mixed load of carrier events and customer reads, measured rather than
estimated.

> **Read this first.** These numbers come from one laptop running the whole system — application,
> database, broker, cache, Prometheus, Grafana and the load generator — inside Docker Desktop. They
> say something useful about whether the design has an obvious bottleneck at this scale. They say
> nothing about what the system would do on real hardware, with a network between the tiers, with a
> multi-broker cluster, or with a database holding more rows than fit in page cache. Nothing here is
> a production capacity claim.

---

## 1. Environment

| | |
|---|---|
| Machine | ${MACHINE_DESCRIPTION} |
| OS | ${OS_DESCRIPTION} |
| Docker | Docker Engine ${DOCKER_VERSION}, ${DOCKER_STORAGE_DRIVER} storage driver |
| Docker resources | ${DOCKER_RESOURCE_DESCRIPTION} |
| Runtime | ${RUNTIME_DESCRIPTION} |
| Application | ParcelFlow \`${APP_VERSION}\`, ${APP_INSTANCES} \`${TRACKING_SERVICE}\` instance(s), consumer \`concurrency: ${CONSUMER_CONCURRENCY}\` |
| PostgreSQL | \`${POSTGRES_IMAGE}\`, Hikari pool ${HIKARI_POOL_SIZE} |
| Broker | \`${BROKER_IMAGE}\`, ${PARTITION_COUNT} partitions, replication factor ${REPLICATION_FACTOR} |
| Cache | \`${REDIS_IMAGE}\`, TTL ${REDIS_TTL_DESCRIPTION} |
| Load generator | \`${K6_VERSION}\`, ${K6_MODE} mode on the same Docker host/network |

Everything shares the available Docker resources with the load generator. Contention between the
thing being measured and the thing measuring it is real here and would not exist in a proper test
setup, where the generator lives on a separate machine.

## 2. Test command

\`\`\`bash
export PARCELFLOW_LOAD_TESTING_ENABLED=true

docker compose down -v
docker compose up -d --build

# Wait for readiness before starting: a run that begins during Flyway migration measures startup.
curl -fsS ${READINESS_URL}

SHIPMENT_COUNT=${SHIPMENT_COUNT} \\
VIRTUAL_USERS=${VIRTUAL_USERS} \\
TEST_DURATION=${TEST_DURATION} \\
EVENT_RATE=${EVENT_RATE} \\
EVENTS_PER_REQUEST=${EVENTS_PER_REQUEST} \\
DUPLICATE_PERCENTAGE=${DUPLICATE_PERCENTAGE} \\
OUT_OF_ORDER_PERCENTAGE=${OUT_OF_ORDER_PERCENTAGE} \\
docker compose --profile ${K6_PROFILE} run --rm ${K6_SERVICE} run ${K6_SCRIPT_CONTAINER}
\`\`\`

\`export\`, not a one-off prefix: both Compose commands have to see the variable. Compose can
recreate a service whose resolved environment changed, so both startup and the test should run with
load testing enabled.

The measured run used:

| Parameter | Value |
|---|---|
| \`SHIPMENT_COUNT\` | ${SHIPMENT_COUNT} |
| \`VIRTUAL_USERS\` | ${VIRTUAL_USERS} |
| \`TEST_DURATION\` | ${TEST_DURATION} |
| \`EVENT_RATE\` | ${EVENT_RATE} events/s target |
| \`EVENTS_PER_REQUEST\` | ${EVENTS_PER_REQUEST} |
| \`DUPLICATE_PERCENTAGE\` | ${DUPLICATE_PERCENTAGE} |
| \`OUT_OF_ORDER_PERCENTAGE\` | ${OUT_OF_ORDER_PERCENTAGE} |

## 3. Dataset

Measured at the end of the run.

| | |
|---|---|
| Shipments | ${SHIPMENTS_STORED} |
| Tracking events stored | ${TRACKING_EVENTS_STORED} |
| Notifications | ${NOTIFICATIONS_STORED} |
| Failed events | ${FAILED_EVENTS_STORED} |
| Database size | ${DATABASE_SIZE} |

EOF

if is_true "${RESET_STACK}"; then
  cat >> "${REPORT_PATH}" <<'EOF'
The database was created empty by `docker compose down -v`, so the row counts above belong to this
run. The dataset may still be small enough to fit in PostgreSQL's page cache; read latency for a
database larger than memory would be a different measurement.
EOF
else
  cat >> "${REPORT_PATH}" <<'EOF'
`RESET_STACK=false` was used, so the row counts can include data from earlier runs. Reconciliation
against this run's published-event counters may therefore be incomplete.
EOF
fi

cat >> "${REPORT_PATH}" <<EOF

---

## 4. Results

**Date of test: ${TEST_DATE}.** Commit \`${GIT_COMMIT}\` on branch \`${GIT_BRANCH}\`. Duration
${TEST_DURATION}, plus setup and ${DRAIN_SECONDS}s measured drain time.

### Client-side (k6)

| Measurement | Value |
|---|---|
| Requests | ${HTTP_REQUESTS} |
| Request rate | ${REQUEST_RATE}/s |
| HTTP error rate | ${HTTP_ERROR_RATE} |
| Checks passed | ${CHECK_RATE} |
| p50 latency, all endpoints | ${HTTP_P50} ms |
| p95 latency, all endpoints | ${HTTP_P95} ms |
| p99 latency, all endpoints | ${HTTP_P99} ms |
| p95 \`GET /api/shipments/{id}\` | ${STATUS_P95} ms |
| p95 \`GET /api/shipments/{id}/events\` | ${HISTORY_P95} ms |
| p95 event publish | ${PUBLISH_P95} ms |
| Events published | ${EVENTS_PUBLISHED} |
| Publish rate | ${PUBLISH_RATE} events/s |
| Duplicates published | ${DUPLICATES_PUBLISHED} |
| Out-of-order events published | ${OUT_OF_ORDER_PUBLISHED} |

k6 threshold result: **${THRESHOLD_RESULT}**. Process exit code: \`${K6_EXIT_CODE}\`.

### Server-side (the service's own metrics, delta over the run)

| Measurement | Value |
|---|---|
| Events received | ${EVENTS_RECEIVED} |
| Events processed | ${EVENTS_PROCESSED} |
| Applied | ${EVENTS_APPLIED} |
| Duplicate | ${DUPLICATES_DETECTED} |
| Out of order | ${OUT_OF_ORDER_DETECTED} |
| Failed | ${EVENTS_FAILED} |
| Dead-lettered | ${EVENTS_DLT} |
| Event processing rate | ${PROCESSING_RATE} events/s over ${WALL_SECONDS}s wall clock |
| Event processing p50 | ${PROCESSING_P50} ms |
| Event processing p95 | ${PROCESSING_P95} ms |
| Event processing p99 | ${PROCESSING_P99} ms |
| Max Kafka consumer lag | ${MAX_CONSUMER_LAG} |
| Drain time after the run stopped | ${DRAIN_SECONDS}s |
| Peak JVM heap in use | ${PEAK_HEAP_MB} MB of ${MAX_HEAP_GB} GB maximum |
| Peak process CPU | ${PEAK_CPU_PERCENT}% |
| Cache hit ratio | ${CACHE_HIT_RATIO} |

A value of **N/A** means the expected k6 custom metric, Actuator metric, Prometheus series, database
table or configuration property was not available under the expected name. The script leaves it
unfilled rather than inventing a value.

### Reconciliation

The client, server and database measurements should agree:

* ${PUBLISHED_RECEIVED_TEXT}
* ${DUPLICATE_TEXT}
* ${OUT_OF_ORDER_TEXT}
* ${STORED_TEXT}
* ${FAILURE_TEXT}

A load test that reports only throughput and HTTP errors can look healthy even when events are
silently rejected or dead-lettered. Reconciliation checks whether that happened.

---

## 5. Observations

**Configured rate versus achieved rate.** ${TARGET_RATE_OBSERVATION}

**Event processing latency.** ${LATENCY_OBSERVATION}

**Tail latency.** ${TAIL_OBSERVATION}

**Cache behaviour.** ${CACHE_OBSERVATION}

**Heap behaviour.** ${HEAP_OBSERVATION}

## 6. Bottlenecks

The test may or may not have reached these limits. They are ordered by the current architecture and
the measurements available from this run:

1. **Consumer parallelism.** Consumer \`concurrency: ${CONSUMER_CONCURRENCY}\` is backed by
   ${PARTITION_COUNT} topic partitions. Threads beyond the partition count cannot add Kafka
   consumption parallelism.
2. **One write transaction per accepted event.** Every applied event normally requires history
   persistence and a shipment update. At higher rates PostgreSQL write throughput and fsync latency
   are likely to matter.
3. **Optimistic-lock contention on hot shipments.** A workload concentrated on a small number of
   shipments can retry whole transactions even if an evenly distributed workload does not.
4. **The Hikari pool.** The detected/configured pool size is ${HIKARI_POOL_SIZE}, shared by event
   consumers and HTTP request handling.
5. **Cache invalidation frequency.** A write-heavy workload invalidates current-shipment cache
   entries frequently. This cannot become a source-of-truth correctness problem, but it can make the
   cache provide little benefit.

## 7. Improvements worth trying

* **Find the actual ceiling.** Use a \`ramping-arrival-rate\` scenario that increases the rate until
  consumer lag grows consistently.
* **More partitions and another service instance.** This would test rebalancing and whether
  idempotency remains correct when ownership moves between consumers.
* **Batch consumption.** Measure whether batching lowers transaction overhead without weakening
  failure handling.
* **Move the load generator off the machine under test.** This removes competition for the same CPU,
  memory and Docker networking.
* **Use a dataset larger than page cache.** That makes history-query indexes and storage I/O part of
  the measurement.
* **Run for longer.** A ${TEST_DURATION} run is not evidence about connection leaks, gradual heap
  growth or index bloat.

---

## 8. Reproducing this

The runner creates these files:

* \`${LATEST_SUMMARY_FILE#${PROJECT_ROOT}/}\` — latest complete k6 JSON summary.
* \`${LATEST_CLIENT_SUMMARY_FILE#${PROJECT_ROOT}/}\` — latest client-side Markdown summary.
* \`${SUMMARY_FILE#${PROJECT_ROOT}/}\` — versioned JSON summary for this run.
* \`${OUTPUT_FILE#${PROJECT_ROOT}/}\` — complete k6 console output.
* \`${PROM_BEFORE_FILE#${PROJECT_ROOT}/}\` and
  \`${PROM_AFTER_FILE#${PROJECT_ROOT}/}\` — raw application metrics used for deltas.

To repeat the same test:

\`\`\`bash
./scripts/run-performance-test.sh
\`\`\`

To vary the shape of the load:

\`\`\`bash
SHIPMENT_COUNT=1000 VIRTUAL_USERS=50 TEST_DURATION=5m EVENT_RATE=500 \\
  EVENTS_PER_REQUEST=10 DUPLICATE_PERCENTAGE=25 OUT_OF_ORDER_PERCENTAGE=20 \\
  ./scripts/run-performance-test.sh
\`\`\`

Watch Grafana at <http://localhost:3000> and Prometheus at <${PROMETHEUS_URL}> while the test runs.
EOF

# -----------------------------------------------------------------------------
# Plain-text metric snapshot
# -----------------------------------------------------------------------------

{
  printf 'Run ID:                      %s\n' "${RUN_ID}"
  printf 'k6 exit code:                %s\n' "${K6_EXIT_CODE}"
  printf 'Threshold result:            %s\n' "${THRESHOLD_RESULT}"
  printf 'HTTP requests:               %s\n' "${HTTP_REQUESTS}"
  printf 'Request rate:                %s/s\n' "${REQUEST_RATE}"
  printf 'HTTP error rate:             %s\n' "${HTTP_ERROR_RATE}"
  printf 'Checks passed:               %s\n' "${CHECK_RATE}"
  printf 'HTTP p50:                    %s ms\n' "${HTTP_P50}"
  printf 'HTTP p95:                    %s ms\n' "${HTTP_P95}"
  printf 'HTTP p99:                    %s ms\n' "${HTTP_P99}"
  printf 'Events published:            %s\n' "${EVENTS_PUBLISHED}"
  printf 'Publish rate:                %s events/s\n' "${PUBLISH_RATE}"
  printf 'Events received:             %s\n' "${EVENTS_RECEIVED}"
  printf 'Events processed:            %s\n' "${EVENTS_PROCESSED}"
  printf 'Duplicate events:            %s\n' "${DUPLICATES_DETECTED}"
  printf 'Out-of-order events:         %s\n' "${OUT_OF_ORDER_DETECTED}"
  printf 'Failed events:               %s\n' "${EVENTS_FAILED}"
  printf 'Dead-lettered events:        %s\n' "${EVENTS_DLT}"
  printf 'Tracking events stored:      %s\n' "${TRACKING_EVENTS_STORED}"
  printf 'Notifications stored:        %s\n' "${NOTIFICATIONS_STORED}"
  printf 'Database size:               %s\n' "${DATABASE_SIZE}"
} > "${METRICS_FILE}"

log "Updated report: ${REPORT_FILE}"
log "Latest client summary: ${LATEST_CLIENT_SUMMARY_FILE#${PROJECT_ROOT}/}"
log "Latest k6 JSON summary: ${LATEST_SUMMARY_FILE#${PROJECT_ROOT}/}"
log "Raw output: ${OUTPUT_FILE#${PROJECT_ROOT}/}"
log "Overall k6 result: ${THRESHOLD_RESULT}; exit code ${K6_EXIT_CODE}"

if [[ "${K6_EXIT_CODE}" -ne 0 ]]; then
  exit "${K6_EXIT_CODE}"
fi
