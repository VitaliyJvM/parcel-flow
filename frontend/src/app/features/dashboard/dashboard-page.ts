import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { map, of } from 'rxjs';
import { toApiError } from '../../core/api/api-error';
import { FailedEventStatus, ShipmentStatus } from '../../core/api/models';
import { FailedEventsApi, ShipmentsApi } from '../../core/api/tracking-api';
import { AuthSession } from '../../core/auth/auth-session';
import { RetailerContext } from '../../core/retailer/retailer-context';
import { InstantDisplay } from '../../shared/date-display';
import {
  CARRIER_META,
  FAILED_EVENT_STATUS_META,
  FAILED_EVENT_STATUSES,
  SHIPMENT_STATUS_META,
  SHIPMENT_STATUSES,
} from '../../shared/domain';
import { ErrorPanel } from '../../shared/error-panel';
import { RetailerRequired } from '../../shared/retailer-required';
import { StatusChip } from '../../shared/status-chip';
import { CountState, loadCounts } from './counts';

const RECENT_COUNT = 5;

/** Moved out of the page body into info tooltips; the wording is unchanged in substance. */
export const COUNTS_NOTE =
  'Counts come from eight filtered list requests (at most three at a time), because the API has ' +
  'no aggregate endpoint. They are a snapshot, not a transaction: a shipment advancing while the ' +
  'counts load can be counted in two tiles or none.';
export const FAILED_NOTE =
  'Dead-lettered carrier events are not tied to a retailer in the API, so these counts cover all ' +
  'retailers and are shown to operators (ADMIN) only. Throughput, latency and backlog are in ' +
  'Grafana, not here.';

@Component({
  selector: 'pf-dashboard-page',
  imports: [
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    StatusChip,
    InstantDisplay,
    ErrorPanel,
    RetailerRequired,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard-page.html',
  styleUrl: './dashboard-page.scss',
})
export class DashboardPage {
  private readonly shipmentsApi = inject(ShipmentsApi);
  private readonly failedEventsApi = inject(FailedEventsApi);
  protected readonly retailer = inject(RetailerContext);
  protected readonly session = inject(AuthSession);

  protected readonly statuses = SHIPMENT_STATUSES;
  protected readonly statusMeta = SHIPMENT_STATUS_META;
  protected readonly failedStatuses = FAILED_EVENT_STATUSES;
  protected readonly failedMeta = FAILED_EVENT_STATUS_META;
  protected readonly carrierMeta = CARRIER_META;

  /** Bumped by "Refresh" to re-run every resource. */
  private readonly refreshToken = signal(0);

  /** Recent shipments; its `totalElements` doubles as the retailer's total count. */
  protected readonly recent = rxResource({
    params: () => ({ retailerId: this.retailer.retailerId(), token: this.refreshToken() }),
    stream: ({ params }) =>
      params.retailerId === null
        ? of(null)
        : this.shipmentsApi.listForRetailer(params.retailerId, { page: 0, size: RECENT_COUNT }),
  });
  protected readonly recentError = computed(() => {
    const e = this.recent.error();
    return e ? toApiError(e) : null;
  });

  protected readonly statusCounts = rxResource({
    params: () => ({ retailerId: this.retailer.retailerId(), token: this.refreshToken() }),
    stream: ({ params: { retailerId } }) =>
      retailerId === null
        ? of(null)
        : loadCounts<ShipmentStatus>(SHIPMENT_STATUSES, (status) =>
            this.shipmentsApi
              .listForRetailer(retailerId, { page: 0, size: 1 }, status)
              .pipe(map((page) => page.totalElements)),
          ),
  });

  /** ADMIN only: failed events span every retailer, and the endpoint answers 403 to anyone else. */
  protected readonly failedCounts = rxResource({
    params: () => ({ token: this.refreshToken(), admin: this.session.isAdmin() }),
    stream: ({ params }) =>
      !params.admin
        ? of(null)
        : loadCounts<FailedEventStatus>(FAILED_EVENT_STATUSES, (status) =>
            this.failedEventsApi.list({ page: 0, size: 1 }, status).pipe(map((page) => page.totalElements)),
          ),
  });

  /** "—" on failure (like the status tiles), "…" only while actually loading. */
  protected readonly totalText = computed(() => {
    if (this.recent.error()) return '—';
    const page = this.recent.value();
    return page ? page.totalElements.toLocaleString() : '…';
  });

  /** Statuses with a non-zero count, for the proportional bar. */
  protected readonly breakdown = computed(() => {
    const counts = this.statusCounts.value();
    if (!counts) return [];
    const ok = SHIPMENT_STATUSES.map((s) => ({ status: s, c: counts[s] })).filter(
      (x): x is { status: ShipmentStatus; c: { state: 'ok'; count: number } } =>
        x.c.state === 'ok' && x.c.count > 0,
    );
    const total = ok.reduce((sum, x) => sum + x.c.count, 0);
    return ok.map((x) => ({
      status: x.status,
      count: x.c.count,
      share: total ? x.c.count / total : 0,
      percent: total ? Math.round((x.c.count / total) * 100) : 0,
    }));
  });

  /** Text alternative for the distribution bar. */
  protected readonly breakdownLabel = computed(() =>
    this.breakdown()
      .map((b) => `${SHIPMENT_STATUS_META[b.status].label} ${b.count} (${b.percent}%)`)
      .join(', '),
  );

  protected readonly countsNote = COUNTS_NOTE;
  protected readonly failedNote = FAILED_NOTE;

  protected isZero(state: CountState | undefined): boolean {
    return state?.state === 'ok' && state.count === 0;
  }

  protected readonly partialFailure = computed(() => {
    const counts = this.statusCounts.value();
    return counts ? Object.values<CountState>(counts).some((c) => c.state === 'error') : false;
  });

  protected count(state: CountState | undefined): string {
    if (!state || state.state === 'loading') return '…';
    return state.state === 'ok' ? state.count.toLocaleString() : '—';
  }

  protected refresh(): void {
    this.refreshToken.update((n) => n + 1);
  }
}
