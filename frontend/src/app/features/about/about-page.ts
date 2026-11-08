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
        <h1 class="pf-title">About this demo</h1>
        <p class="pf-subtitle">
          ParcelFlow is an independent portfolio project. Carriers are fictional.
        </p>
      </div>
    </header>

    <div class="pf-callout pf-callout-warning" role="note">
      <mat-icon aria-hidden="true">warning</mat-icon>
      <div class="pf-callout-body">
        <strong>Local demo — no authentication.</strong> This build is meant for
        <strong>localhost only</strong>. Docker Compose publishes every port on 127.0.0.1, and the
        nginx in front of this UI forwards only <code>/api/</code>.
      </div>
    </div>

    <div class="pf-about-grid">
      <section class="pf-card" aria-labelledby="limits-heading">
        <h2 id="limits-heading">Security limitations</h2>
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
      </section>

      <div class="pf-about-side">
        <section class="pf-card" aria-labelledby="before-heading">
          <h2 id="before-heading">Required before any public deployment</h2>
          <ol class="pf-steps">
            <li>Add authentication to the tracking service.</li>
            <li>Enforce retailer scoping in the backend, from the authenticated user, not the URL.</li>
            <li>Protect <code>/api/admin/**</code> with an operator role and audit manual retries.</li>
            <li>Move actuator to a management port that is not publicly routed.</li>
            <li>Put Grafana and Prometheus behind a login.</li>
            <li>Never publish PostgreSQL, Redis or Kafka ports; add credentials and TLS.</li>
          </ol>
          <p class="pf-muted pf-small">
            Details and rationale: <code>docs/analysis/angular-frontend-implementation.md</code>, §9.
          </p>
        </section>

        <section class="pf-card" aria-labelledby="ui-heading">
          <h2 id="ui-heading">What the UI does and does not do</h2>
          <ul class="pf-plain">
            <li>Uses only the public REST API, through same-origin relative paths.</li>
            <li>
              Polls for live updates (no WebSockets or SSE). It watches event and notification
              counts as well as the shipment version, so late, superseded scans still appear.
            </li>
            <li>
              Shows notification <em>records</em>. Nothing is emailed or texted; every record stays
              <code>PENDING</code>.
            </li>
            <li>
              Builds the carrier simulator's <code>docker compose run</code> command; it never
              runs anything.
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
  protected readonly limitations: Limitation[] = [
    {
      icon: 'lock_open',
      title: 'No authentication',
      text:
        'The tracking service has no login and no Spring Security. Every endpoint, including ' +
        'POST /api/admin/failed-events/{id}/retry, accepts anonymous requests.',
    },
    {
      icon: 'storefront',
      title: 'No retailer isolation',
      text:
        "The retailer selector is a view filter. Anyone can read any retailer's shipments by " +
        'changing the ID in the URL.',
    },
    {
      icon: 'visibility_off',
      title: 'Hiding UI is not authorization',
      text: 'Disabled or hidden buttons here do not stop the same request being sent with curl.',
    },
    {
      icon: 'key',
      title: 'Shipment IDs act like bearer tokens',
      text: 'Whoever holds a shipment ID can read its status, history and notification records.',
    },
    {
      icon: 'monitoring',
      title: 'Grafana has anonymous Admin access',
      text: 'Port 3000, for convenience on a laptop. Prometheus (port 9090) has no login either.',
    },
  ];
}
