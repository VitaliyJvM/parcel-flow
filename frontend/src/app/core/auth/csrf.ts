import { HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, shareReplay, switchMap } from 'rxjs';
import { CsrfTokenResponse } from '../api/models';

export const CSRF_PATH = '/api/auth/csrf';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

/**
 * The session's CSRF token.
 *
 * The backend keeps the token in the server-side session and hands it out only through
 * `GET /api/auth/csrf` — never as a cookie, because a cookie can be planted by another origin on the
 * same host (a sibling subdomain; on localhost, any other local service), and a token an attacker
 * can plant is a token the attacker knows. Same-origin script can read the endpoint; a cross-site
 * page cannot (no CORS).
 *
 * One request is shared by every concurrent write and the answer is reused until `clear()`: when
 * the server refuses a token (it rotated at login or logout) or the session ends (401). A failed
 * fetch is not cached, so the next write asks again.
 */
@Injectable({ providedIn: 'root' })
export class CsrfTokens {
  private readonly http = inject(HttpClient);
  private current: Observable<CsrfTokenResponse> | null = null;

  token(): Observable<CsrfTokenResponse> {
    // shareReplay resets on error, so a failed fetch is retried by the next caller.
    this.current ??= this.http
      .get<CsrfTokenResponse>(CSRF_PATH)
      .pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.current;
  }

  /** Forget the token; the next write fetches a fresh one. */
  clear(): void {
    this.current = null;
  }
}

/** Relative, same-origin URLs only: a token must never be sent to another origin. */
function isSameOrigin(url: string): boolean {
  return url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/\\');
}

/**
 * Adds the session's CSRF token to every state-changing same-origin request, in the header the
 * backend names (`X-XSRF-TOKEN`). Replaces Angular's built-in cookie-to-header XSRF support, which
 * is disabled in `app.config.ts`.
 *
 * Registered last (closest to the backend), so the session interceptor's one retry after a CSRF
 * refusal passes through here again and picks up a fresh token.
 */
export const csrfInterceptor: HttpInterceptorFn = (req, next) => {
  if (SAFE_METHODS.has(req.method) || !isSameOrigin(req.url)) {
    return next(req);
  }
  return inject(CsrfTokens)
    .token()
    .pipe(
      switchMap((csrf) => next(req.clone({ setHeaders: { [csrf.headerName]: csrf.token } }))),
    );
};
