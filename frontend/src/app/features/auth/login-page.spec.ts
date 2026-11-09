import { TestBed } from '@angular/core/testing';
import { provideRecordingNavigation, RecordingNavigation } from '../../../testing/auth';
import { provideTestHttp } from '../../../testing/http';
import { AuthSession } from '../../core/auth/auth-session';
import { LoginPage } from './login-page';

describe('LoginPage', () => {
  let navigation: RecordingNavigation;

  async function render(inputs: Record<string, string> = {}) {
    sessionStorage.clear();
    const recording = provideRecordingNavigation();
    navigation = recording.navigation;
    TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [...provideTestHttp(), recording.provider],
    });
    const fixture = TestBed.createComponent(LoginPage);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  function clickSignIn(el: HTMLElement) {
    Array.from(el.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Sign in with Keycloak'))
      ?.click();
  }

  it('signs in through the backend, never with a password form of its own', async () => {
    const { el } = await render({ returnUrl: '/shipments' });
    expect(el.querySelector('input[type="password"]')).toBeNull();
    expect(el.querySelector('[data-testid="login-notice"]')).toBeNull();
    clickSignIn(el);
    expect(navigation.assigned).toEqual(['/oauth2/authorization/keycloak']);
    expect(TestBed.inject(AuthSession).consumeReturnUrl()).toBe('/shipments');
  });

  it.each([
    ['not_authorized', 'This account cannot use ParcelFlow'],
    ['login_failed', 'Sign-in did not complete'],
    ['idp_unavailable', 'Identity provider unavailable'],
    ['unavailable', 'Service unreachable'],
  ])('explains error=%s without echoing anything from the URL', async (error, title) => {
    const { el } = await render({ error });
    expect(el.querySelector('[data-testid="login-notice"]')?.textContent).toContain(title);
  });

  it('ignores an unknown error code rather than rendering it', async () => {
    const { el } = await render({ error: '<img src=x onerror=alert(1)>' });
    expect(el.querySelector('[data-testid="login-notice"]')).toBeNull();
    expect(el.innerHTML).not.toContain('onerror');
  });

  it('tells the user their session ended', async () => {
    const { el } = await render({ reason: 'expired' });
    expect(el.querySelector('[data-testid="login-notice"]')?.textContent).toContain(
      'Your session has ended',
    );
  });

  it('after a refused login, asks Keycloak to prompt again instead of reusing the SSO session', async () => {
    const { el } = await render({ error: 'not_authorized' });
    clickSignIn(el);
    expect(navigation.assigned).toEqual(['/oauth2/authorization/keycloak?prompt=login']);
  });
});
