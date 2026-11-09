import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  provideRecordingNavigation,
  RecordingNavigation,
  signInAs,
  signOutForTest,
} from '../testing/auth';
import { provideTestHttp } from '../testing/http';
import { App } from './app';

describe('App shell', () => {
  let navigation: RecordingNavigation;

  beforeEach(async () => {
    localStorage.clear();
    const recording = provideRecordingNavigation();
    navigation = recording.navigation;
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [...provideTestHttp(), provideRouter([]), recording.provider],
    }).compileComponents();
  });

  async function render() {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  const navLabels = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('mat-nav-list a')).map((a) => a.textContent?.trim());

  it('shows an ADMIN every section, the retailer picker, and who is signed in', async () => {
    signInAs('ADMIN');
    const { el } = await render();
    expect(navLabels(el)).toEqual([
      'space_dashboardOverview',
      'inventory_2Shipments',
      'add_boxRegister shipment',
      'reportFailed events',
      'terminalCarrier simulator',
      'infoAbout & security',
    ]);
    expect(el.textContent).toContain('Select retailer');
    expect(el.querySelector('[data-testid="user-role"]')?.textContent).toBe('ADMIN');
    // The unauthenticated-demo banner is gone.
    expect(el.querySelector('.pf-demo-banner')).toBeNull();
    expect(el.textContent).not.toContain('no authentication');
  });

  it('shows a RETAILER no admin sections and their assigned retailer, which is not a button', async () => {
    signInAs('RETAILER', 'alpha-goods');
    const { el } = await render();
    expect(navLabels(el)).toEqual([
      'space_dashboardOverview',
      'inventory_2Shipments',
      'add_boxRegister shipment',
      'infoAbout & security',
    ]);
    const assigned = el.querySelector('[data-testid="assigned-retailer"]');
    expect(assigned?.textContent).toContain('alpha-goods');
    expect(assigned?.tagName).not.toBe('BUTTON');
    expect(el.textContent).not.toContain('Select retailer');
    expect(el.querySelector('[data-testid="user-role"]')?.textContent).toBe('RETAILER');
  });

  it('renders no shell (navigation, toolbar) for a signed-out visitor', async () => {
    signOutForTest();
    const { el } = await render();
    expect(el.querySelector('mat-toolbar')).toBeNull();
    expect(el.querySelector('mat-nav-list')).toBeNull();
  });

  it('signs out through a top-level POST form carrying the CSRF token', async () => {
    signInAs('ADMIN');
    const { fixture, el } = await render();
    (el.querySelector('[data-testid="user-menu"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    const signOut = Array.from(
      document.querySelectorAll<HTMLButtonElement>('button[mat-menu-item]'),
    ).find((b) => b.textContent?.includes('Sign out'));
    signOut?.click();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/csrf')
      .flush({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'masked' });
    await fixture.whenStable();
    expect(navigation.posts).toEqual([{ action: '/logout', fields: { _csrf: 'masked' } }]);
  });
});
