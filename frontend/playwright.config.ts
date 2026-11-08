import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the REAL stack: tracking service, Kafka (Redpanda), PostgreSQL, Redis,
 * and the carrier simulator run through Docker Compose. Nothing is mocked.
 *
 * Prerequisite: `docker compose up -d --wait` from the repository root.
 *
 *   npm run e2e                                    # starts `ng serve` (proxy -> 127.0.0.1:8080)
 *   E2E_BASE_URL=http://127.0.0.1:8081 npm run e2e # against the nginx container instead
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
    baseURL: externalBaseUrl ?? 'http://127.0.0.1:4200',
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
        url: 'http://127.0.0.1:4200',
        reuseExistingServer: !process.env['CI'],
        timeout: 120_000,
      },
});
