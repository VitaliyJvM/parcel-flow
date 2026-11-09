import { DOCUMENT } from '@angular/common';
import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { DestroyRef, inject, Injectable } from '@angular/core';
import { NOW } from '../live/page-visibility';

/**
 * Marks a request the application sent on its own (a polling tick), not because the user did
 * something. Such a request is sent as background unless the user has interacted with the page
 * since the last request that counted as activity.
 */
export const BACKGROUND_REQUEST = new HttpContextToken<boolean>(() => false);

/** The header the backend reads; with `true`, the request does not reset the idle timeout. */
export const BACKGROUND_HEADER = 'X-ParcelFlow-Background';

const INTERACTIONS = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;

/**
 * When the user last interacted with the page, and when the backend was last told about it.
 *
 * The backend ends a session after 30 minutes without user activity. It cannot tell a click from a
 * timer, so the UI tells it: automatic polling is sent with `X-ParcelFlow-Background: true` unless
 * the user has clicked, typed, scrolled or touched since the previous request that counted as
 * activity. A page left open on a shipment therefore no longer keeps the session alive forever,
 * while someone actually using it is never signed out mid-use.
 *
 * Listens passively at the document; records a timestamp and nothing else.
 */
@Injectable({ providedIn: 'root' })
export class UserActivity {
  private readonly now = inject(NOW);
  private lastInteractionAt: number;
  private lastReportedAt: number;

  constructor() {
    // Loading the application is itself the user's doing.
    this.lastInteractionAt = this.now();
    this.lastReportedAt = this.lastInteractionAt;
    const document = inject(DOCUMENT);
    const listener = () => this.interacted();
    for (const type of INTERACTIONS) {
      document.addEventListener(type, listener, { capture: true, passive: true });
    }
    inject(DestroyRef).onDestroy(() => {
      for (const type of INTERACTIONS) {
        document.removeEventListener(type, listener, { capture: true });
      }
    });
  }

  /** Records a user interaction. Public for tests; production code is driven by the listeners. */
  interacted(): void {
    this.lastInteractionAt = this.now();
  }

  /**
   * Whether a request the application is about to send on its own should count as activity: only
   * if the user interacted since the last request that did. Either way, a request that counts
   * moves the reference point.
   */
  claimActivity(background: boolean): boolean {
    if (background && this.lastInteractionAt <= this.lastReportedAt) {
      return false;
    }
    this.lastReportedAt = this.now();
    return true;
  }
}

/**
 * Sends `X-ParcelFlow-Background: true` on API requests marked {@link BACKGROUND_REQUEST} when the
 * user has not interacted since the last request that counted as activity. Every other API request
 * counts as activity and carries no header. The header is only a hint for the idle timeout; the
 * backend never grants anything because of it.
 */
export const userActivityInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) {
    return next(req);
  }
  const counts = inject(UserActivity).claimActivity(req.context.get(BACKGROUND_REQUEST));
  return next(counts ? req : req.clone({ setHeaders: { [BACKGROUND_HEADER]: 'true' } }));
};
