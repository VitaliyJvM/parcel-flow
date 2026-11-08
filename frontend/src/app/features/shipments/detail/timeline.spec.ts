import { trackingEvent } from '../../../../testing/http';
import { findStatusSource, orderTimeline, summarizeTimeline } from './timeline';

/**
 * An OUT_OF_ORDER-style history: sequence 3 (OUT_FOR_DELIVERY) arrived before sequence 2
 * (IN_TRANSIT). Sequence 2 is stored as SUPERSEDED and must not become the current status.
 */
function outOfOrderHistory() {
  const created = trackingEvent({
    eventId: 'e1',
    sequenceNumber: 1,
    normalizedEventType: 'LABEL_CREATED',
    eventTime: '2026-08-01T08:00:00Z',
    receivedAt: '2026-08-03T09:00:00Z',
  });
  const ofd = trackingEvent({
    eventId: 'e3',
    sequenceNumber: 3,
    normalizedEventType: 'OUT_FOR_DELIVERY',
    eventTime: '2026-08-02T08:00:00Z',
    receivedAt: '2026-08-03T09:00:01Z',
  });
  const transitLate = trackingEvent({
    eventId: 'e2',
    sequenceNumber: 2,
    normalizedEventType: 'IN_TRANSIT',
    eventTime: '2026-08-01T20:00:00Z',
    receivedAt: '2026-08-03T09:00:02Z',
    processingStatus: 'SUPERSEDED',
  });
  return { created, ofd, transitLate, all: [created, transitLate, ofd] };
}

describe('timeline ordering', () => {
  it('orders by carrier event time, then sequence number', () => {
    const { all } = outOfOrderHistory();
    expect(orderTimeline(all, 'eventTime').map((e) => e.eventId)).toEqual(['e1', 'e2', 'e3']);
  });

  it('orders by arrival to show why an event was superseded', () => {
    const { all } = outOfOrderHistory();
    expect(orderTimeline(all, 'received').map((e) => e.eventId)).toEqual(['e1', 'e3', 'e2']);
  });

  it('breaks event-time ties by sequence number', () => {
    const a = trackingEvent({ eventId: 'a', sequenceNumber: 5, eventTime: '2026-08-01T00:00:00Z' });
    const b = trackingEvent({ eventId: 'b', sequenceNumber: 4, eventTime: '2026-08-01T00:00:00Z' });
    expect(orderTimeline([a, b], 'eventTime').map((e) => e.eventId)).toEqual(['b', 'a']);
  });

  it('does not mutate its input', () => {
    const { all } = outOfOrderHistory();
    const copy = [...all];
    orderTimeline(all, 'received');
    expect(all).toEqual(copy);
  });
});

describe('status source and summary', () => {
  it('identifies the last-received APPLIED event as the source of the current status', () => {
    const { all } = outOfOrderHistory();
    expect(findStatusSource(all, 'OUT_FOR_DELIVERY')).toBe('e3');
  });

  it('never attributes the status to a SUPERSEDED event', () => {
    const { all } = outOfOrderHistory();
    // Even if a caller passes the superseded event's type, it is not a source.
    expect(findStatusSource(all, 'IN_TRANSIT')).toBeNull();
  });

  it('returns null when the shipment status disagrees with every applied event', () => {
    const { all } = outOfOrderHistory();
    expect(findStatusSource(all, 'DELIVERED')).toBeNull();
  });

  it('counts applied/superseded and flags when the last row is not the current status', () => {
    const delivered = trackingEvent({
      eventId: 'd',
      sequenceNumber: 8,
      normalizedEventType: 'DELIVERED',
      eventTime: '2026-08-04T10:00:00Z',
    });
    // A backfilled scan with an *earlier* sequence but a *later* event time: last row, superseded.
    const backfill = trackingEvent({
      eventId: 'late',
      sequenceNumber: 6,
      normalizedEventType: 'IN_TRANSIT',
      eventTime: '2026-08-05T10:00:00Z',
      processingStatus: 'SUPERSEDED',
    });
    const summary = summarizeTimeline([delivered, backfill], 'DELIVERED');
    expect(summary).toEqual({ total: 2, applied: 1, superseded: 1, lastRowDiffers: true });
    expect(summarizeTimeline([delivered], 'DELIVERED').lastRowDiffers).toBe(false);
  });
});
