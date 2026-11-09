import { expect, Page } from '@playwright/test';

/**
 * Real logins through the real Keycloak login form: no API shortcut, no injected cookie. The same
 * identities as the local realm (keycloak/realm/parcelflow-realm.json).
 *
 * Passwords come from the environment — CI sets throwaway CI-only values for the Compose stack;
 * locally they are the generated values in the repository's .env:
 *
 *   set -a && source ../.env && set +a && npm run e2e
 */
export interface Identity {
  username: string;
  password: () => string;
  retailerId: string | null;
}

function secret(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} is not set. Export the local passwords first: set -a && source ../.env && set +a`,
    );
  }
  return value;
}

export const ADMIN: Identity = {
  username: 'ops.admin',
  password: () => secret('PARCELFLOW_ADMIN_PASSWORD'),
  retailerId: null,
};
export const RETAILER_ALPHA: Identity = {
  username: 'retailer.alpha',
  password: () => secret('PARCELFLOW_RETAILER_PASSWORD'),
  retailerId: 'alpha-goods',
};
export const RETAILER_BETA: Identity = {
  username: 'retailer.beta',
  password: () => secret('PARCELFLOW_RETAILER_PASSWORD'),
  retailerId: 'beta-supplies',
};
export const RETAILER_UNASSIGNED: Identity = {
  username: 'retailer.unassigned',
  password: () => secret('PARCELFLOW_RETAILER_PASSWORD'),
  retailerId: null,
};

/**
 * Fills and submits Keycloak's login form, which the browser is expected to be on.
 *
 * With `prompt=login` and a live SSO session, Keycloak shows a re-authentication form for the
 * current user (username fixed, password only). Its "restart login" control switches account; use
 * it when the identity to sign in as is someone else.
 */
export async function submitKeycloakLogin(page: Page, identity: Identity): Promise<void> {
  await expect(page.locator('#kc-form-login')).toBeVisible();
  // keycloak.v2 theme: the re-authentication form shows the current user in a read-only
  // #kc-attempted-username and has no #username field at all.
  const attempted = page.locator('#kc-attempted-username');
  if (await attempted.isVisible()) {
    if ((await attempted.inputValue()) === identity.username) {
      await page.locator('#password').fill(identity.password());
      await page.locator('#kc-login').click();
      return;
    }
    await page.locator('#reset-login').click();
  }
  await page.locator('#username').fill(identity.username);
  await page.locator('#password').fill(identity.password());
  await page.locator('#kc-login').click();
}

/**
 * Signs in from the UI's login page and waits until the application shell shows the user. `path`
 * is where to start; an unauthenticated visit there redirects to /login first.
 */
export async function signIn(page: Page, identity: Identity, path = '/dashboard'): Promise<void> {
  await page.goto(path);
  await expect(page).toHaveURL(/\/login/);
  await page.getByRole('button', { name: 'Sign in with Keycloak' }).click();
  await submitKeycloakLogin(page, identity);
  await expect(page.getByTestId('user-menu')).toContainText(
    identity.retailerId ? 'RETAILER' : 'ADMIN',
  );
}

export async function signOut(page: Page): Promise<void> {
  await page.getByTestId('user-menu').click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/signed-out$/);
}

/**
 * Headers for an API write from the signed-in page's session: the session's CSRF token, fetched
 * the way the UI fetches it (it is bound to the server-side session and never a cookie).
 */
export async function csrfHeaders(page: Page): Promise<Record<string, string>> {
  const response = await page.request.get('/api/auth/csrf');
  if (!response.ok()) {
    throw new Error(`GET /api/auth/csrf answered ${response.status()}: is the page signed in?`);
  }
  const csrf = (await response.json()) as { headerName: string; token: string };
  return { [csrf.headerName]: csrf.token };
}
