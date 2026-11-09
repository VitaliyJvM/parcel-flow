import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthSessionResponse, CsrfTokenResponse, Role, SessionUser } from '../api/models';
import { BrowserNavigation } from './browser-navigation';

/**
 * `unknown`: not asked yet. `anonymous`: the backend says there is no authenticated session.
 * `unavailable`: the backend could not be asked (down, unreachable) — distinct from anonymous so
 * the login page does not send someone to Keycloak when the service behind it is broken.
 */
export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous' | 'unavailable';

/** Why the user is on the login page, for the message shown there. */
export type SignedOutReason = 'expired' | null;

/** Spring Security's authorization endpoint for the Keycloak registration (proxied same-origin). */
export const LOGIN_PATH = '/oauth2/authorization/keycloak';
export const LOGOUT_PATH = '/logout';
const RETURN_URL_KEY = 'parcelflow.returnUrl';

/**
 * The browser's authentication state, as the backend (the BFF) reports it.
 *
 * The UI holds no credential. The session is an HttpOnly cookie the UI cannot read; OAuth tokens
 * never leave the server. What this service knows comes from `GET /api/auth/session`: who the
 * user is, their single role, and — for a RETAILER — the one retailer the server lets them act for.
 * That retailer is authoritative here only for display and for building requests; the backend
 * checks every request against its own copy.
 *
 * Signals throughout: components and guards read `status()`, `role()`, `retailerId()`.
 */
@Injectable({ providedIn: 'root' })
export class AuthSession {
  private readonly http = inject(HttpClient);
  private readonly navigation = inject(BrowserNavigation);

  private readonly state = signal<{ status: AuthStatus; user: SessionUser | null }>({
    status: 'unknown',
    user: null,
  });
  private readonly reason = signal<SignedOutReason>(null);
  private pending: Promise<AuthStatus> | null = null;

  readonly status = computed(() => this.state().status);
  readonly user = computed(() => this.state().user);
  readonly isAuthenticated = computed(() => this.state().status === 'authenticated');
  readonly role = computed<Role | null>(() => this.state().user?.role ?? null);
  readonly isAdmin = computed(() => this.role() === 'ADMIN');
  readonly isRetailer = computed(() => this.role() === 'RETAILER');
  /** The server-assigned retailer of a RETAILER user; null for an admin. */
  readonly retailerId = computed(() =>
    this.isRetailer() ? (this.state().user?.retailerId ?? null) : null,
  );
  readonly signedOutReason = this.reason.asReadonly();

  /** Resolves once the session has been asked for at least once. Concurrent callers share one request. */
  ensureLoaded(): Promise<AuthStatus> {
    const status = this.state().status;
    if (status === 'authenticated' || status === 'anonymous') {
      return Promise.resolve(status);
    }
    return this.refresh();
  }

  /** Asks the backend again, e.g. after a page reload or to retry after `unavailable`. */
  refresh(): Promise<AuthStatus> {
    this.pending ??= firstValueFrom(this.http.get<AuthSessionResponse>('/api/auth/session'))
      .then(
        (response) => this.accept(response),
        () => {
          this.state.set({ status: 'unavailable', user: null });
          return 'unavailable' as const;
        },
      )
      .finally(() => (this.pending = null));
    return this.pending;
  }

  /** Applies a session response. Public for tests; production code goes through `refresh()`. */
  accept(response: AuthSessionResponse): AuthStatus {
    const user = response.authenticated ? (response.user ?? null) : null;
    const status: AuthStatus = user ? 'authenticated' : 'anonymous';
    this.state.set({ status, user });
    if (user) {
      this.reason.set(null);
    }
    return status;
  }

  /**
   * Starts the OIDC login: a full-page navigation to Spring Security, which redirects to Keycloak.
   *
   * `returnUrl` is remembered in sessionStorage (a route, not a credential) so the app can come
   * back to it after the round trip. `forcePrompt` asks Keycloak to show its login form even if an
   * SSO session exists — used after a logout or a refused login, so "Sign in" never silently
   * reuses the previous identity.
   */
  login(returnUrl: string | null = null, options: { forcePrompt?: boolean } = {}): void {
    const safe = safeReturnUrl(returnUrl);
    try {
      if (safe) {
        globalThis.sessionStorage?.setItem(RETURN_URL_KEY, safe);
      } else {
        globalThis.sessionStorage?.removeItem(RETURN_URL_KEY);
      }
    } catch {
      // Storage blocked: the user lands on the dashboard instead. Not worth failing login over.
    }
    this.navigation.assign(options.forcePrompt ? `${LOGIN_PATH}?prompt=login` : LOGIN_PATH);
  }

  /** The route to resume after login, once. */
  consumeReturnUrl(): string | null {
    try {
      const value = globalThis.sessionStorage?.getItem(RETURN_URL_KEY) ?? null;
      globalThis.sessionStorage?.removeItem(RETURN_URL_KEY);
      return safeReturnUrl(value);
    } catch {
      return null;
    }
  }

  /**
   * Logs out: a top-level form POST to `/logout` carrying the masked CSRF token. The backend
   * invalidates the session, rotates the CSRF token and redirects to Keycloak's end-session
   * endpoint, which ends the SSO session and returns the browser to `/signed-out`.
   */
  async logout(): Promise<void> {
    const csrf = await firstValueFrom(this.http.get<CsrfTokenResponse>('/api/auth/csrf'));
    this.state.set({ status: 'anonymous', user: null });
    this.navigation.submitPost(LOGOUT_PATH, { [csrf.parameterName]: csrf.token });
  }

  /** The backend answered 401: the session expired or was invalidated elsewhere. */
  markExpired(): void {
    if (this.state().status === 'authenticated') {
      this.reason.set('expired');
    }
    this.state.set({ status: 'anonymous', user: null });
  }
}

/**
 * Only same-application paths are accepted as a return target: an absolute URL or a
 * protocol-relative `//host` would turn the login page into an open redirect.
 */
export function safeReturnUrl(value: string | null | undefined): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return null;
  }
  if (value.startsWith('/login') || value.startsWith('/signed-out')) {
    return null;
  }
  return value;
}
