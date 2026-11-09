import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AuthSession } from '../../core/auth/auth-session';

/**
 * Where Keycloak returns the browser after RP-initiated logout (`post_logout_redirect_uri`).
 *
 * Deliberately does not redirect anywhere on its own: an automatic hop back to the login flow is
 * exactly the "logged straight back in" loop logout must not cause. "Sign in again" also forces
 * Keycloak's login prompt, in case the end-session redirect did not complete.
 */
@Component({
  selector: 'pf-signed-out-page',
  imports: [MatButtonModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main id="main" class="pf-auth-page" tabindex="-1">
      <section class="pf-card pf-auth-card" aria-labelledby="signed-out-heading">
        <div class="pf-auth-brand">
          <mat-icon aria-hidden="true">deployed_code</mat-icon>
          <span>ParcelFlow</span>
        </div>
        <h1 id="signed-out-heading" class="pf-title">You have signed out</h1>
        <p class="pf-muted">
          Your ParcelFlow session and your Keycloak single sign-on session have both ended.
        </p>
        <button mat-flat-button type="button" (click)="signIn()" [disabled]="redirecting()">
          <mat-icon aria-hidden="true">login</mat-icon>
          Sign in again
        </button>
      </section>
    </main>
  `,
  styleUrl: './auth-pages.scss',
})
export class SignedOutPage implements OnInit {
  private readonly session = inject(AuthSession);
  protected readonly redirecting = signal(false);

  ngOnInit(): void {
    // Re-reads the (now anonymous) session, which also makes the backend issue a fresh CSRF
    // cookie for whatever this browser does next.
    void this.session.refresh();
  }

  protected signIn(): void {
    this.redirecting.set(true);
    this.session.login(null, { forcePrompt: true });
  }
}
