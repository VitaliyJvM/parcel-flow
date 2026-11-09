import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { signInAs } from '../../../testing/auth';
import { problem } from '../../../testing/http';
import { ApiError } from '../api/api-error';
import {
  correlationIdInterceptor,
  problemDetailsInterceptor,
  transientRetryInterceptor,
} from '../http/interceptors';
import { AuthSession } from './auth-session';
import { CSRF_PATH, csrfInterceptor } from './csrf';
import { sessionInterceptor } from './session-interceptor';

describe('sessionInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([
            correlationIdInterceptor,
            sessionInterceptor,
            problemDetailsInterceptor,
            transientRetryInterceptor,
            csrfInterceptor,
          ]),
        ),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  it('on 401 clears the session and sends the user to /login with an expired notice', async () => {
    signInAs('RETAILER', 'alpha-goods');
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    const result = firstValueFrom(client.get('/api/shipments/x'));
    const { body, opts } = problem(401, 'authentication-required');
    http.expectOne('/api/shipments/x').flush(body, opts);

    await expect(result).rejects.toBeInstanceOf(ApiError);
    expect(TestBed.inject(AuthSession).isAuthenticated()).toBe(false);
    expect(TestBed.inject(AuthSession).signedOutReason()).toBe('expired');
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { reason: 'expired', returnUrl: '/' },
    });
  });

  const token = (value: string) => ({
    headerName: 'X-XSRF-TOKEN',
    parameterName: '_csrf',
    token: value,
  });

  it('on a CSRF refusal drops the stale token and retries the write once with a fresh one', async () => {
    signInAs('ADMIN');
    const result = firstValueFrom(client.post('/api/admin/failed-events/1/retry', null));

    http.expectOne(CSRF_PATH).flush(token('stale'));
    const first = http.expectOne('/api/admin/failed-events/1/retry');
    expect(first.request.headers.get('X-XSRF-TOKEN')).toBe('stale');
    const csrf = problem(403, 'csrf-token-invalid');
    first.flush(csrf.body, csrf.opts);

    http.expectOne(CSRF_PATH).flush(token('fresh'));
    const retry = http.expectOne('/api/admin/failed-events/1/retry');
    expect(retry.request.headers.get('X-XSRF-TOKEN')).toBe('fresh');
    retry.flush({ succeeded: true });

    await expect(result).resolves.toEqual({ succeeded: true });
  });

  it('reports a second CSRF refusal instead of looping', async () => {
    signInAs('ADMIN');
    const result = firstValueFrom(client.post('/api/shipments', {}));

    const csrf = problem(403, 'csrf-token-invalid');
    http.expectOne(CSRF_PATH).flush(token('t1'));
    http.expectOne('/api/shipments').flush(csrf.body, csrf.opts);
    http.expectOne(CSRF_PATH).flush(token('t2'));
    http.expectOne('/api/shipments').flush(csrf.body, csrf.opts);

    await expect(result).rejects.toMatchObject({ status: 403, slug: 'csrf-token-invalid' });
  });

  it('a 401 also drops the cached CSRF token: the next session has its own', async () => {
    signInAs('ADMIN');
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const first = firstValueFrom(client.post('/api/shipments', {}));
    http.expectOne(CSRF_PATH).flush(token('old-session'));
    const expired = problem(401, 'authentication-required');
    http.expectOne('/api/shipments').flush(expired.body, expired.opts);
    await expect(first).rejects.toMatchObject({ status: 401 });

    const next = firstValueFrom(client.post('/api/shipments', {}));
    http.expectOne(CSRF_PATH).flush(token('new-session'));
    const write = http.expectOne('/api/shipments');
    expect(write.request.headers.get('X-XSRF-TOKEN')).toBe('new-session');
    write.flush({});
    await next;
  });

  it('leaves a genuine 403 (permission) to the page', async () => {
    signInAs('RETAILER', 'alpha-goods');
    const navigate = vi.spyOn(router, 'navigate');
    const result = firstValueFrom(client.get('/api/retailers/beta-supplies/shipments'));
    const denied = problem(403, 'retailer-access-denied');
    http.expectOne('/api/retailers/beta-supplies/shipments').flush(denied.body, denied.opts);

    await expect(result).rejects.toMatchObject({ status: 403, slug: 'retailer-access-denied' });
    expect(navigate).not.toHaveBeenCalled();
    expect(TestBed.inject(AuthSession).isAuthenticated()).toBe(true);
  });

  it('does not react to the session endpoint itself', async () => {
    const navigate = vi.spyOn(router, 'navigate');
    const result = firstValueFrom(client.get('/api/auth/session'));
    const { body, opts } = problem(401, 'authentication-required');
    http.expectOne('/api/auth/session').flush(body, opts);
    await expect(result).rejects.toBeInstanceOf(ApiError);
    expect(navigate).not.toHaveBeenCalled();
  });
});
