import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { isApiError } from '../api/api-error';
import { AuthSession } from './auth-session';
import { CsrfTokens } from './csrf';

/** Marks the one automatic retry after a CSRF refresh, so a second refusal is final. */
const CSRF_RETRIED = new HttpContextToken<boolean>(() => false);

const AUTH_PREFIX = '/api/auth/';

/**
 * Reacts to the backend's authentication decisions on API calls.
 *
 * - **401** (`authentication-required`): the session is gone — expired (30 minutes without user
 *   activity, or 8 hours after login), logged out in another tab, or invalidated on the server. The
 *   state and the cached CSRF token are cleared and the user is sent to the login page with the
 *   route they were on, so signing in brings them back.
 * - **403 `csrf-token-invalid`** on a write: the cached CSRF token was stale (the session behind it
 *   changed). The request was refused before it did anything, so it is safe to drop the token and
 *   send the request once more; the CSRF interceptor fetches a fresh one. A second refusal is
 *   reported normally.
 * - Any other 403 is a real permission refusal and is left to the page to explain.
 *
 * Registered between the correlation-id and problem-details interceptors, so it sees parsed
 * `ApiError`s, and its retry goes back through the CSRF interceptor, which adds the new token.
 */
export const sessionInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/') || req.url.startsWith(AUTH_PREFIX)) {
    return next(req);
  }
  const session = inject(AuthSession);
  const router = inject(Router);
  const csrf = inject(CsrfTokens);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (!isApiError(error)) {
        return throwError(() => error);
      }
      if (error.status === 401) {
        csrf.clear();
        session.markExpired();
        const returnUrl = router.url;
        void router.navigate(['/login'], {
          queryParams: {
            reason: 'expired',
            ...(returnUrl.startsWith('/login') ? {} : { returnUrl }),
          },
        });
        return throwError(() => error);
      }
      if (
        error.status === 403 &&
        error.slug === 'csrf-token-invalid' &&
        !req.context.get(CSRF_RETRIED)
      ) {
        csrf.clear();
        return next(req.clone({ context: req.context.set(CSRF_RETRIED, true) }));
      }
      return throwError(() => error);
    }),
  );
};
