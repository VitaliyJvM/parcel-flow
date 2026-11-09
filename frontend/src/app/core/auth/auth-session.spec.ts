import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ADMIN_USER,
  provideRecordingNavigation,
  RecordingNavigation,
  retailerUser,
} from '../../../testing/auth';
import { provideTestHttp } from '../../../testing/http';
import { AuthSession, safeReturnUrl } from './auth-session';

describe('AuthSession', () => {
  let http: HttpTestingController;
  let session: AuthSession;
  let navigation: RecordingNavigation;

  beforeEach(() => {
    sessionStorage.clear();
    const recording = provideRecordingNavigation();
    navigation = recording.navigation;
    TestBed.configureTestingModule({ providers: [...provideTestHttp(), recording.provider] });
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(AuthSession);
  });

  afterEach(() => http.verify());

  it('starts unknown and restores an authenticated session from the backend (page reload)', async () => {
    expect(session.status()).toBe('unknown');
    const loaded = session.ensureLoaded();
    http
      .expectOne('/api/auth/session')
      .flush({ authenticated: true, user: retailerUser('alpha-goods') });

    expect(await loaded).toBe('authenticated');
    expect(session.isRetailer()).toBe(true);
    expect(session.isAdmin()).toBe(false);
    expect(session.retailerId()).toBe('alpha-goods');
    expect(session.user()?.displayName).toBe('Retailer alpha-goods');
  });

  it('shares one request between concurrent callers and does not ask again once known', async () => {
    const a = session.ensureLoaded();
    const b = session.ensureLoaded();
    http.expectOne('/api/auth/session').flush({ authenticated: false, user: null });
    expect(await a).toBe('anonymous');
    expect(await b).toBe('anonymous');
    expect(await session.ensureLoaded()).toBe('anonymous');
    http.expectNone('/api/auth/session');
  });

  it('reports an unreachable backend as unavailable, not as signed out', async () => {
    const loaded = session.ensureLoaded();
    http.expectOne('/api/auth/session').error(new ProgressEvent('error'));
    // The transient-retry interceptor retries GETs on status 0; fail those attempts too.
    await new Promise((resolve) => setTimeout(resolve, 350));
    http.match('/api/auth/session').forEach((r) => r.error(new ProgressEvent('error')));
    await new Promise((resolve) => setTimeout(resolve, 650));
    http.match('/api/auth/session').forEach((r) => r.error(new ProgressEvent('error')));
    expect(await loaded).toBe('unavailable');
    expect(session.isAuthenticated()).toBe(false);
  });

  it('an admin has no retailer of their own', () => {
    session.accept({ authenticated: true, user: ADMIN_USER });
    expect(session.isAdmin()).toBe(true);
    expect(session.retailerId()).toBeNull();
  });

  it('never stores anything token-like in browser storage', () => {
    localStorage.clear();
    session.accept({ authenticated: true, user: ADMIN_USER });
    session.login('/shipments');
    const stored = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage });
    expect(stored).not.toMatch(/token|eyJ|secret/i);
  });

  describe('login', () => {
    it('navigates to the BFF login endpoint and remembers a safe return route', () => {
      session.login('/shipments?status=DELIVERED');
      expect(navigation.assigned).toEqual(['/oauth2/authorization/keycloak']);
      expect(session.consumeReturnUrl()).toBe('/shipments?status=DELIVERED');
      expect(session.consumeReturnUrl()).toBeNull();
    });

    it('can force the identity provider to prompt again', () => {
      session.login(null, { forcePrompt: true });
      expect(navigation.assigned).toEqual(['/oauth2/authorization/keycloak?prompt=login']);
    });

    it('drops a return URL that would leave the application', () => {
      session.login('https://evil.example/');
      expect(session.consumeReturnUrl()).toBeNull();
    });
  });

  describe('logout', () => {
    it('fetches the masked CSRF token and submits a top-level POST form to /logout', async () => {
      session.accept({ authenticated: true, user: ADMIN_USER });
      const done = session.logout();
      http
        .expectOne('/api/auth/csrf')
        .flush({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'masked' });
      await done;

      expect(navigation.posts).toEqual([{ action: '/logout', fields: { _csrf: 'masked' } }]);
      expect(session.isAuthenticated()).toBe(false);
    });
  });

  it('marks an authenticated session expired on a 401, with the reason kept for the login page', () => {
    session.accept({ authenticated: true, user: ADMIN_USER });
    session.markExpired();
    expect(session.status()).toBe('anonymous');
    expect(session.signedOutReason()).toBe('expired');
    session.accept({ authenticated: true, user: ADMIN_USER });
    expect(session.signedOutReason()).toBeNull();
  });
});

describe('safeReturnUrl', () => {
  it.each([
    ['/shipments', '/shipments'],
    ['/shipments/abc?x=1', '/shipments/abc?x=1'],
    ['//evil.example', null],
    ['/\\evil.example', null],
    ['https://evil.example', null],
    ['javascript:alert(1)', null],
    ['/login?returnUrl=/x', null],
    ['/signed-out', null],
    ['', null],
    [null, null],
  ])('%s -> %s', (input, expected) => {
    expect(safeReturnUrl(input)).toBe(expected);
  });
});
