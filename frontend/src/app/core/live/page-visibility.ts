import { DOCUMENT } from '@angular/common';
import { inject, InjectionToken } from '@angular/core';
import { distinctUntilChanged, fromEvent, map, Observable, of, shareReplay, startWith } from 'rxjs';

/**
 * Emits whether the page is visible, starting with the current state. A token so tests can
 * drive it directly instead of faking `document.visibilityState`.
 */
export const PAGE_VISIBLE = new InjectionToken<Observable<boolean>>('PAGE_VISIBLE', {
  providedIn: 'root',
  factory: () => {
    const document = inject(DOCUMENT);
    if (!document.defaultView) {
      return of(true);
    }
    const isVisible = () => document.visibilityState !== 'hidden';
    return fromEvent(document, 'visibilitychange').pipe(
      map(isVisible),
      startWith(isVisible()),
      distinctUntilChanged(),
      shareReplay({ bufferSize: 1, refCount: true }),
    );
  },
});

/** Milliseconds since epoch; injectable so polling decisions are testable. */
export const NOW = new InjectionToken<() => number>('NOW', {
  providedIn: 'root',
  factory: () => () => Date.now(),
});
