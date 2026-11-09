import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AuthSession } from '../../core/auth/auth-session';

interface Notice {
  kind: 'error' | 'warning' | 'info';
  icon: string;
  title: string;
  text: string;
}

/**
 * The entry point. ParcelFlow has no password form of its own: "Sign in" hands the browser to the
 * backend, which runs the OIDC authorization code flow with Keycloak and comes back with a session.
 *
 * Query parameters (all set by the backend or the app, all enumerated — nothing is echoed):
 * `error` = not_authorized | login_failed | idp_unavailable | unavailable, `reason` = expired,
 * `returnUrl` = the route to resume.
 */
@Component({
  selector: 'pf-login-page',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main id="main" class="pf-auth-page" tabindex="-1">
      <section class="pf-card pf-auth-card" aria-labelledby="login-heading">
        @if (redirecting()) {
          <mat-progress-bar mode="indeterminate" aria-label="Redirecting to sign-in" />
        }
        <div class="pf-auth-brand">
          <mat-icon aria-hidden="true">deployed_code</mat-icon>
          <span>ParcelFlow</span>
        </div>
        <h1 id="login-heading" class="pf-title">Sign in</h1>

        @if (notice(); as n) {
          <div
            class="pf-callout"
            [class]="'pf-callout-' + n.kind"
            role="alert"
            data-testid="login-notice"
          >
            <mat-icon aria-hidden="true">{{ n.icon }}</mat-icon>
            <div class="pf-callout-body">
              <strong>{{ n.title }}</strong>
              <p>{{ n.text }}</p>
            </div>
          </div>
        }

        <p class="pf-muted">
          You will be taken to the ParcelFlow identity provider (Keycloak) and brought back here.
          Your password is never entered into this application.
        </p>

        <button mat-flat-button type="button" (click)="signIn()" [disabled]="redirecting()">
          <mat-icon aria-hidden="true">login</mat-icon>
          Sign in with Keycloak
        </button>
      </section>
    </main>
  `,
  styleUrl: './auth-pages.scss',
})
export class LoginPage {
  private readonly session = inject(AuthSession);

  readonly error = input<string | undefined>();
  readonly reason = input<string | undefined>();
  readonly returnUrl = input<string | undefined>();

  protected readonly redirecting = signal(false);

  protected readonly notice = computed<Notice | null>(() => {
    switch (this.error()) {
      case 'not_authorized':
        return {
          kind: 'error',
          icon: 'block',
          title: 'This account cannot use ParcelFlow',
          text:
            'Keycloak signed you in, but the account has no ParcelFlow role, or is a retailer account ' +
            'without exactly one retailer assigned. Ask an administrator to fix the account, or sign in ' +
            'as someone else.',
        };
      case 'login_failed':
        return {
          kind: 'error',
          icon: 'error',
          title: 'Sign-in did not complete',
          text: 'The sign-in response could not be verified or was cancelled. Please try again.',
        };
      case 'idp_unavailable':
        return {
          kind: 'warning',
          icon: 'cloud_off',
          title: 'Identity provider unavailable',
          text: 'Keycloak is not reachable right now, so nobody can sign in. It may still be starting.',
        };
      case 'unavailable':
        return {
          kind: 'warning',
          icon: 'cloud_off',
          title: 'Service unreachable',
          text: 'The tracking service did not respond. Check that the backend is running, then try again.',
        };
    }
    if (this.reason() === 'expired' || this.session.signedOutReason() === 'expired') {
      return {
        kind: 'info',
        icon: 'schedule',
        title: 'Your session has ended',
        text: 'For your security you were signed out. Sign in again to continue where you left off.',
      };
    }
    return null;
  });

  protected signIn(): void {
    this.redirecting.set(true);
    // After a refused or failed login, make Keycloak ask again rather than silently reusing the
    // same SSO session (which would just fail the same way).
    const forcePrompt = this.error() === 'not_authorized' || this.error() === 'login_failed';
    this.session.login(this.returnUrl() ?? null, { forcePrompt });
  }
}
