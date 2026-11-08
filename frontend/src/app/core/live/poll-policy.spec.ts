import { DEFAULT_POLL_CONFIG, nextPoll } from './poll-policy';

const config = DEFAULT_POLL_CONFIG;
const base = { lastChangeAt: 0, consecutiveErrors: 0 };

describe('nextPoll', () => {
  it('polls fast while changes are recent', () => {
    expect(nextPoll({ ...base, status: 'IN_TRANSIT', now: 10_000 })).toEqual({ mode: 'fast', delayMs: config.fastMs });
  });

  it('slows down after the active window', () => {
    expect(nextPoll({ ...base, status: 'IN_TRANSIT', now: config.activeWindowMs })).toEqual({
      mode: 'slow',
      delayMs: config.slowMs,
    });
  });

  it('keeps polling fast for a grace window after DELIVERED (late SUPERSEDED events)', () => {
    expect(nextPoll({ ...base, status: 'DELIVERED', now: config.deliveredGraceMs - 1 })).toEqual({
      mode: 'fast',
      delayMs: config.fastMs,
    });
  });

  it('settles once DELIVERED and quiet for the grace window', () => {
    expect(nextPoll({ ...base, status: 'DELIVERED', now: config.deliveredGraceMs })).toEqual({
      mode: 'settled',
      delayMs: null,
    });
  });

  it('backs off exponentially on consecutive errors, capped', () => {
    const at = (errors: number) => nextPoll({ ...base, status: 'IN_TRANSIT', now: 1, consecutiveErrors: errors }).delayMs;
    expect(at(1)).toBe(config.slowMs);
    expect(at(2)).toBe(config.slowMs * 2);
    expect(at(10)).toBe(config.maxErrorBackoffMs);
  });

  it('still settles a quiet delivered shipment even while erroring', () => {
    expect(
      nextPoll({ ...base, status: 'DELIVERED', now: config.deliveredGraceMs, consecutiveErrors: 3 }).mode,
    ).toBe('settled');
  });
});
