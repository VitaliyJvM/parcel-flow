import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ShipmentLiveTracker } from '../../../core/live/shipment-live-tracker';
import { CopyButton } from '../../../shared/copy-button';
import { InstantDisplay, LocalDateDisplay } from '../../../shared/date-display';
import { formatRelative } from '../../../shared/dates';
import { CARRIER_META, isOperationalCarrier } from '../../../shared/domain';
import { ErrorPanel } from '../../../shared/error-panel';
import { StatusChip } from '../../../shared/status-chip';
import { AuthSession } from '../../../core/auth/auth-session';
import { SimulationPanel } from '../../simulator/simulation-panel';
import { NotificationList } from './notification-list';
import { TrackingTimeline } from './tracking-timeline';

const LIVE_LABEL = {
  idle: 'Not started',
  fast: 'Live · checking every few seconds',
  slow: 'Live · quiet, checking every 15 s',
  backoff: 'Reconnecting · backing off after errors',
  settled: 'Paused · delivered and quiet',
  paused: 'Paused while this tab is hidden',
  stopped: 'Stopped',
} as const;

@Component({
  selector: 'pf-shipment-detail-page',
  imports: [
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTabsModule,
    MatTooltipModule,
    StatusChip,
    InstantDisplay,
    LocalDateDisplay,
    CopyButton,
    ErrorPanel,
    TrackingTimeline,
    NotificationList,
    SimulationPanel,
  ],
  providers: [ShipmentLiveTracker],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shipment-detail-page.html',
  styleUrl: './shipment-detail-page.scss',
})
export class ShipmentDetailPage {
  protected readonly live = inject(ShipmentLiveTracker);
  protected readonly session = inject(AuthSession);

  /** Route parameter (withComponentInputBinding). */
  readonly shipmentId = input.required<string>();

  /** Set by the registration page via navigation state (falls back to history.state on reload). */
  protected readonly justRegistered =
    (inject(Router).currentNavigation()?.extras.state ??
      (globalThis.history?.state as Record<string, unknown> | null))?.['justRegistered'] === true;

  protected readonly shipment = this.live.shipment;
  protected readonly carrierMeta = CARRIER_META;
  protected readonly operational = computed(() => {
    const s = this.shipment();
    return s ? isOperationalCarrier(s.carrierCode) : true;
  });
  protected readonly liveLabel = computed(() => LIVE_LABEL[this.live.mode()]);
  protected readonly canResume = computed(() => {
    const mode = this.live.mode();
    return mode === 'settled' || mode === 'backoff';
  });
  protected readonly lastChecked = computed(() => {
    const at = this.live.lastCheckedAt();
    return at ? formatRelative(new Date(at).toISOString()) : null;
  });
  protected readonly eventCount = computed(() => this.live.events()?.total ?? null);
  protected readonly notificationCount = computed(() => this.live.notifications()?.total ?? null);

  /** Index of the selected tab; 0 = Tracking history. */
  protected readonly selectedTab = signal(0);
  /** Event the timeline should scroll to and focus once it is rendered; cleared when done. */
  protected readonly focusEventId = signal<string | null>(null);

  constructor() {
    effect(() => this.live.track(this.shipmentId()));
  }

  /** From a notification: open Tracking history and hand the event to the timeline. */
  protected showEvent(eventId: string): void {
    this.focusEventId.set(eventId);
    this.selectedTab.set(0);
  }
}
