import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { Role } from '../api/models';
import { AuthSession, safeReturnUrl } from './auth-session';

/**
 * Route guards. They decide what to *show*; they are not the security boundary. Every API call is
 * authorized by the backend from the session, so a route reached by editing the URL can render at
 * most an empty page and a 403.
 */

/** Signed-in users only. Anyone else goes to /login, remembering where they were heading. */
export const authGuard: CanActivateFn = async (_route, state): Promise<boolean | UrlTree> => {
  const session = inject(AuthSession);
  const router = inject(Router);
  const status = await session.ensureLoaded();
  if (status === 'authenticated') {
    return true;
  }
  return router.createUrlTree(['/login'], {
    queryParams: {
      returnUrl: state.url,
      ...(status === 'unavailable' ? { error: 'unavailable' } : {}),
    },
  });
};

/** A route for one role, e.g. the failed-event pages for ADMIN. Others see /forbidden. */
export function roleGuard(role: Role): CanActivateFn {
  return async (): Promise<boolean | UrlTree> => {
    const session = inject(AuthSession);
    const router = inject(Router);
    await session.ensureLoaded();
    return session.role() === role ? true : router.createUrlTree(['/forbidden']);
  };
}

/**
 * The application root, where the backend lands the browser after a successful login. Resumes the
 * route remembered before the round trip to Keycloak, once; otherwise the dashboard.
 */
export const landingGuard: CanActivateFn = (): UrlTree => {
  const session = inject(AuthSession);
  return inject(Router).parseUrl(session.consumeReturnUrl() ?? '/dashboard');
};

/** The login page itself: a user who is already signed in has nothing to do there. */
export const signedOutOnlyGuard: CanActivateFn = async (route): Promise<boolean | UrlTree> => {
  const session = inject(AuthSession);
  const router = inject(Router);
  if ((await session.ensureLoaded()) !== 'authenticated') {
    return true;
  }
  return router.parseUrl(safeReturnUrl(route.queryParamMap.get('returnUrl')) ?? '/dashboard');
};
