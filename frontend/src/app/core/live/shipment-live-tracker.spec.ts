import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BehaviorSubject } from 'rxjs';
import {
  countPage,
  notification,
  page,
  problem,
  provideTestHttp,
  shipment,
  SHIPMENT_ID,
  trackingEvent,
} from '../../../testing/http';
import type { NotificationResponse, ShipmentResponse, TrackingEventResponse } from '../api/models';
import { PAGE_VISIBLE } from './page-visibility';
import { POLL_CONFIG, PollConfig } from './poll-policy';
import { ShipmentLiveTracker } from './shipment-live-tracker';

const CONFIG: PollConfig = {
  fastMs: 1_000,
  slowMs: 5_000,
  activeWindowMs: 20_000,
  deliveredGraceMs: 3_000,
  maxErrorBackoffMs: 20_000,
};

const SHIPMENT_URL = `/api/shipments/${SHIPMENT_ID}`;
const EVENTS_URL = `${SHIPMENT_URL}/events`;
const NOTIFICATIONS_URL = `${SHIPMENT_URL}/notifications`;

describe('ShipmentLiveTracker', () => {
  let http: HttpTestingController;
  let visible: BehaviorSubject<boolean>;
  let tracker: ShipmentLiveTracker;

  beforeEach(() => {
    vi.useFakeTimers();
    visible = new BehaviorSubject(true);
    TestBed.configureTestingModule({
      providers: [
        ...provideTestHttp(),
        ShipmentLiveTracker,
        { provide: PAGE_VISIBLE, useValue: visible },
        { provide: POLL_CONFIG, useValue: CONFIG },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    tracker = TestBed.inject(ShipmentLiveTracker);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const isProbe = (url: string) => (r: { url: string; params: { get(n: string): string | null } }) =>
    r.url === url && r.params.get('size') === '1';
  const isFull = (url: string) => (r: { url: string; params: { get(n: string): string | null } }) =>
    r.url === url && r.params.get('size') !== '1';

  /** Answers one tick's three probes. */
  function answerProbes(s: ShipmentResponse | TestRequestError, eventTotal: number, notificationTotal: number) {
    const shipmentReq = http.expectOne(SHIPMENT_URL);
    if ('error' in s) {
      shipmentReq.flush(s.error.body, s.error.opts);
    } else {
      shipmentReq.flush(s);
    }
    http.expectOne(isProbe(EVENTS_URL)).flush(countPage(eventTotal));
    http.expectOne(isProbe(NOTIFICATIONS_URL)).flush(countPage(notificationTotal));
  }

  function answerEvents(events: TrackingEventResponse[]): TestRequest {
    const req = http.expectOne(isFull(EVENTS_URL));
    expect(req.request.params.get('size')).toBe('200');
    req.flush(page(events));
    return req;
  }

  function answerNotifications(items: NotificationResponse[]): void {
    http.expectOne(isFull(NOTIFICATIONS_URL)).flush(page(items));
  }

  interface TestRequestError {
    error: ReturnType<typeof problem>;
  }

  async function initialLoad(s = shipment({ version: 1, currentStatus: 'IN_TRANSIT' }), events = [trackingEvent()]) {
    tracker.track(SHIPMENT_ID);
    answerProbes(s, events.length, 0);
    answerEvents(events);
    answerNotifications([]);
    await vi.advanceTimersByTimeAsync(0);
  }

  it('loads shipment, full history and notifications on start', async () => {
    await initialLoad();
    expect(tracker.shipment()?.version).toBe(1);
    expect(tracker.events()?.items).toHaveLength(1);
    expect(tracker.notifications()?.items).toEqual([]);
    expect(tracker.mode()).toBe('fast');
  });

  it('refreshes history when a SUPERSEDED event arrives without a version change', async () => {
    const applied = trackingEvent({ sequenceNumber: 5, normalizedEventType: 'OUT_FOR_DELIVERY' });
    await initialLoad(shipment({ version: 3, currentStatus: 'OUT_FOR_DELIVERY' }), [applied]);

    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    // Same version, same status — but one more event exists.
    answerProbes(shipment({ version: 3, currentStatus: 'OUT_FOR_DELIVERY' }), 2, 0);
    const late = trackingEvent({ sequenceNumber: 2, processingStatus: 'SUPERSEDED' });
    answerEvents([applied, late]);
    await vi.advanceTimersByTimeAsync(0);

    expect(tracker.shipment()?.version).toBe(3);
    expect(tracker.events()?.items.map((e) => e.processingStatus)).toEqual(['APPLIED', 'SUPERSEDED']);
    expect(tracker.lastChangedAt()).not.toBeNull();
  });

  it('does not re-download history when nothing changed', async () => {
    await initialLoad();
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    answerProbes(shipment({ version: 1, currentStatus: 'IN_TRANSIT' }), 1, 0);
    http.expectNone(isFull(EVENTS_URL));
    http.expectNone(isFull(NOTIFICATIONS_URL));
    await vi.advanceTimersByTimeAsync(0);
    expect(tracker.lastChangedAt()).toBeNull();
  });

  it('refreshes notifications independently of history', async () => {
    await initialLoad();
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    answerProbes(shipment({ version: 1, currentStatus: 'IN_TRANSIT' }), 1, 1);
    http.expectNone(isFull(EVENTS_URL));
    answerNotifications([notification()]);
    await vi.advanceTimersByTimeAsync(0);
    expect(tracker.notifications()?.items).toHaveLength(1);
  });

  it('never overlaps requests: the next tick waits for the previous one', async () => {
    await initialLoad();
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    // Probes are in flight and unanswered; time passes well beyond several intervals.
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs * 10);
    expect(http.match(SHIPMENT_URL)).toHaveLength(1);
    http.match(isProbe(EVENTS_URL));
    http.match(isProbe(NOTIFICATIONS_URL));
  });

  it('ignores a shipment snapshot older than the one shown', async () => {
    await initialLoad(shipment({ version: 4, currentStatus: 'OUT_FOR_DELIVERY' }));
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    answerProbes(shipment({ version: 3, currentStatus: 'IN_TRANSIT' }), 1, 0);
    await vi.advanceTimersByTimeAsync(0);
    expect(tracker.shipment()?.version).toBe(4);
    expect(tracker.shipment()?.currentStatus).toBe('OUT_FOR_DELIVERY');
  });

  it('pauses while the tab is hidden, cancelling in-flight requests, and resumes immediately', async () => {
    await initialLoad();
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    const inFlight = http.expectOne(SHIPMENT_URL);
    http.match(isProbe(EVENTS_URL));
    http.match(isProbe(NOTIFICATIONS_URL));

    visible.next(false);
    expect(inFlight.cancelled).toBe(true);
    expect(tracker.mode()).toBe('paused');
    await vi.advanceTimersByTimeAsync(CONFIG.slowMs * 3);
    http.expectNone(SHIPMENT_URL);

    visible.next(true);
    answerProbes(shipment({ version: 1, currentStatus: 'IN_TRANSIT' }), 1, 0);
    await vi.advanceTimersByTimeAsync(0);
  });

  it('keeps checking briefly after DELIVERED, then settles; refresh resumes', async () => {
    await initialLoad(shipment({ version: 8, currentStatus: 'DELIVERED' }));
    // Within the grace window: still polling.
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    answerProbes(shipment({ version: 8, currentStatus: 'DELIVERED' }), 1, 0);
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    answerProbes(shipment({ version: 8, currentStatus: 'DELIVERED' }), 1, 0);
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    answerProbes(shipment({ version: 8, currentStatus: 'DELIVERED' }), 1, 0);
    await vi.advanceTimersByTimeAsync(0);
    expect(tracker.mode()).toBe('settled');

    await vi.advanceTimersByTimeAsync(CONFIG.slowMs * 5);
    http.expectNone(SHIPMENT_URL);

    tracker.refresh();
    answerProbes(shipment({ version: 8, currentStatus: 'DELIVERED' }), 1, 0);
    await vi.advanceTimersByTimeAsync(0);
    expect(tracker.mode()).toBe('fast');
  });

  it('stops polling when the shipment does not exist', async () => {
    tracker.track(SHIPMENT_ID);
    answerProbes({ error: problem(404, 'shipment-not-found') }, 0, 0);
    // Probes for events/notifications on an unknown shipment would also be 404 in reality; the
    // tracker must not download full collections after the shipment 404.
    await vi.advanceTimersByTimeAsync(0);
    expect(tracker.notFound()).toBe(true);
    expect(tracker.mode()).toBe('stopped');
    await vi.advanceTimersByTimeAsync(CONFIG.slowMs * 5);
    http.expectNone(SHIPMENT_URL);
  });

  it('keeps showing the last good data and backs off when a probe fails', async () => {
    await initialLoad();
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    answerProbes({ error: problem(500, 'internal-error') }, 1, 0);
    await vi.advanceTimersByTimeAsync(0);
    expect(tracker.shipmentError()?.status).toBe(500);
    expect(tracker.shipment()?.version).toBe(1);
    expect(tracker.mode()).toBe('backoff');

    // No request at the fast interval...
    await vi.advanceTimersByTimeAsync(CONFIG.fastMs);
    http.expectNone(SHIPMENT_URL);
    // ...but one after the backoff delay.
    await vi.advanceTimersByTimeAsync(CONFIG.slowMs - CONFIG.fastMs);
    answerProbes(shipment({ version: 1, currentStatus: 'IN_TRANSIT' }), 1, 0);
    await vi.advanceTimersByTimeAsync(0);
    expect(tracker.shipmentError()).toBeNull();
  });

  it('loads every page of a long history', async () => {
    tracker.track(SHIPMENT_ID);
    answerProbes(shipment(), 250, 0);
    const first = Array.from({ length: 200 }, () => trackingEvent());
    const second = Array.from({ length: 50 }, () => trackingEvent());
    http
      .expectOne((r) => r.url === EVENTS_URL && r.params.get('page') === '0' && r.params.get('size') === '200')
      .flush(page(first, { size: 200, totalElements: 250, totalPages: 2, hasNext: true }));
    http
      .expectOne((r) => r.url === EVENTS_URL && r.params.get('page') === '1')
      .flush(page(second, { page: 1, size: 200, totalElements: 250, totalPages: 2, hasNext: false }));
    answerNotifications([]);
    await vi.advanceTimersByTimeAsync(0);
    expect(tracker.events()?.items).toHaveLength(250);
    expect(tracker.events()?.truncated).toBe(false);
  });

  it('resets state when switching to another shipment', async () => {
    await initialLoad();
    const otherId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    tracker.track(otherId);
    expect(tracker.shipment()).toBeNull();
    expect(tracker.events()).toBeNull();
    http.match((r) => r.url.startsWith(`/api/shipments/${otherId}`));
  });
});
