import { HttpErrorResponse } from '@angular/common/http';

/** The response header the tracking service echoes on every API response. */
export const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/** `type` URIs the backend emits are `https://parcelflow.example/problems/<slug>`. */
const PROBLEM_TYPE_PREFIX = 'https://parcelflow.example/problems/';

/**
 * Problem slugs the tracking service can return (see `ApiExceptionHandler`). Kept as a union so a
 * typo in a `switch` is a compile error; `string` covers slugs a newer backend might add.
 */
export type ProblemSlug =
  | 'shipment-not-found'
  | 'duplicate-tracking-number'
  | 'failed-event-not-found'
  | 'failed-event-not-retryable'
  | 'failed-event-retry-not-available'
  | 'validation-failed'
  | 'malformed-request'
  | 'not-found'
  | 'internal-error'
  | 'authentication-required'
  | 'csrf-token-invalid'
  | 'access-denied'
  | 'retailer-access-denied'
  | (string & {});

/** Shape of an RFC 9457 problem document as this backend produces it. */
export interface ProblemDocument {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: Record<string, string>;
  [extension: string]: unknown;
}

/**
 * Every failed API call surfaces as one of these, never as a raw `HttpErrorResponse`.
 *
 * Built once, in the error interceptor, so components branch on `status` / `slug` and render
 * `correlationId` without each re-parsing a response body that may be a problem document, an
 * nginx HTML error page, or nothing at all (network failure).
 */
export class ApiError extends Error {
  constructor(
    /** HTTP status; 0 when the request never got a response. */
    readonly status: number,
    readonly title: string,
    readonly detail: string,
    readonly slug: ProblemSlug | null,
    /** Field or parameter name to message, for validation failures. */
    readonly fieldErrors: Readonly<Record<string, string>>,
    /** Server-echoed id, or the id the client sent when there was no response. */
    readonly correlationId: string | null,
    /** Problem extension members (e.g. `carrierCode`, `errorCategory`). */
    readonly extensions: Readonly<Record<string, unknown>> = {},
  ) {
    super(`${status} ${title}: ${detail}`);
    this.name = 'ApiError';
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }

  get isServerError(): boolean {
    return this.status >= 500;
  }

  extension(name: string): string | null {
    const value = this.extensions[name];
    return typeof value === 'string' ? value : null;
  }

  static fromHttpError(error: HttpErrorResponse, sentCorrelationId: string | null): ApiError {
    const correlationId = error.headers?.get(CORRELATION_ID_HEADER) ?? sentCorrelationId;
    if (error.status === 0) {
      return new ApiError(
        0,
        'Service unreachable',
        'The tracking service did not respond. Check that the backend is running.',
        null,
        {},
        correlationId,
      );
    }
    const problem = asProblem(error.error);
    if (!problem) {
      return new ApiError(
        error.status,
        error.statusText || defaultTitle(error.status),
        defaultDetail(error.status),
        null,
        {},
        correlationId,
      );
    }
    const { type, title, status, detail, errors, instance, ...extensions } = problem;
    void instance;
    return new ApiError(
      typeof status === 'number' ? status : error.status,
      title ?? defaultTitle(error.status),
      detail ?? defaultDetail(error.status),
      slugOf(type),
      sanitizeErrors(errors),
      correlationId,
      extensions,
    );
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

/** Coerces anything thrown into an ApiError so templates have exactly one error shape. */
export function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) {
    return value;
  }
  const message = value instanceof Error ? value.message : 'Unexpected error';
  return new ApiError(-1, 'Unexpected error', message, null, {}, null);
}

function asProblem(body: unknown): ProblemDocument | null {
  if (typeof body === 'string') {
    try {
      return asProblem(JSON.parse(body));
    } catch {
      return null;
    }
  }
  if (body !== null && typeof body === 'object' && ('title' in body || 'type' in body)) {
    return body as ProblemDocument;
  }
  return null;
}

function slugOf(type: string | undefined): ProblemSlug | null {
  if (!type) {
    return null;
  }
  return type.startsWith(PROBLEM_TYPE_PREFIX) ? type.slice(PROBLEM_TYPE_PREFIX.length) : type;
}

function sanitizeErrors(errors: unknown): Record<string, string> {
  if (errors === null || typeof errors !== 'object') {
    return {};
  }
  const result: Record<string, string> = {};
  for (const [field, message] of Object.entries(errors)) {
    if (typeof message === 'string') {
      result[field] = message;
    }
  }
  return result;
}

function defaultTitle(status: number): string {
  if (status === 401) return 'Sign-in required';
  if (status === 403) return 'Not permitted';
  if (status === 404) return 'Not found';
  if (status === 409) return 'Conflict';
  if (status >= 500) return 'Server error';
  if (status >= 400) return 'Request rejected';
  return 'Request failed';
}

function defaultDetail(status: number): string {
  if (status === 502 || status === 503 || status === 504) {
    return 'The tracking service is unavailable behind the proxy. It may still be starting.';
  }
  if (status >= 500) {
    return 'The tracking service could not process the request.';
  }
  if (status === 401) {
    return 'Your session has ended. Sign in again to continue.';
  }
  if (status === 403) {
    return 'Your account is not permitted to do this.';
  }
  return 'The request was rejected.';
}
