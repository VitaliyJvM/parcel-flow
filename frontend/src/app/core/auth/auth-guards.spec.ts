import { HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideRecordingNavigation, signInAs } from '../../../testing/auth';
import { provideTestHttp } from '../../../testing/http';
import { authGuard, landingGuard, roleGuard, signedOutOnlyGuard } from './auth-guards';
import { AuthSession } from './auth-session';

@Component({ template: 'page' })
class Page {}

describe('route guards', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness | null;

  beforeEach(() => {
    harness = null;
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        ...provideTestHttp(),
        provideRecordingNavigation().provider,
        provideRouter([
          { path: 'login', component: Page, canActivate: [signedOutOnlyGuard] },
          {
            path: '',
            canActivateChild: [authGuard],
            children: [
              { path: '', pathMatch: 'full', canActivate: [landingGuard], children: [] },
              { path: 'dashboard', component: Page },
              { path: 'shipments', component: Page },
              {
                path: 'operations/failed-events',
                component: Page,
                canActivate: [roleGuard('ADMIN')],
              },
              { path: 'forbidden', component: Page },
            ],
          },
        ]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function navigate(url: string): Promise<string> {
    harness ??= await RouterTestingHarness.create();
    let done = false;
    const pending = harness.navigateByUrl(url).finally(() => (done = true));
    // A guard may ask the backend for the session; answer whatever is outstanding until the
    // navigation settles.
    while (!done) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      http.match('/api/auth/session').forEach((r) => r.flush({ authenticated: false, user: null }));
    }
    await pending;
    return TestBed.inject(Router).url;
  }

  it('sends an anonymous visitor to /login with the route they wanted', async () => {
    expect(await navigate('/shipments?status=DELIVERED')).toBe(
      '/login?returnUrl=%2Fshipments%3Fstatus%3DDELIVERED',
    );
  });

  it('lets a signed-in user through without asking the backend again', async () => {
    signInAs('RETAILER', 'alpha-goods');
    expect(await navigate('/shipments')).toBe('/shipments');
  });

  it('keeps a RETAILER out of ADMIN pages', async () => {
    signInAs('RETAILER', 'alpha-goods');
    expect(await navigate('/operations/failed-events')).toBe('/forbidden');
  });

  it('lets an ADMIN into ADMIN pages', async () => {
    signInAs('ADMIN');
    expect(await navigate('/operations/failed-events')).toBe('/operations/failed-events');
  });

  it('sends a signed-in user away from /login to their return route', async () => {
    signInAs('ADMIN');
    expect(await navigate('/login?returnUrl=%2Fshipments')).toBe('/shipments');
    expect(await navigate('/login?returnUrl=%2F%2Fevil.example')).toBe('/dashboard');
  });

  it('resumes the remembered route after login, once', async () => {
    signInAs('ADMIN');
    TestBed.inject(AuthSession).login('/shipments');
    expect(await navigate('/')).toBe('/shipments');
    expect(await navigate('/')).toBe('/dashboard');
  });
});
