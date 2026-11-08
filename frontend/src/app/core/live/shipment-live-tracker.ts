import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import {
  BehaviorSubject,
  catchError,
  combineLatest,
  defer,
  distinctUntilChanged,
  EMPTY,
  expand,
  forkJoin,
  map,
  Observable,
  of,
  reduce,
  repeat,
  switchMap,
  tap,
  timer,
} from 'rxjs';
import { ApiError, toApiError } from '../api/api-error';
import {
  NotificationResponse,
  Page,
  PAGE_LIMITS,
  ShipmentResponse,
  TrackingEventResponse,
} from '../api/models';
import { ShipmentsApi } from '../api/tracking-api';
import { NOW, PAGE_VISIBLE } from './page-visibility';
import { nextPoll, POLL_CONFIG, PollMode } from './poll-policy';

/** Upper bound on pages fetched per collection per refresh (200 events/page → 2,000 events). */
const MAX_PAGES = 10;

export type LiveMode = PollMode | 'idle' | 'paused' | 'stopped';

interface Collection<T> {
  items: T[];
  total: number;
  truncated: boolean;
}

type Outcome<T> = { ok: true; value: T } | { ok: false; error: ApiError };

function attempt<T>(source: Observable<T>): Observable<Outcome<T>> {
  return source.pipe(
    map((value): Outcome<T> => ({ ok: true, value })),
    catchError((error: unknown) => of<Outcome<T>>({ ok: false, error: toApiError(error) })),
  );
}

/**
 * Keeps one shipment, its tracking history and its notification records up to date by polling.
 *
 * Each tick issues three cheap, independent requests in parallel:
 *
 *   GET /api/shipments/{id}                         current status + version
 *   GET /api/shipments/{id}/events?size=1           only `totalElements`
 *   GET /api/shipments/{id}/notifications?size=1    only `totalElements`
 *
 * and re-downloads a collection only when its count moved (or on first load). The count probes
 * are what make SUPERSEDED events visible: an event that loses the ordering comparison is stored
 * without touching the shipment row, so `shipment.version` stays the same while the history grows.
 * Keying history refresh off the version alone would miss it. Events and notifications are
 * immutable and never deleted, so a count is a sufficient change signal for both.
 *
 * Scheduling rules (see `nextPoll`): fast while things are changing, slower when quiet, a grace
 * window after DELIVERED, exponential backoff on errors, paused while the tab is hidden, and a
 * full stop on 404. Ticks are strictly sequential (`repeat` waits for the previous tick to
 * complete), so requests never overlap; `switchMap` on every restart cancels anything in flight,
 * so a response for a previous shipment or a pre-refresh tick can never land.
 *
 * Provided per component, so it is torn down with the page that uses it.
 */
@Injectable()
export class ShipmentLiveTracker {
  private readonly api = inject(ShipmentsApi);
  private readonly visible$ = inject(PAGE_VISIBLE);
  private readonly now = inject(NOW);
  private readonly config = inject(POLL_CONFIG);

  private readonly shipmentId$ = new BehaviorSubject<string | null>(null);
  /** Bumped by refresh()/resume() to restart the loop with an immediate tick. */
  private readonly restart$ = new BehaviorSubject<number>(0);

  private lastChangeAt = 0;
  private consecutiveErrors = 0;

  private readonly _shipment = signal<ShipmentResponse | null>(null);
  private readonly _events = signal<Collection<TrackingEventResponse> | null>(null);
  private readonly _notifications = signal<Collection<NotificationResponse> | null>(null);
  private readonly _shipmentError = signal<ApiError | null>(null);
  private readonly _historyError = signal<ApiError | null>(null);
  private readonly _notificationsError = signal<ApiError | null>(null);
  private readonly _notFound = signal(false);
  private readonly _mode = signal<LiveMode>('idle');
  private readonly _busy = signal(false);
  private readonly _lastCheckedAt = signal<number | null>(null);
  private readonly _lastChangedAt = signal<number | null>(null);

  readonly shipment = this._shipment.asReadonly();
  readonly events = this._events.asReadonly();
  readonly notifications = this._notifications.asReadonly();
  readonly shipmentError = this._shipmentError.asReadonly();
  readonly historyError = this._historyError.asReadonly();
  readonly notificationsError = this._notificationsError.asReadonly();
  readonly notFound = this._notFound.asReadonly();
  readonly mode = this._mode.asReadonly();
  readonly busy = this._busy.asReadonly();
  readonly lastCheckedAt = this._lastCheckedAt.asReadonly();
  readonly lastChangedAt = this._lastChangedAt.asReadonly();

  constructor() {
    const subscription = combineLatest([
      this.shipmentId$.pipe(distinctUntilChanged()),
      this.visible$.pipe(distinctUntilChanged()),
      this.restart$,
    ])
      .pipe(
        switchMap(([id, visible]) => {
          this._busy.set(false);
          if (id === null) {
            this._mode.set('idle');
            return EMPTY;
          }
          if (!visible) {
            this._mode.set('paused');
            return EMPTY;
          }
          this._mode.set('fast');
          return this.loop(id);
        }),
      )
      .subscribe();
    inject(DestroyRef).onDestroy(() => subscription.unsubscribe());
  }

  /** Starts (or switches) tracking. Clears state when the id changes. */
  track(shipmentId: string): void {
    if (this.shipmentId$.value !== shipmentId) {
      this.reset();
      this.lastChangeAt = this.now();
      this.shipmentId$.next(shipmentId);
    }
  }

  /** Immediate full check, and re-arms the fast polling window. */
  refresh(): void {
    this.lastChangeAt = this.now();
    this.consecutiveErrors = 0;
    this.restart$.next(this.restart$.value + 1);
  }

  /** Resume after the loop settled (delivered and quiet) or stopped on errors. */
  resume(): void {
    this.refresh();
  }

  private reset(): void {
    this._shipment.set(null);
    this._events.set(null);
    this._notifications.set(null);
    this._shipmentError.set(null);
    this._historyError.set(null);
    this._notificationsError.set(null);
    this._notFound.set(false);
    this._lastCheckedAt.set(null);
    this._lastChangedAt.set(null);
    this.consecutiveErrors = 0;
  }

  private loop(id: string): Observable<void> {
    return defer(() => this.tick(id)).pipe(
      repeat({
        delay: () => {
          if (this._notFound()) {
            this._mode.set('stopped');
            return EMPTY;
          }
          const decision = nextPoll(
            {
              status: this._shipment()?.currentStatus ?? null,
              lastChangeAt: this.lastChangeAt,
              now: this.now(),
              consecutiveErrors: this.consecutiveErrors,
            },
            this.config,
          );
          this._mode.set(decision.mode);
          return decision.delayMs === null ? EMPTY : timer(decision.delayMs);
        },
      }),
    );
  }

  private tick(id: string): Observable<void> {
    this._busy.set(true);
    const probe = { probe: true };
    return forkJoin({
      shipment: attempt(this.api.get(id, probe)),
      eventCount: attempt(this.api.events(id, { page: 0, size: 1 }, probe)),
      notificationCount: attempt(this.api.notifications(id, { page: 0, size: 1 }, probe)),
    }).pipe(
      switchMap(({ shipment, eventCount, notificationCount }) => {
        let changed = false;
        let failures = 0;

        if (shipment.ok) {
          changed = this.applyShipment(shipment.value) || changed;
          this._shipmentError.set(null);
        } else {
          failures++;
          if (shipment.error.status === 404) {
            this._notFound.set(true);
          }
          this._shipmentError.set(shipment.error);
        }

        const events = this._events();
        const needEvents =
          eventCount.ok && (events === null || eventCount.value.totalElements !== events.total);
        const notifications = this._notifications();
        const needNotifications =
          notificationCount.ok &&
          (notifications === null || notificationCount.value.totalElements !== notifications.total);
        if (!eventCount.ok) {
          failures++;
          this._historyError.set(eventCount.error);
        }
        if (!notificationCount.ok) {
          failures++;
          this._notificationsError.set(notificationCount.error);
        }

        if (this._notFound()) {
          return of({ changed, failures, events: null, notifications: null });
        }
        return forkJoin({
          events: needEvents
            ? attempt(this.loadAll((page) => this.api.events(id, page), PAGE_LIMITS.trackingEvents))
            : of(null),
          notifications: needNotifications
            ? attempt(
                this.loadAll((page) => this.api.notifications(id, page), PAGE_LIMITS.notifications),
              )
            : of(null),
        }).pipe(map((loaded) => ({ changed, failures, ...loaded })));
      }),
      tap((result) => {
        let { changed, failures } = result;
        if (result.events) {
          if (result.events.ok) {
            changed = this._events() !== null || changed;
            this._events.set(result.events.value);
            this._historyError.set(null);
          } else {
            failures++;
            this._historyError.set(result.events.error);
          }
        }
        if (result.notifications) {
          if (result.notifications.ok) {
            changed = this._notifications() !== null || changed;
            this._notifications.set(result.notifications.value);
            this._notificationsError.set(null);
          } else {
            failures++;
            this._notificationsError.set(result.notifications.error);
          }
        }
        const now = this.now();
        this.consecutiveErrors = failures > 0 ? this.consecutiveErrors + 1 : 0;
        if (changed) {
          this.lastChangeAt = now;
          this._lastChangedAt.set(now);
        }
        this._lastCheckedAt.set(now);
        this._busy.set(false);
      }),
      map(() => undefined),
    );
  }

  /**
   * Accepts a shipment snapshot unless it is older than the one already shown. The Redis cache is
   * evicted on every status change, but a response read just before an eviction can still arrive
   * after a fresher one; `version` only ever increases, so it orders snapshots safely.
   *
   * @returns whether the visible state changed (not counting the first load)
   */
  private applyShipment(next: ShipmentResponse): boolean {
    const current = this._shipment();
    if (current && current.shipmentId === next.shipmentId && next.version < current.version) {
      return false;
    }
    this._shipment.set(next);
    return (
      current !== null &&
      (current.version !== next.version || current.currentStatus !== next.currentStatus)
    );
  }

  /** Fetches every page of a collection sequentially, up to MAX_PAGES. */
  private loadAll<T>(
    fetchPage: (page: { page: number; size: number }) => Observable<Page<T>>,
    size: number,
  ): Observable<Collection<T>> {
    return fetchPage({ page: 0, size }).pipe(
      expand((page) =>
        page.hasNext && page.page + 1 < MAX_PAGES ? fetchPage({ page: page.page + 1, size }) : EMPTY,
      ),
      reduce(
        (acc: Collection<T>, page: Page<T>) => ({
          items: [...acc.items, ...page.content],
          total: page.totalElements,
          truncated: page.hasNext && page.page + 1 >= MAX_PAGES,
        }),
        { items: [], total: 0, truncated: false },
      ),
    );
  }
}
