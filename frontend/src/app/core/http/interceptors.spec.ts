import { HttpClient } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { problem, provideTestHttp } from '../../../testing/http';
import { ApiError } from '../api/api-error';

describe('HTTP interceptors', () => {
  let http: HttpClient;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: provideTestHttp() });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    controller.verify();
    vi.useRealTimers();
  });

  it('adds a fresh X-Correlation-Id to API requests only', () => {
    http.get('/api/shipments/x').subscribe();
    http.get('/assets/config.json').subscribe();
    const api = controller.expectOne('/api/shipments/x');
    const other = controller.expectOne('/assets/config.json');
    expect(api.request.headers.get('X-Correlation-Id')).toMatch(/^[0-9a-f-]{36}$/);
    expect(other.request.headers.has('X-Correlation-Id')).toBe(false);
    api.flush({});
    other.flush({});
  });

  it('keeps a caller-supplied correlation id', () => {
    http.get('/api/shipments/x', { headers: { 'X-Correlation-Id': 'mine' } }).subscribe();
    const req = controller.expectOne('/api/shipments/x');
    expect(req.request.headers.get('X-Correlation-Id')).toBe('mine');
    req.flush({});
  });

  it('converts a problem+json response into an ApiError with fields and correlation id', async () => {
    const result = firstValueFrom(http.post('/api/shipments', {}));
    const { body, opts } = problem(400, 'validation-failed', {
      errors: { trackingNumber: 'must not be blank', carrierCode: 'must not be null' },
    });
    controller.expectOne('/api/shipments').flush(body, opts);

    const error = await result.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(400);
    expect(apiError.slug).toBe('validation-failed');
    expect(apiError.fieldErrors).toEqual({
      trackingNumber: 'must not be blank',
      carrierCode: 'must not be null',
    });
    expect(apiError.correlationId).toBe('srv-corr-1');
  });

  it('falls back to the sent correlation id when the server echoed none', async () => {
    const result = firstValueFrom(http.get('/api/shipments/x'));
    const req = controller.expectOne('/api/shipments/x');
    const sent = req.request.headers.get('X-Correlation-Id');
    req.flush('<html>Bad gateway</html>', { status: 500, statusText: 'Server Error' });
    const error = (await result.catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(500);
    expect(error.slug).toBeNull();
    expect(error.correlationId).toBe(sent);
  });

  it('retries an idempotent GET on 503 and then succeeds', async () => {
    vi.useFakeTimers();
    let value: unknown;
    http.get('/api/shipments/x').subscribe((v) => (value = v));
    controller.expectOne('/api/shipments/x').flush(null, { status: 503, statusText: 'Unavailable' });
    await vi.advanceTimersByTimeAsync(300);
    controller.expectOne('/api/shipments/x').flush({ ok: true });
    expect(value).toEqual({ ok: true });
  });

  it('does not retry on 4xx', async () => {
    const result = firstValueFrom(http.get('/api/shipments/x'));
    const { body, opts } = problem(404, 'shipment-not-found');
    controller.expectOne('/api/shipments/x').flush(body, opts);
    const error = (await result.catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(404);
  });

  it('never retries a POST, even on 503', async () => {
    const result = firstValueFrom(http.post('/api/shipments', {}));
    controller.expectOne('/api/shipments').flush(null, { status: 503, statusText: 'Unavailable' });
    const error = (await result.catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(503);
    controller.expectNone('/api/shipments');
  });

  it('reports a network failure as status 0', async () => {
    const result = firstValueFrom(http.post('/api/shipments', {}));
    controller.expectOne('/api/shipments').error(new ProgressEvent('error'));
    const error = (await result.catch((e: unknown) => e)) as ApiError;
    expect(error.isNetworkError).toBe(true);
    expect(error.correlationId).toMatch(/^[0-9a-f-]{36}$/);
  });
});
