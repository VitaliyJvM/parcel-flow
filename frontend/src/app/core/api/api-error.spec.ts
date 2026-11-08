import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { ApiError, toApiError } from './api-error';

describe('ApiError.fromHttpError', () => {
  it('parses an RFC 9457 document with extension members', () => {
    const error = ApiError.fromHttpError(
      new HttpErrorResponse({
        status: 409,
        headers: new HttpHeaders({ 'X-Correlation-Id': 'abc' }),
        error: {
          type: 'https://parcelflow.example/problems/duplicate-tracking-number',
          title: 'Duplicate tracking number',
          status: 409,
          detail: 'Tracking number SP1 is already registered for carrier SWIFTPOST',
          instance: '/api/shipments',
          carrierCode: 'SWIFTPOST',
          trackingNumber: 'SP1',
        },
      }),
      'sent',
    );
    expect(error.slug).toBe('duplicate-tracking-number');
    expect(error.title).toBe('Duplicate tracking number');
    expect(error.extension('carrierCode')).toBe('SWIFTPOST');
    expect(error.extension('trackingNumber')).toBe('SP1');
    expect(error.extension('instance')).toBeNull();
    expect(error.correlationId).toBe('abc');
  });

  it('parses a problem document delivered as a string body', () => {
    const error = ApiError.fromHttpError(
      new HttpErrorResponse({
        status: 404,
        error: JSON.stringify({ type: 'https://parcelflow.example/problems/not-found', title: 'Not found' }),
      }),
      null,
    );
    expect(error.slug).toBe('not-found');
  });

  it('describes a proxy 502 without a problem body', () => {
    const error = ApiError.fromHttpError(
      new HttpErrorResponse({ status: 502, statusText: 'Bad Gateway', error: '<html/>' }),
      'sent',
    );
    expect(error.isServerError).toBe(true);
    expect(error.detail).toContain('unavailable');
    expect(error.correlationId).toBe('sent');
  });

  it('ignores non-string values in the errors map', () => {
    const error = ApiError.fromHttpError(
      new HttpErrorResponse({
        status: 400,
        error: { title: 'Validation failed', errors: { a: 'bad', b: 42 } },
      }),
      null,
    );
    expect(error.fieldErrors).toEqual({ a: 'bad' });
  });

  it('wraps arbitrary thrown values', () => {
    expect(toApiError(new Error('boom')).detail).toBe('boom');
    expect(toApiError('x').title).toBe('Unexpected error');
  });
});
