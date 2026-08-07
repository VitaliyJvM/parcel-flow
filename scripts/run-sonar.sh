#!/usr/bin/env bash

set -Eeuo pipefail

# ParcelFlow SonarQube Cloud analysis runner, for local use.
#
# Run from the repository root:
#   cp .env.sonar.example .env.sonar     # then fill in the three values
#   ./scripts/run-sonar.sh
#
# What it does:
#   1. reads .env.sonar (git-ignored) without executing it;
#   2. checks SONAR_TOKEN, SONAR_PROJECT_KEY and SONAR_ORGANIZATION are all present;
#   3. runs `test jacocoTestReport` only if a module is missing its coverage XML;
#   4. runs `./gradlew sonar`, which reuses that XML rather than re-running the suite.
#
# Options:
#   FORCE_TESTS=true ./scripts/run-sonar.sh    re-run the suite even if reports exist
#   SKIP_TESTS=true  ./scripts/run-sonar.sh    never run the suite; fail if reports are missing
#
# Variables already exported in your shell win over the file, so a one-off override works:
#   SONAR_PROJECT_KEY=other-key ./scripts/run-sonar.sh
#
# The token is never printed. This script is compatible with the Bash 3.2 version shipped with
# macOS, so it uses no associative arrays, no `mapfile` and no `${var,,}`.

# -----------------------------------------------------------------------------
# Setup
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env.sonar}"
EXAMPLE_FILE="${REPO_ROOT}/.env.sonar.example"

FORCE_TESTS="${FORCE_TESTS:-false}"
SKIP_TESTS="${SKIP_TESTS:-false}"

# Declared up front so `set -u` does not trip over them before the file is read.
SONAR_TOKEN="${SONAR_TOKEN:-}"
SONAR_PROJECT_KEY="${SONAR_PROJECT_KEY:-}"
SONAR_ORGANIZATION="${SONAR_ORGANIZATION:-}"
SONAR_HOST_URL="${SONAR_HOST_URL:-}"

log()  { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

# -----------------------------------------------------------------------------
# 1. The configuration file has to exist
# -----------------------------------------------------------------------------

if [ ! -f "${ENV_FILE}" ]; then
    printf 'error: %s not found.\n\n' "${ENV_FILE}" >&2
    printf 'Create it from the template and fill in the three values:\n\n' >&2
    printf '    cp .env.sonar.example .env.sonar\n\n' >&2
    if [ -f "${EXAMPLE_FILE}" ]; then
        printf 'The template documents where each value comes from in SonarQube Cloud.\n' >&2
    fi
    exit 1
fi

# -----------------------------------------------------------------------------
# 2. Load it without executing it
#
# `source .env.sonar` would run whatever is in the file as shell code. This reads it line by line
# instead and assigns only the four keys below by name, so a stray backtick in a pasted token is
# data rather than a command.
# -----------------------------------------------------------------------------

read_env_file() {
    local line key value

    # `|| [ -n "$line" ]` so a final line with no trailing newline is still read.
    while IFS= read -r line || [ -n "${line}" ]; do
        line="${line%$'\r'}"                      # tolerate CRLF

        case "${line}" in
            ''|'#'*) continue ;;                  # blank and comment lines
            *'='*)   ;;                           # anything else must be KEY=VALUE
            *)       continue ;;
        esac

        line="${line# }"
        line="${line#export }"

        key="${line%%=*}"
        value="${line#*=}"

        # Trim whitespace around the key; a key cannot legitimately contain any.
        key="$(printf '%s' "${key}" | tr -d '[:space:]')"

        # Trim whitespace around the value, so `KEY = value` does not yield " value" and a token
        # with a trailing space pasted from a browser still authenticates. Done before the quotes
        # are stripped, so KEY=" padded " keeps the padding the quotes were asking for.
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"

        # Strip one layer of matching quotes, so TOKEN="abc" and TOKEN=abc behave the same.
        case "${value}" in
            \"*\") value="${value#\"}"; value="${value%\"}" ;;
            \'*\') value="${value#\'}"; value="${value%\'}" ;;
        esac

        # A whitelist, assigned by explicit name. No eval, and an unexpected key in the file
        # cannot introduce an environment variable into the Gradle run.
        case "${key}" in
            SONAR_TOKEN)        [ -n "${SONAR_TOKEN}" ]        || SONAR_TOKEN="${value}" ;;
            SONAR_PROJECT_KEY)  [ -n "${SONAR_PROJECT_KEY}" ]  || SONAR_PROJECT_KEY="${value}" ;;
            SONAR_ORGANIZATION) [ -n "${SONAR_ORGANIZATION}" ] || SONAR_ORGANIZATION="${value}" ;;
            SONAR_HOST_URL)     [ -n "${SONAR_HOST_URL}" ]     || SONAR_HOST_URL="${value}" ;;
            *) continue ;;
        esac
    done < "${ENV_FILE}"
}

read_env_file

# -----------------------------------------------------------------------------
# 3. All three required values have to be present
# -----------------------------------------------------------------------------

MISSING=""
[ -n "${SONAR_TOKEN}" ]        || MISSING="${MISSING} SONAR_TOKEN"
[ -n "${SONAR_PROJECT_KEY}" ]  || MISSING="${MISSING} SONAR_PROJECT_KEY"
[ -n "${SONAR_ORGANIZATION}" ] || MISSING="${MISSING} SONAR_ORGANIZATION"

if [ -n "${MISSING}" ]; then
    printf 'error: missing required value(s) in %s:%s\n\n' "${ENV_FILE}" "${MISSING}" >&2
    printf 'See .env.sonar.example for where each one comes from in SonarQube Cloud.\n' >&2
    exit 1
fi

export SONAR_TOKEN SONAR_PROJECT_KEY SONAR_ORGANIZATION
[ -n "${SONAR_HOST_URL}" ] && export SONAR_HOST_URL

# Deliberately not printed: SONAR_TOKEN.
log "Project key:  ${SONAR_PROJECT_KEY}"
log "Organization: ${SONAR_ORGANIZATION}"
log "Host:         ${SONAR_HOST_URL:-https://sonarcloud.io}"
log "Token:        set (value hidden)"
log ""

# -----------------------------------------------------------------------------
# 4. Coverage, but only if it is not already there
#
# The point of this whole change is that the test suite runs once. `sonar` reads the JaCoCo XML
# from disk and has no task dependency on `test`, so the suite is only started here when a module
# has no report yet.
# -----------------------------------------------------------------------------

# Module names come from settings.gradle so adding a module does not silently skip its coverage.
MODULES="$(sed -n "s/^ *include *['\"]:\{0,1\}\([^'\"]*\)['\"].*/\1/p" settings.gradle || true)"
[ -n "${MODULES}" ] || fail "could not read module names from settings.gradle"

REPORT_MISSING=false
for module in ${MODULES}; do
    if [ ! -f "${module}/build/reports/jacoco/test/jacocoTestReport.xml" ]; then
        log "No coverage report for ${module}."
        REPORT_MISSING=true
    fi
done

if [ "${FORCE_TESTS}" = "true" ]; then
    log "FORCE_TESTS=true — running the test suite."
    ./gradlew test jacocoTestReport
elif [ "${REPORT_MISSING}" = "true" ]; then
    if [ "${SKIP_TESTS}" = "true" ]; then
        fail "SKIP_TESTS=true but a JaCoCo report is missing. Run ./gradlew test jacocoTestReport."
    fi
    log "Running the test suite to produce coverage (this starts Testcontainers)."
    ./gradlew test jacocoTestReport
else
    log "Reusing the existing JaCoCo reports. FORCE_TESTS=true re-runs the suite."
fi

log ""

# -----------------------------------------------------------------------------
# 5. Analyse
# -----------------------------------------------------------------------------

log "Running SonarQube Cloud analysis."
./gradlew sonar

log ""
log "Done. Results: ${SONAR_HOST_URL:-https://sonarcloud.io}/dashboard?id=${SONAR_PROJECT_KEY}"
