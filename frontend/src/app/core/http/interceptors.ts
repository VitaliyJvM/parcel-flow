import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, retry, throwError, timer } from 'rxjs';
import { ApiError, CORRELATION_ID_HEADER } from '../api/api-error';

/** Opt a GET out of transient-failure retries, e.g. a lightweight polling probe. */
export const SKIP_RETRY = new HttpContextToken<boolean>(() => false);

const API_PREFIX = '/api/';

function isApiRequest(url: string): boolean {
  return url.startsWith(API_PREFIX);
}

function newCorrelationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `pf-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * Tags every API request with a fresh `X-Correlation-Id`.
 *
 * The backend propagates a caller-supplied id rather than minting its own, so the id the UI shows
 * next to an error is the same one in the service's structured logs — even for a request that
 * never got a response, where there is no echoed header to read.
 */
export const correlationIdInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isApiRequest(req.url) || req.headers.has(CORRELATION_ID_HEADER)) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { [CORRELATION_ID_HEADER]: newCorrelationId() } }));
};

const TRANSIENT_STATUSES = new Set([0, 502, 503, 504]);
const MAX_RETRIES = 2;

/**
 * Retries idempotent GETs on transient failures (no response, or a proxy saying the upstream is
 * down) with a short backoff. Never retries writes: a retried POST /api/shipments could surface
 * a misleading 409 for a registration that actually succeeded, and a retried failed-event retry
 * would reprocess an event twice.
 */
export const transientRetryInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method !== 'GET' || !isApiRequest(req.url) || req.context.get(SKIP_RETRY)) {
    return next(req);
  }
  return next(req).pipe(
    retry({
      count: MAX_RETRIES,
      delay: (error: unknown, attempt: number) =>
        error instanceof HttpErrorResponse && TRANSIENT_STATUSES.has(error.status)
          ? timer(300 * 2 ** (attempt - 1))
          : throwError(() => error),
    }),
  );
};

/**
 * Converts every API failure into an {@link ApiError} parsed from the RFC 9457 problem document.
 * Registered last so it sees the final outcome after retries.
 */
export const problemDetailsInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isApiRequest(req.url)) {
    return next(req);
  }
  const sentCorrelationId = req.headers.get(CORRELATION_ID_HEADER);
  return next(req).pipe(
    catchError((error: unknown) =>
      throwError(() =>
        error instanceof HttpErrorResponse ? ApiError.fromHttpError(error, sentCorrelationId) : error,
      ),
    ),
  );
};
