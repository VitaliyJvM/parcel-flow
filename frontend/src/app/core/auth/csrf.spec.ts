import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { CSRF_PATH, csrfInterceptor, CsrfTokens } from './csrf';

const TOKEN = { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'masked-1' };

describe('csrfInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([csrfInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  afterEach(() => http.verify());

  it('sends the session token from /api/auth/csrf in the header the backend names', async () => {
    const result = firstValueFrom(client.post('/api/shipments', { a: 1 }));
    http.expectOne(CSRF_PATH).flush(TOKEN);
    const write = http.expectOne('/api/shipments');
    expect(write.request.headers.get('X-XSRF-TOKEN')).toBe('masked-1');
    write.flush({ ok: true });
    await expect(result).resolves.toEqual({ ok: true });
  });

  it('never reads a cookie: a planted XSRF-TOKEN cookie is not what gets sent', async () => {
    document.cookie = 'XSRF-TOKEN=planted; path=/';
    try {
      const result = firstValueFrom(client.post('/logout-like', null));
      http.expectOne(CSRF_PATH).flush(TOKEN);
      const write = http.expectOne('/logout-like');
      expect(write.request.headers.get('X-XSRF-TOKEN')).toBe('masked-1');
      write.flush(null);
      await result;
    } finally {
      document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    }
  });

  it('shares one token fetch between concurrent writes and reuses it afterwards', async () => {
    const a = firstValueFrom(client.post('/api/a', null));
    const b = firstValueFrom(client.post('/api/b', null));
    http.expectOne(CSRF_PATH).flush(TOKEN);
    http.expectOne('/api/a').flush(1);
    http.expectOne('/api/b').flush(2);
    await Promise.all([a, b]);

    const c = firstValueFrom(client.post('/api/c', null));
    http.expectNone(CSRF_PATH);
    const reused = http.expectOne('/api/c');
    expect(reused.request.headers.get('X-XSRF-TOKEN')).toBe('masked-1');
    reused.flush(3);
    await expect(c).resolves.toBe(3);
  });

  it('fetches a fresh token after clear()', async () => {
    const first = firstValueFrom(client.post('/api/a', null));
    http.expectOne(CSRF_PATH).flush(TOKEN);
    http.expectOne('/api/a').flush(1);
    await first;

    TestBed.inject(CsrfTokens).clear();
    const second = firstValueFrom(client.post('/api/a', null));
    http.expectOne(CSRF_PATH).flush({ ...TOKEN, token: 'masked-2' });
    const write = http.expectOne('/api/a');
    expect(write.request.headers.get('X-XSRF-TOKEN')).toBe('masked-2');
    write.flush(2);
    await second;
  });

  it('does not cache a failed token fetch', async () => {
    const failed = firstValueFrom(client.post('/api/a', null));
    http.expectOne(CSRF_PATH).flush(null, { status: 503, statusText: 'Unavailable' });
    await expect(failed).rejects.toBeTruthy();
    http.expectNone('/api/a');

    const retried = firstValueFrom(client.post('/api/a', null));
    http.expectOne(CSRF_PATH).flush(TOKEN);
    http.expectOne('/api/a').flush(1);
    await expect(retried).resolves.toBe(1);
  });

  it('leaves safe methods and other origins alone', async () => {
    const read = firstValueFrom(client.get('/api/shipments/x'));
    const get = http.expectOne('/api/shipments/x');
    expect(get.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    get.flush({});
    await read;
    http.expectNone(CSRF_PATH);

    for (const url of ['https://elsewhere.example/api', '//elsewhere.example/api']) {
      const write = firstValueFrom(client.post(url, null));
      const sent = http.expectOne(url);
      expect(sent.request.headers.has('X-XSRF-TOKEN')).toBe(false);
      sent.flush(null);
      await write;
    }
    http.expectNone(CSRF_PATH);
  });
});
