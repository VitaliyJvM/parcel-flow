import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ShipmentStatus, TrackingEventResponse } from '../../../core/api/models';
import { InstantDisplay } from '../../../shared/date-display';
import { SHIPMENT_STATUS_META } from '../../../shared/domain';
import { StatusChip, Tag } from '../../../shared/status-chip';
import { findStatusSource, orderTimeline, summarizeTimeline, TimelineOrder } from './timeline';

/**
 * `compact`: one scannable row per event (status, processing outcome, sequence, event time,
 * description, location); diagnostics are behind a per-event disclosure.
 * `detailed`: every event shows its diagnostics, i.e. everything the API returns.
 */
export type TimelineView = 'compact' | 'detailed';

export const SUPERSEDED_EXPLANATION =
  'Stored, but did not change the status: an equal or higher sequence number had already been ' +
  'applied, or the shipment was already delivered.';

@Component({
  selector: 'pf-tracking-timeline',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatTooltipModule,
    StatusChip,
    Tag,
    InstantDisplay,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './tracking-timeline.html',
  styleUrl: './tracking-timeline.scss',
})
export class TrackingTimeline {
  readonly events = input.required<readonly TrackingEventResponse[]>();
  readonly currentStatus = input.required<ShipmentStatus>();
  readonly truncated = input(false);
  /** Event to scroll to and focus (e.g. requested from a notification). */
  readonly focusEventId = input<string | null>(null);
  /** Emitted once the requested event has been scrolled to and focused. */
  readonly eventFocused = output<string>();

  protected readonly order = signal<TimelineOrder>('eventTime');
  protected readonly view = signal<TimelineView>('compact');
  /** Events whose diagnostics are open in compact view. Survives polling refreshes (keyed by id). */
  private readonly expanded = signal<ReadonlySet<string>>(new Set());

  protected readonly ordered = computed(() => orderTimeline(this.events(), this.order()));
  protected readonly statusSource = computed(() =>
    findStatusSource(this.events(), this.currentStatus()),
  );
  protected readonly summary = computed(() =>
    summarizeTimeline(this.events(), this.currentStatus()),
  );
  protected readonly statusMeta = SHIPMENT_STATUS_META;
  protected readonly supersededExplanation = SUPERSEDED_EXPLANATION;
  /** The last requested event stays highlighted (a cue besides keyboard focus) until the next request. */
  protected readonly highlighted = signal<string | null>(null);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  constructor() {
    // Runs after Angular has rendered this component's DOM, so the row exists when we look for
    // it; no setTimeout. Re-runs when the request or the rendered list changes, which also covers
    // a request for an event that arrives with a later poll.
    afterRenderEffect(() => {
      const eventId = this.focusEventId();
      this.ordered();
      if (!eventId) {
        return;
      }
      const rows = this.host.nativeElement.querySelectorAll<HTMLElement>('li.pf-event');
      const row = Array.from(rows).find((li) => li.id === `event-${eventId}`);
      if (!row) {
        return;
      }
      const reduceMotion = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
      row.scrollIntoView?.({ block: 'center', behavior: reduceMotion ? 'auto' : 'smooth' });
      row.focus({ preventScroll: true });
      this.highlighted.set(eventId);
      this.eventFocused.emit(eventId);
    });
  }

  protected setOrder(value: TimelineOrder): void {
    this.order.set(value);
  }

  protected setView(value: TimelineView): void {
    this.view.set(value);
  }

  protected isOpen(eventId: string): boolean {
    return this.view() === 'detailed' || this.expanded().has(eventId);
  }

  protected toggle(eventId: string): void {
    this.expanded.update((current) => {
      const next = new Set(current);
      if (!next.delete(eventId)) {
        next.add(eventId);
      }
      return next;
    });
  }
}
