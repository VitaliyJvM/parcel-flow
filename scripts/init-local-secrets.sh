#!/usr/bin/env bash
#
# Generates the local-only Keycloak secrets that Docker Compose requires, into the git-ignored .env.
#
#   ./scripts/init-local-secrets.sh          # adds whatever is missing; never overwrites
#   ./scripts/init-local-secrets.sh --print  # also prints the local login passwords
#
# What it writes (only the keys that are not already set in .env):
#
#   PARCELFLOW_OIDC_CLIENT_SECRET   the confidential client's secret, shared by Keycloak and the
#                                   tracking service
#   PARCELFLOW_ADMIN_PASSWORD       password of the local ADMIN user  (ops.admin)
#   PARCELFLOW_RETAILER_PASSWORD    password of the local RETAILER users (retailer.alpha,
#                                   retailer.beta, retailer.unassigned)
#   KEYCLOAK_ADMIN_PASSWORD         the Keycloak admin console (master realm, user "admin")
#
# The values are random per machine and exist only in .env. Keycloak imports the realm once per
# container, so after changing a value recreate the container:  docker compose up -d --force-recreate keycloak
#
# Compatible with the Bash 3.2 shipped with macOS.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${ROOT}/.env"
PRINT=false
[ "${1:-}" = "--print" ] && PRINT=true

random_secret() {
  # 32 alphanumeric characters from the kernel CSPRNG. A bounded read first: `tr </dev/urandom |
  # head` would end in SIGPIPE, which `set -o pipefail` turns into a failure. 1024 bytes yield ~240
  # alphanumerics, far more than the 32 kept.
  head -c 1024 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-32
}

has_value() {
  [ -f "${ENV_FILE}" ] && grep -Eq "^$1=.+" "${ENV_FILE}"
}

if [ ! -f "${ENV_FILE}" ]; then
  cp "${ROOT}/.env.example" "${ENV_FILE}"
  chmod 600 "${ENV_FILE}"
  echo "Created .env from .env.example"
fi

added=0
for key in PARCELFLOW_OIDC_CLIENT_SECRET PARCELFLOW_ADMIN_PASSWORD PARCELFLOW_RETAILER_PASSWORD KEYCLOAK_ADMIN_PASSWORD; do
  if has_value "${key}"; then
    continue
  fi
  value="$(random_secret)"
  if grep -Eq "^${key}=" "${ENV_FILE}"; then
    # Present but empty (as copied from .env.example): fill it in place. A temp file rather than
    # `sed -i`, whose syntax differs between BSD and GNU sed.
    tmp="$(mktemp)"
    awk -v k="${key}" -v v="${value}" 'BEGIN{FS=OFS="="} $1==k && $2=="" {$0=k"="v} {print}' "${ENV_FILE}" >"${tmp}"
    cat "${tmp}" >"${ENV_FILE}"
    rm -f "${tmp}"
  else
    printf '%s=%s\n' "${key}" "${value}" >>"${ENV_FILE}"
  fi
  echo "Generated ${key}"
  added=$((added + 1))
done

if [ "${added}" -eq 0 ]; then
  echo "All local secrets are already set in .env; nothing changed."
else
  echo "Wrote ${added} value(s) to .env. If Keycloak is already running, recreate it:"
  echo "  docker compose up -d --force-recreate keycloak tracking-service"
fi

if [ "${PRINT}" = true ]; then
  echo
  echo "Local logins (http://localhost:8081):"
  echo "  ops.admin           ADMIN                       $(grep -E '^PARCELFLOW_ADMIN_PASSWORD=' "${ENV_FILE}" | cut -d= -f2-)"
  echo "  retailer.alpha      RETAILER  alpha-goods       $(grep -E '^PARCELFLOW_RETAILER_PASSWORD=' "${ENV_FILE}" | cut -d= -f2-)"
  echo "  retailer.beta       RETAILER  beta-supplies     (same retailer password)"
  echo "  retailer.unassigned RETAILER  (no retailer)     (same; login is refused by design)"
else
  echo "Run with --print to show the local login passwords."
fi
