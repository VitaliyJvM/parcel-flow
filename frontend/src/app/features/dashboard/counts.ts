import { catchError, from, map, mergeMap, Observable, of, scan, startWith } from 'rxjs';
import { ApiError, toApiError } from '../../core/api/api-error';

export type CountState = { state: 'loading' } | { state: 'ok'; count: number } | { state: 'error'; error: ApiError };

/** Concurrency cap: a status breakdown is 8 requests; never fire them all at once. */
export const COUNT_CONCURRENCY = 3;

/**
 * Loads one count per key with at most `concurrency` requests in flight, emitting the growing
 * result map after each response. A failure marks only its own key, so one 500 shows one
 * "unavailable" tile instead of blanking the dashboard.
 *
 * The API has no aggregate endpoint, so each count is a `size=1` page read for `totalElements`.
 */
export function loadCounts<K extends string>(
  keys: readonly K[],
  fetchCount: (key: K) => Observable<number>,
  concurrency = COUNT_CONCURRENCY,
): Observable<Record<K, CountState>> {
  const initial = Object.fromEntries(keys.map((k) => [k, { state: 'loading' }])) as Record<K, CountState>;
  return from(keys).pipe(
    mergeMap(
      (key) =>
        fetchCount(key).pipe(
          map((count): [K, CountState] => [key, { state: 'ok', count }]),
          catchError((error: unknown) => of<[K, CountState]>([key, { state: 'error', error: toApiError(error) }])),
        ),
      concurrency,
    ),
    scan((acc, [key, value]) => ({ ...acc, [key]: value }), initial),
    startWith(initial),
  );
}
