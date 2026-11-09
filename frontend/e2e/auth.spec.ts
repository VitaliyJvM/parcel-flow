import { expect, test } from '@playwright/test';
import {
  ADMIN,
  csrfHeaders,
  RETAILER_ALPHA,
  RETAILER_BETA,
  RETAILER_UNASSIGNED,
  signIn,
  signOut,
  submitKeycloakLogin,
} from './support/auth';

/**
 * Authentication and retailer isolation through the real browser, the real nginx/BFF and the real
 * Keycloak — two retailer accounts in separate browser contexts, plus the admin.
 */

const RUN = Date.now().toString(36);

test.describe('authentication', () => {
  test('an unauthenticated visit goes to /login, and signing in resumes the requested page', async ({
    page,
  }) => {
    await signIn(page, RETAILER_ALPHA, '/shipments?status=DELIVERED');
    await expect(page).toHaveURL(/\/shipments\?status=DELIVERED$/);
    await expect(page.getByTestId('assigned-retailer')).toContainText('alpha-goods');
  });

  test('the session survives a reload, and no token is ever readable by the page', async ({
    page,
  }) => {
    await signIn(page, RETAILER_ALPHA);
    await page.reload();
    await expect(page.getByTestId('user-menu')).toContainText('RETAILER');

    const storage = await page.evaluate(() => ({
      local: JSON.stringify({ ...localStorage }),
      session: JSON.stringify({ ...sessionStorage }),
      cookies: document.cookie,
    }));
    expect(storage.local + storage.session).not.toMatch(/eyJ|token|secret/i);
    // The session cookie is HttpOnly, and the CSRF token lives in the server-side session: the page
    // can read no cookie of ours at all.
    expect(storage.cookies).not.toMatch(/XSRF-TOKEN|PARCELFLOW_SESSION|JSESSIONID/);
    expect(page.url()).not.toMatch(/code=|token=|state=/);

    const session = await page.request.get('/api/auth/session');
    expect(await session.text()).not.toMatch(/eyJ|access_token|refresh_token|id_token/);
  });

  test('a RETAILER sees no admin navigation and is turned away from admin pages', async ({
    page,
  }) => {
    await signIn(page, RETAILER_ALPHA);
    const nav = page.locator('mat-nav-list');
    // Positive control first, so the absence checks below cannot pass vacuously.
    await expect(nav.getByRole('link', { name: 'Shipments', exact: true })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Failed events' })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: 'Carrier simulator' })).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: /select retailer|change retailer/i }),
    ).toHaveCount(0);

    await page.goto('/operations/failed-events');
    await expect(page).toHaveURL(/\/forbidden$/);
    // And the API agrees, whatever the UI shows.
    expect((await page.request.get('/api/admin/failed-events')).status()).toBe(403);
  });

  test('logout ends the session and the Keycloak SSO session: no automatic re-login', async ({
    page,
  }) => {
    await signIn(page, RETAILER_BETA);
    await signOut(page);
    await expect(page.getByRole('heading', { name: 'You have signed out' })).toBeVisible();

    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/login/);
    expect((await page.request.get('/api/retailers/beta-supplies/shipments')).status()).toBe(401);

    // Signing in again asks for credentials instead of silently reusing the old SSO session.
    await page.getByRole('button', { name: 'Sign in with Keycloak' }).click();
    await expect(page.locator('#kc-form-login')).toBeVisible();
  });

  test('an expired session sends the user to the login page with an explanation', async ({
    page,
  }) => {
    await signIn(page, RETAILER_ALPHA);
    // The server-side session is gone (expiry, logout elsewhere): the cookie no longer resolves.
    await page.context().clearCookies({ name: 'PARCELFLOW_SESSION' });
    // The next API call the open page makes is refused with 401. That may already be one of the
    // dashboard's own requests; otherwise navigating in-app triggers one.
    const shipments = page
      .locator('mat-nav-list')
      .getByRole('link', { name: 'Shipments', exact: true });
    if (await shipments.isVisible()) {
      await shipments.click({ timeout: 5_000 }).catch(() => undefined);
    }
    await expect(page).toHaveURL(/\/login\?.*reason=expired/);
    await expect(page.getByTestId('login-notice')).toContainText('Your session has ended');
    expect(new URL(page.url()).searchParams.get('returnUrl')).toMatch(/^\//);
  });

  test('a retailer account without a retailer assignment is refused, with a way to switch account', async ({
    page,
  }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Sign in with Keycloak' }).click();
    await submitKeycloakLogin(page, RETAILER_UNASSIGNED);
    await expect(page).toHaveURL(/\/login\?error=not_authorized$/);
    await expect(page.getByTestId('login-notice')).toContainText(
      'This account cannot use ParcelFlow',
    );

    // "Sign in" now forces Keycloak to prompt instead of silently reusing the refused account's
    // SSO session; Keycloak asks for re-authentication and offers to switch account.
    await page.getByRole('button', { name: 'Sign in with Keycloak' }).click();
    await expect(page.locator('#kc-form-login')).toBeVisible();
    await expect(page.locator('#reset-login')).toBeVisible();
    await submitKeycloakLogin(page, RETAILER_ALPHA);
    await expect(page.getByTestId('assigned-retailer')).toContainText('alpha-goods');
  });
});

test.describe('retailer isolation with two real retailer accounts', () => {
  test("a retailer cannot see, find, or learn about another retailer's shipment", async ({
    browser,
  }) => {
    const alphaContext = await browser.newContext();
    const betaContext = await browser.newContext();
    const alpha = await alphaContext.newPage();
    const beta = await betaContext.newPage();
    try {
      await signIn(alpha, RETAILER_ALPHA);
      await signIn(beta, RETAILER_BETA);
      const trackingNumber = `E2E-ISO-${RUN}`;

      // Alpha registers through the form; the retailer field is fixed to alpha's retailer.
      await alpha.goto('/shipments/new');
      await expect(alpha.getByLabel('Retailer ID')).toBeDisabled();
      await expect(alpha.getByLabel('Retailer ID')).toHaveValue('alpha-goods');
      await alpha.getByLabel('Customer reference').fill('cust-iso');
      await alpha.getByLabel('Tracking number').fill(trackingNumber);
      await alpha.getByRole('button', { name: 'Register shipment' }).click();
      await expect(alpha).toHaveURL(/\/shipments\/[0-9a-f-]{36}$/);
      const shipmentPath = new URL(alpha.url()).pathname;
      await expect(alpha.getByTestId('current-status')).toHaveAttribute(
        'data-status',
        'LABEL_CREATED',
      );
      // The simulator is an operator tool.
      await expect(alpha.getByRole('tab', { name: 'Simulate' })).toHaveCount(0);

      // Beta opens alpha's shipment URL: "not found", exactly like a shipment that does not exist.
      await beta.goto(shipmentPath);
      await expect(beta.getByText(/not found/i).first()).toBeVisible();
      await expect(beta.getByText('alpha-goods')).toHaveCount(0);
      await expect(beta.getByTestId('current-status')).toHaveCount(0);

      // Beta looks it up by tracking number: no such shipment.
      await beta.goto('/shipments');
      const lookup = beta.getByRole('search', { name: 'Find by tracking number' });
      await lookup.getByRole('combobox', { name: 'Carrier' }).click();
      await beta.getByRole('option', { name: 'SwiftPost', exact: true }).click();
      await lookup.getByLabel('Tracking number').fill(trackingNumber);
      await lookup.getByRole('button', { name: 'Find' }).click();
      await expect(beta.getByTestId('lookup-not-found')).toBeVisible();

      // Beta tries to register the same number: refused (it is globally unique), with nothing to open.
      await beta.goto('/shipments/new');
      await beta.getByLabel('Customer reference').fill('cust-beta');
      await beta.getByLabel('Tracking number').fill(trackingNumber);
      await beta.getByRole('button', { name: 'Register shipment' }).click();
      await expect(beta.getByTestId('duplicate-not-accessible')).toBeVisible();
      await expect(beta.getByRole('button', { name: 'Open existing shipment' })).toHaveCount(0);

      // Forged requests from beta's own session are refused by the backend.
      const headers = await csrfHeaders(beta);
      const forged = await beta.request.post('/api/shipments', {
        headers,
        data: {
          retailerId: 'alpha-goods',
          customerId: 'x',
          trackingNumber: `FORGED-${RUN}`,
          carrierCode: 'SWIFTPOST',
        },
      });
      expect(forged.status()).toBe(403);
      expect((await beta.request.get('/api/retailers/alpha-goods/shipments')).status()).toBe(403);
      expect((await beta.request.get(`/api${shipmentPath}/events`)).status()).toBe(404);
      expect((await beta.request.get(`/api${shipmentPath}/notifications`)).status()).toBe(404);

      // Alpha, the owner, still sees it in the list.
      await alpha.goto('/shipments');
      await expect(alpha.getByRole('link', { name: trackingNumber })).toBeVisible();
    } finally {
      await alphaContext.close();
      await betaContext.close();
    }
  });

  test('CSRF: a planted XSRF-TOKEN cookie is worthless; only the session token works', async ({
    page,
    baseURL,
  }) => {
    await signIn(page, RETAILER_ALPHA);
    // What another origin on this host (a sibling subdomain; on localhost, any other port) could do:
    // set a cookie it knows the value of, then submit that value.
    await page.context().addCookies([
      { name: 'XSRF-TOKEN', value: 'planted-by-another-origin', url: String(baseURL) },
    ]);
    const body = {
      retailerId: 'alpha-goods',
      customerId: 'c',
      trackingNumber: `E2E-CSRF-${RUN}`,
      carrierCode: 'SWIFTPOST',
    };
    const tossed = await page.request.post('/api/shipments', {
      headers: { 'X-XSRF-TOKEN': 'planted-by-another-origin' },
      data: body,
    });
    expect(tossed.status()).toBe(403);
    expect(((await tossed.json()) as { type: string }).type).toMatch(/csrf-token-invalid$/);

    const genuine = await page.request.post('/api/shipments', {
      headers: await csrfHeaders(page),
      data: body,
    });
    expect(genuine.status()).toBe(201);
  });

  test('a spoofed X-Forwarded-Host cannot move the OIDC redirect to another origin', async ({
    request,
    baseURL,
  }) => {
    const response = await request.get('/oauth2/authorization/keycloak', {
      headers: { 'X-Forwarded-Host': 'evil.example', Forwarded: 'host=evil.example' },
      maxRedirects: 0,
    });
    expect(response.status()).toBe(302);
    const authorize = new URL(response.headers()['location'] ?? '');
    const redirectUri = new URL(authorize.searchParams.get('redirect_uri') ?? '');
    expect(redirectUri.origin).toBe(new URL(String(baseURL)).origin);
    expect(redirectUri.pathname).toBe('/login/oauth2/code/keycloak');
  });

  test("an ADMIN sees every retailer's shipments", async ({ browser }) => {
    const alphaContext = await browser.newContext();
    const alpha = await alphaContext.newPage();
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    try {
      await signIn(alpha, RETAILER_ALPHA);
      const trackingNumber = `E2E-ADM-${RUN}`;
      const created = await alpha.request.post('/api/shipments', {
        headers: await csrfHeaders(alpha),
        data: {
          retailerId: 'alpha-goods',
          customerId: 'c',
          trackingNumber,
          carrierCode: 'SWIFTPOST',
        },
      });
      expect(created.status()).toBe(201);
      const { shipmentId } = (await created.json()) as { shipmentId: string };

      await signIn(admin, ADMIN);
      await admin.goto(`/shipments/${shipmentId}`);
      await expect(admin.getByTestId('current-status')).toHaveAttribute(
        'data-status',
        'LABEL_CREATED',
      );
      await expect(admin.getByText('alpha-goods').first()).toBeVisible();
    } finally {
      await alphaContext.close();
      await adminContext.close();
    }
  });
});
