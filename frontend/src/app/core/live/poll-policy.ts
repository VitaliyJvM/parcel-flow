import { InjectionToken } from '@angular/core';
import type { ShipmentStatus } from '../api/models';

export interface PollConfig {
  /** Interval while something changed recently: a simulator run is in progress. */
  fastMs: number;
  /** Interval for a shipment that has been quiet for a while but is not delivered. */
  slowMs: number;
  /** How long after the last observed change polling stays fast. */
  activeWindowMs: number;
  /**
   * How long polling continues after the last change once the shipment is DELIVERED.
   *
   * DELIVERED is terminal for the *status*, not for the *history*: a late or replayed scan is still
   * stored, as SUPERSEDED, without moving the status or bumping the shipment version. Stopping the
   * moment DELIVERED appears would hide exactly those events during a demo.
   */
  deliveredGraceMs: number;
  /** Ceiling for the error backoff. */
  maxErrorBackoffMs: number;
}

export const DEFAULT_POLL_CONFIG: PollConfig = {
  fastMs: 2_000,
  slowMs: 15_000,
  activeWindowMs: 120_000,
  deliveredGraceMs: 60_000,
  maxErrorBackoffMs: 60_000,
};

export const POLL_CONFIG = new InjectionToken<PollConfig>('POLL_CONFIG', {
  providedIn: 'root',
  factory: () => DEFAULT_POLL_CONFIG,
});

export interface PollState {
  status: ShipmentStatus | null;
  /** Epoch ms of the last observed change (or of the session start / manual refresh). */
  lastChangeAt: number;
  now: number;
  consecutiveErrors: number;
}

export type PollMode = 'fast' | 'slow' | 'backoff' | 'settled';

export interface PollDecision {
  mode: PollMode;
  /** null: stop polling until the user refreshes or resumes. */
  delayMs: number | null;
}

/** Pure scheduling decision for the next poll; see `ShipmentLiveTracker`. */
export function nextPoll(state: PollState, config: PollConfig = DEFAULT_POLL_CONFIG): PollDecision {
  const quietFor = state.now - state.lastChangeAt;
  if (state.status === 'DELIVERED' && quietFor >= config.deliveredGraceMs) {
    return { mode: 'settled', delayMs: null };
  }
  if (state.consecutiveErrors > 0) {
    const backoff = config.slowMs * 2 ** (state.consecutiveErrors - 1);
    return { mode: 'backoff', delayMs: Math.min(backoff, config.maxErrorBackoffMs) };
  }
  if (state.status === 'DELIVERED') {
    return { mode: 'fast', delayMs: config.fastMs };
  }
  return quietFor < config.activeWindowMs
    ? { mode: 'fast', delayMs: config.fastMs }
    : { mode: 'slow', delayMs: config.slowMs };
}
