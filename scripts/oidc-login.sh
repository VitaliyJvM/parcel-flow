#!/usr/bin/env bash
#
# Signs in to ParcelFlow through the real OIDC flow with curl, the way a browser does: the BFF's
# login endpoint, Keycloak's HTML login form, the callback. Leaves the session in a curl cookie jar.
#
#   scripts/oidc-login.sh <base-url> <username> <password-env-var> <cookie-jar>
#
#   PARCELFLOW_ADMIN_PASSWORD=... scripts/oidc-login.sh http://localhost:8081 ops.admin \
#       PARCELFLOW_ADMIN_PASSWORD /tmp/pf.cookies
#   curl -b /tmp/pf.cookies http://localhost:8081/api/auth/session
#
# The password is read from the named environment variable, never from the command line, and handed
# to curl on stdin (`--data-urlencode password@-`, fed by the shell's printf builtin), so it appears
# in neither shell history nor any process's argument list (`ps`). The cookie jar is created 0600.
# Intended for local smoke tests and the load-test runner; it is
# not an API and not a supported way to authenticate anything else.
#
# Exit status: 0 when the session is authenticated, non-zero (with the step that failed) otherwise.

set -euo pipefail

BASE_URL="${1:?base url, e.g. http://localhost:8081}"
USERNAME="${2:?username}"
PASSWORD_VAR="${3:?name of the environment variable holding the password}"
JAR="${4:?cookie jar path}"
PASSWORD="${!PASSWORD_VAR:?environment variable ${PASSWORD_VAR} is not set}"

fail() { echo "oidc-login: $*" >&2; exit 1; }

location() { # prints the Location header of a response read from stdin
  tr -d '\r' | awk 'tolower($1)=="location:" {print $2}' | tail -1
}

: > "${JAR}"
chmod 600 "${JAR}"

# 1. The BFF's login endpoint answers with a redirect to Keycloak (state, nonce, PKCE challenge).
authorize="$(curl -sS -o /dev/null -D - -c "${JAR}" -b "${JAR}" "${BASE_URL}/oauth2/authorization/keycloak" | location)"
case "${authorize}" in
  http*://*/protocol/openid-connect/auth\?*) ;;
  *) fail "expected a redirect to Keycloak, got '${authorize}'" ;;
esac

# 2. Keycloak's login page. The form action carries Keycloak's own session tab id.
page="$(curl -sS -c "${JAR}" -b "${JAR}" "${authorize}")"
action="$(printf '%s' "${page}" | tr '\n' ' ' | sed -n 's/.*id="kc-form-login"[^>]*action="\([^"]*\)".*/\1/p' | sed 's/&amp;/\&/g')"
if [ -z "${action}" ]; then
  action="$(printf '%s' "${page}" | tr '\n' ' ' | sed -n 's/.*action="\([^"]*login-actions\/authenticate[^"]*\)".*/\1/p' | sed 's/&amp;/\&/g')"
fi
[ -n "${action}" ] || fail "no Keycloak login form found"

# 3. Credentials to Keycloak (never to ParcelFlow). Keycloak redirects back to the BFF callback.
#    The password goes through stdin: printf is a shell builtin, so it never becomes an argument.
callback="$(printf '%s' "${PASSWORD}" | curl -sS -o /dev/null -D - -c "${JAR}" -b "${JAR}" \
  --data-urlencode "username=${USERNAME}" --data-urlencode "password@-" \
  --data-urlencode "credentialId=" "${action}" | location)"
case "${callback}" in
  */login/oauth2/code/keycloak\?*) ;;
  *) fail "Keycloak did not redirect to the callback (wrong credentials?), got '${callback}'" ;;
esac

# 4. The callback: the BFF exchanges the code server-side and establishes the session.
landing="$(curl -sS -o /dev/null -D - -c "${JAR}" -b "${JAR}" "${callback}" | location)"
case "${landing}" in
  */login\?error=*) fail "login refused by ParcelFlow: ${landing}" ;;
esac

# 5. Confirm. Writes also need the session's CSRF token: GET /api/auth/csrf with this jar.
session="$(curl -sS -c "${JAR}" -b "${JAR}" "${BASE_URL}/api/auth/session")"
case "${session}" in
  *'"authenticated":true'*) echo "${session}" ;;
  *) fail "session is not authenticated: ${session}" ;;
esac
