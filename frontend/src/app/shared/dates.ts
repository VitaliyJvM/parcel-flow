/**
 * Date handling for the two temporal types the API returns.
 *
 *  - `Instant` fields (`eventTime`, `receivedAt`, `createdAt`, ...) are ISO-8601 UTC timestamps.
 *    They are moments, so they are shown in the viewer's time zone with the UTC value available.
 *  - `estimatedDeliveryDate` is a `LocalDate` (`2026-08-12`): a calendar day with no time zone.
 *    `new Date('2026-08-12')` parses that as UTC midnight, which renders as 11 August anywhere
 *    west of UTC. It is therefore never turned into a `Date` for display; it is formatted from its
 *    own year/month/day components.
 */

const LOCAL_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

export interface LocalDateParts {
  year: number;
  month: number;
  day: number;
}

export function parseLocalDate(value: string | null | undefined): LocalDateParts | null {
  if (!value) {
    return null;
  }
  const match = LOCAL_DATE_PATTERN.exec(value);
  if (!match) {
    return null;
  }
  const [, y, m, d] = match;
  const parts = { year: Number(y), month: Number(m), day: Number(d) };
  // Reject 2026-02-31 and friends by round-tripping through UTC (zone-independent).
  const probe = new Date(Date.UTC(parts.year, parts.month - 1, parts.day));
  return probe.getUTCFullYear() === parts.year &&
    probe.getUTCMonth() === parts.month - 1 &&
    probe.getUTCDate() === parts.day
    ? parts
    : null;
}

/** "Wed, Aug 12, 2026" for a LocalDate, identical in every time zone. */
export function formatLocalDate(value: string | null | undefined, locale?: string): string {
  const parts = parseLocalDate(value);
  if (!parts) {
    return value ?? '';
  }
  // Formatting a UTC-midnight instant *in UTC* yields exactly the stored calendar day.
  return new Intl.DateTimeFormat(locale, {
    weekday: 'short',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(parts.year, parts.month - 1, parts.day)));
}

/**
 * Today's calendar date in the given zone (default: the viewer's), as `YYYY-MM-DD`. Used as the
 * `min` of the ETA input and to tell whether an ETA is in the past.
 */
export function todayLocalDate(now: Date = new Date(), timeZone?: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone,
  }).formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')}`;
}

/** Lexicographic compare is correct for zero-padded ISO dates. */
export function isLocalDateBefore(a: string, b: string): boolean {
  return a < b;
}

/** An ISO instant shown in the viewer's zone (or a given one), with seconds. */
export function formatInstant(
  value: string | null | undefined,
  options: { timeZone?: string; locale?: string } = {},
): string {
  const date = parseInstant(value);
  if (!date) {
    return '';
  }
  return new Intl.DateTimeFormat(options.locale, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZone: options.timeZone,
    timeZoneName: 'short',
  }).format(date);
}

/**
 * A compact instant for tables and timelines: "Nov 7, 6:10 PM", with the year only when it is not
 * the current one. Seconds and the zone name are left to the full form (tooltip / screen readers).
 */
export function formatInstantShort(
  value: string | null | undefined,
  options: { timeZone?: string; locale?: string; now?: Date } = {},
): string {
  const date = parseInstant(value);
  if (!date) {
    return '';
  }
  const yearOf = (d: Date) =>
    new Intl.DateTimeFormat('en', { year: 'numeric', timeZone: options.timeZone }).format(d);
  const sameYear = yearOf(date) === yearOf(options.now ?? new Date());
  return new Intl.DateTimeFormat(options.locale, {
    year: sameYear ? undefined : 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZone: options.timeZone,
  }).format(date);
}

/** The exact UTC value, for tooltips and copy/paste into logs. */
export function formatInstantUtc(value: string | null | undefined): string {
  const date = parseInstant(value);
  return date ? date.toISOString().replace('.000Z', 'Z') : '';
}

export function parseInstant(value: string | null | undefined): Date | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** "3 min ago" style relative time; falls back to absolute beyond a week. */
export function formatRelative(
  value: string | null | undefined,
  now: Date = new Date(),
  locale?: string,
): string {
  const date = parseInstant(value);
  if (!date) {
    return '';
  }
  const seconds = Math.round((date.getTime() - now.getTime()) / 1000);
  const abs = Math.abs(seconds);
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  if (abs < 60) return rtf.format(seconds, 'second');
  if (abs < 3600) return rtf.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(seconds / 3600), 'hour');
  if (abs < 7 * 86400) return rtf.format(Math.round(seconds / 86400), 'day');
  return formatInstant(value, { locale });
}
