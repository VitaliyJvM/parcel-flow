# ParcelFlow web UI

Angular 22 console for the ParcelFlow tracking service. Sign-in is OpenID Connect through the
tracking service (the BFF) and Keycloak; the browser holds only an HttpOnly session cookie and never
an OAuth token. See [../docs/security.md](../docs/security.md).

## Requirements

- Node.js **24.15+** (pinned in `.nvmrc`: `nvm use`); npm 11
- The backend running locally: `./scripts/init-local-secrets.sh && docker compose up -d --wait` from
  the repository root (Keycloak included)
- For `npm run e2e`: the local passwords exported, `set -a && source ../.env && set +a`

## Commands

| Command | What it does |
|---|---|
| `npm ci` | Install the pinned dependency tree |
| `npm start` | Dev server on http://localhost:4200, proxying `/api`, `/oauth2/authorization`, `/login/oauth2/code` and `/logout` to `127.0.0.1:8080` (`proxy.conf.json`). Open it as `localhost`, not 127.0.0.1: the OIDC redirect URIs are exact. |
| `npm run lint` | ESLint (angular-eslint, template accessibility rules) + typecheck of `e2e/` + icon-subset check |
| `npm test` | Vitest unit and component tests (jsdom), single run |
| `npm run build` | Production build to `dist/parcelflow-web/browser` |
| `npm run e2e:install` | Install Playwright's Chromium (once) |
| `npm run e2e` | Playwright against the real stack; starts `ng serve` unless `E2E_BASE_URL` is set |
| `npm run api:fetch` | Download `/v3/api-docs` from the running service into `openapi/` |
| `npm run api:generate` | Regenerate `src/app/core/api/generated/` from the committed snapshot |
| `npm run icons:check` | Fail if a template uses an icon missing from the self-hosted Material Symbols subset |
| `npm run icons:subset` | Rebuild `src/assets/fonts/material-symbols-outlined-subset.woff2` from `material-symbols.icons.txt` (needs Python `fonttools` + `brotli`; see the script header) |

Fonts are self-hosted, with no external requests. Roboto comes from `@fontsource/roboto`; the icons
use a Material Symbols subset generated from `@fontsource-variable/material-symbols-outlined`.
Both are under the OFL 1.1, and the licenses are served at `/licenses/fonts/`.

E2E through the production nginx image: `E2E_BASE_URL=http://localhost:8081 npm run e2e`.
The E2E suite signs in through the real Keycloak login form (`e2e/support/auth.ts`) and runs the
real carrier simulator via `docker compose run`, so it needs Docker and the local passwords.

## Layout

```
src/app/
  core/
    api/          typed HttpClient services, ApiError (RFC 9457), generated OpenAPI contracts
    auth/         AuthSession (Signals), guards, session interceptor (401 / CSRF retry), session-bound
                  CSRF token interceptor, user-activity marking of background polling, navigation
    http/         functional interceptors: correlation id, problem details, GET retry
    live/         ShipmentLiveTracker (polling) and its pure scheduling policy
    retailer/     retailer context: server-assigned for RETAILER, a picker for ADMIN
  shared/         status chips, date display, error panel, validators, enum metadata
  features/
    dashboard/    per-retailer status counts (bounded concurrency), recent shipments
    shipments/    list, tracking-number lookup, registration, detail (timeline, notifications, simulate tab)
    operations/   failed-event list, detail and manual retry
    simulator/    CLI command builder (nothing is executed from the browser)
    auth/         login, signed-out and forbidden pages
    about/        security model, limitations, 404
e2e/              Playwright: authentication and isolation, workflow against Compose + simulator
nginx/            runtime config for the Docker image
openapi/          committed OpenAPI snapshot the contracts are generated from
```
