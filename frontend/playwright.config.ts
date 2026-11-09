import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the REAL stack: tracking service, Kafka (Redpanda), PostgreSQL, Redis,
 * and the carrier simulator run through Docker Compose. Nothing is mocked.
 *
 * Prerequisite: `docker compose up -d --wait` from the repository root, and the local passwords in
 * the environment (the tests sign in through the real Keycloak login form):
 *
 *   set -a && source ../.env && set +a
 *   npm run e2e                                    # starts `ng serve` (proxy -> 127.0.0.1:8080)
 *   E2E_BASE_URL=http://localhost:8081 npm run e2e # against the nginx container instead
 *
 * `localhost`, not 127.0.0.1: the OIDC redirect URIs registered in Keycloak are exact, and the
 * callback must come back to the origin that holds the session cookie.
 */
const externalBaseUrl = process.env['E2E_BASE_URL'];

export default defineConfig({
  testDir: './e2e',
  // The workflow publishes to one shared Kafka topic and polls; run serially for readable logs.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 15_000 },
  reporter: process.env['CI'] ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: externalBaseUrl ?? 'http://localhost:4200',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    timezoneId: 'America/Vancouver',
    locale: 'en-US',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: externalBaseUrl
    ? undefined
    : {
        command: 'npm run start -- --port 4200',
        url: 'http://localhost:4200',
        reuseExistingServer: !process.env['CI'],
        timeout: 120_000,
      },
});
