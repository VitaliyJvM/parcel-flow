# ParcelFlow web UI

Angular 22 console for the ParcelFlow tracking service. **Local, unauthenticated demo** — see the
[implementation report](../docs/analysis/angular-frontend-implementation.md) for architecture,
security limits and test results.

## Requirements

- Node.js **24.15+** (pinned in `.nvmrc`: `nvm use`); npm 11
- The backend running locally: `docker compose up -d --wait` from the repository root

## Commands

| Command | What it does |
|---|---|
| `npm ci` | Install the pinned dependency tree |
| `npm start` | Dev server on http://127.0.0.1:4200, proxying `/api` to `127.0.0.1:8080` (`proxy.conf.json`) |
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

E2E through the production nginx image: `E2E_BASE_URL=http://127.0.0.1:8081 npm run e2e`.
The E2E suite runs the real carrier simulator via `docker compose run`, so it needs Docker.

## Layout

```
src/app/
  core/
    api/          typed HttpClient services, ApiError (RFC 9457), generated OpenAPI contracts
    http/         functional interceptors: correlation id, problem details, GET retry
    live/         ShipmentLiveTracker (polling) and its pure scheduling policy
    retailer/     retailer view-filter (signal + localStorage) and its dialog
  shared/         status chips, date display, error panel, validators, enum metadata
  features/
    dashboard/    per-retailer status counts (bounded concurrency), recent shipments
    shipments/    list, registration, detail (timeline, notifications, simulate tab)
    operations/   failed-event list, detail and manual retry
    simulator/    CLI command builder (nothing is executed from the browser)
    about/        demo limitations, 404
e2e/              Playwright workflow against Compose + simulator
nginx/            runtime config for the Docker image
openapi/          committed OpenAPI snapshot the contracts are generated from
```
