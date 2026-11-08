import type { ShipmentStatus, TrackingEventResponse } from '../../../core/api/models';

/**
 * `eventTime`: what the carrier observed, in the order the API returns it (event time, then
 * sequence number). `received`: the order ParcelFlow ingested the events, which is the order the
 * ordering rules were actually applied in — the view that explains SUPERSEDED rows.
 */
export type TimelineOrder = 'eventTime' | 'received';

function time(value: string): number {
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? 0 : ms;
}

export function orderTimeline(
  events: readonly TrackingEventResponse[],
  order: TimelineOrder,
): TrackingEventResponse[] {
  const sorted = [...events];
  if (order === 'eventTime') {
    // Re-sort defensively with the API's own documented order, so pagination joins and future
    // backend changes cannot silently reorder the view.
    sorted.sort(
      (a, b) => time(a.eventTime) - time(b.eventTime) || a.sequenceNumber - b.sequenceNumber,
    );
  } else {
    sorted.sort(
      (a, b) => time(a.receivedAt) - time(b.receivedAt) || a.sequenceNumber - b.sequenceNumber,
    );
  }
  return sorted;
}

/**
 * The event that put the shipment into its current status: the most recently *received* APPLIED
 * event, because every APPLIED event overwrote the status when it was processed. Returned only if
 * it agrees with `currentStatus`, which comes from the shipment resource and is authoritative —
 * the timeline never decides the status.
 */
export function findStatusSource(
  events: readonly TrackingEventResponse[],
  currentStatus: ShipmentStatus,
): string | null {
  let source: TrackingEventResponse | null = null;
  for (const event of events) {
    if (event.processingStatus !== 'APPLIED') continue;
    if (
      source === null ||
      time(event.receivedAt) > time(source.receivedAt) ||
      (time(event.receivedAt) === time(source.receivedAt) &&
        event.sequenceNumber > source.sequenceNumber)
    ) {
      source = event;
    }
  }
  return source !== null && source.normalizedEventType === currentStatus ? source.eventId : null;
}

export interface TimelineSummary {
  total: number;
  applied: number;
  superseded: number;
  /** True when the chronologically last row is not the shipment's current status. */
  lastRowDiffers: boolean;
}

export function summarizeTimeline(
  events: readonly TrackingEventResponse[],
  currentStatus: ShipmentStatus,
): TimelineSummary {
  const ordered = orderTimeline(events, 'eventTime');
  const last = ordered.at(-1);
  return {
    total: events.length,
    applied: events.filter((e) => e.processingStatus === 'APPLIED').length,
    superseded: events.filter((e) => e.processingStatus === 'SUPERSEDED').length,
    lastRowDiffers: last !== undefined && last.normalizedEventType !== currentStatus,
  };
}
