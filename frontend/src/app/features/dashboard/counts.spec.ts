import { Subject, throwError } from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import { CountState, loadCounts } from './counts';

describe('loadCounts', () => {
  it('keeps at most `concurrency` requests in flight', () => {
    const pending = new Map<string, Subject<number>>();
    let maxInFlight = 0;
    const emissions: Record<string, CountState>[] = [];
    loadCounts(['a', 'b', 'c', 'd', 'e'], (key) => {
      const subject = new Subject<number>();
      pending.set(key, subject);
      maxInFlight = Math.max(maxInFlight, pending.size);
      return subject;
    }, 2).subscribe((v) => emissions.push(v));

    expect(pending.size).toBe(2);
    for (const key of ['a', 'b', 'c', 'd', 'e']) {
      const subject = pending.get(key);
      pending.delete(key);
      subject?.next(1);
      subject?.complete();
    }
    expect(maxInFlight).toBe(2);
    expect(Object.values(emissions.at(-1) ?? {}).every((c) => c.state === 'ok')).toBe(true);
  });

  it('marks only the failing key on a partial failure', () => {
    let last: Record<string, CountState> | undefined;
    loadCounts(['ok', 'bad'], (key) =>
      key === 'bad'
        ? throwError(() => new ApiError(500, 'Server error', 'x', null, {}, 'c'))
        : new Subject<number>().asObservable(),
    ).subscribe((v) => (last = v));
    expect(last?.['ok']?.state).toBe('loading');
    expect(last?.['bad']?.state).toBe('error');
  });

  it('starts with every key loading', () => {
    let first: Record<string, CountState> | undefined;
    loadCounts(['x', 'y'], () => new Subject<number>()).subscribe((v) => (first ??= v));
    expect(first).toEqual({ x: { state: 'loading' }, y: { state: 'loading' } });
  });
});
