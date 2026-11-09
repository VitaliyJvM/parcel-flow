import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

interface Limitation {
  icon: string;
  title: string;
  text: string;
}

@Component({
  selector: 'pf-about-page',
  imports: [MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="pf-page-header">
      <div>
        <h1 class="pf-title">About & security</h1>
        <p class="pf-subtitle">
          ParcelFlow is an independent portfolio project. Carriers are fictional.
        </p>
      </div>
    </header>

    <div class="pf-callout pf-callout-info" role="note">
      <mat-icon aria-hidden="true">verified_user</mat-icon>
      <div class="pf-callout-body">
        <strong>Signed in through Keycloak (OpenID Connect).</strong> The tracking service is this
        UI's backend-for-frontend: it runs the login, keeps every token on the server, and gives the
        browser only an HttpOnly session cookie. Every API request is authorized on the server.
      </div>
    </div>

    <div class="pf-about-grid">
      <section class="pf-card" aria-labelledby="model-heading">
        <h2 id="model-heading">How access works</h2>
        <ul class="pf-limits">
          @for (item of model; track item.title) {
            <li>
              <mat-icon aria-hidden="true">{{ item.icon }}</mat-icon>
              <div>
                <h3>{{ item.title }}</h3>
                <p>{{ item.text }}</p>
              </div>
            </li>
          }
        </ul>
      </section>

      <div class="pf-about-side">
        <section class="pf-card" aria-labelledby="limits-heading">
          <h2 id="limits-heading">Known limitations of this local build</h2>
          <ul class="pf-limits">
            @for (item of limitations; track item.title) {
              <li>
                <mat-icon aria-hidden="true">{{ item.icon }}</mat-icon>
                <div>
                  <h3>{{ item.title }}</h3>
                  <p>{{ item.text }}</p>
                </div>
              </li>
            }
          </ul>
          <p class="pf-muted pf-small">Details: <code>docs/security.md</code> in the repository.</p>
        </section>

        <section class="pf-card" aria-labelledby="ui-heading">
          <h2 id="ui-heading">What the UI does and does not do</h2>
          <ul class="pf-plain">
            <li>
              Uses only same-origin relative paths: the REST API and the login/logout endpoints.
            </li>
            <li>
              Polls for live updates (no WebSockets or SSE). It watches event and notification
              counts as well as the shipment version, so late, superseded scans still appear.
            </li>
            <li>
              Shows notification <em>records</em>. Nothing is emailed or texted; every record stays
              <code>PENDING</code>.
            </li>
            <li>
              Builds the carrier simulator's <code>docker compose run</code> command for operators;
              it never runs anything.
            </li>
            <li>
              Only SWIFTPOST and PACIFICA have event normalizers; NORDEX and METROLINK events are
              dead-lettered.
            </li>
            <li>
              Fonts are self-hosted (licenses at
              <a href="/licenses/fonts/README.txt">/licenses/fonts/</a>); no external requests.
            </li>
          </ul>
        </section>
      </div>
    </div>
  `,
  styles: `
    .pf-about-grid {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1099.98px) {
      .pf-about-grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .pf-limits {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .pf-limits li {
      display: flex;
      gap: 12px;
      padding: 12px 0;
      border-top: 1px solid var(--mat-sys-outline-variant);
    }
    .pf-limits li:first-child {
      border-top: none;
      padding-top: 0;
    }
    .pf-limits mat-icon {
      flex: none;
      color: var(--mat-sys-on-surface-variant);
    }
    .pf-limits h3 {
      font: var(--mat-sys-title-small);
      margin: 0 0 2px;
    }
    .pf-limits p {
      margin: 0;
    }
    .pf-steps,
    .pf-plain {
      margin: 0;
      padding-left: 20px;
    }
    .pf-steps li,
    .pf-plain li {
      margin-bottom: 6px;
    }
    .pf-small {
      font: var(--mat-sys-body-small);
      margin: 12px 0 0;
    }
  `,
})
export class AboutPage {
  protected readonly model: Limitation[] = [
    {
      icon: 'login',
      title: 'OpenID Connect login, tokens stay on the server',
      text:
        'Sign-in is the authorization code flow with PKCE, completed by the tracking service as a ' +
        'confidential client. Access, refresh and ID tokens live in the server-side session; the ' +
        'browser holds an HttpOnly, SameSite=Lax session cookie and nothing in local or session ' +
        'storage.',
    },
    {
      icon: 'badge',
      title: 'Two roles: ADMIN and RETAILER',
      text:
        'ADMIN sees every retailer, failed-event diagnostics and retry, and the simulator command ' +
        'builder. RETAILER sees only their own retailer. Roles come from Keycloak.',
    },
    {
      icon: 'storefront',
      title: 'Retailer isolation is enforced by the backend',
      text:
        "A retailer's retailer is assigned to their Keycloak account by an administrator. The " +
        'backend checks it on every request: another retailer\'s shipment answers "not found", ' +
        'exactly like one that does not exist, and a forged retailer ID is refused.',
    },
    {
      icon: 'shield',
      title: 'CSRF protection on every change',
      text:
        'State-changing requests (registration, retry, logout) carry a CSRF token bound to the ' +
        'server-side session. Hiding a button is never what stops a request.',
    },
    {
      icon: 'logout',
      title: 'Logout ends both sessions',
      text:
        'Signing out invalidates the ParcelFlow session and ends the Keycloak single sign-on ' +
        'session, so the next sign-in asks for credentials again.',
    },
  ];

  protected readonly limitations: Limitation[] = [
    {
      icon: 'construction',
      title: 'Keycloak runs in development mode',
      text:
        'Plain HTTP on localhost, an in-container database, and a realm re-imported on every ' +
        'container recreation. Fine for a laptop; not a production identity provider.',
    },
    {
      icon: 'http',
      title: 'HTTP on localhost',
      text:
        'Locally the session cookie is not marked Secure because there is no TLS. The production ' +
        'configuration requires HTTPS and a Secure, __Host- prefixed cookie.',
    },
    {
      icon: 'dns',
      title: 'Sessions live in one service instance',
      text:
        'Servlet sessions are in memory: a restart signs everyone out, and running several ' +
        'instances would need sticky sessions or a shared session store.',
    },
    {
      icon: 'monitoring',
      title: 'Infrastructure around the API is not secured',
      text:
        'Grafana has anonymous Admin access (port 3000); Prometheus, Redis and Redpanda have no ' +
        'login. Every port is bound to 127.0.0.1 for that reason.',
    },
  ];
}
