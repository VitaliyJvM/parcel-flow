import {
  formatInstant,
  formatInstantUtc,
  formatLocalDate,
  formatRelative,
  parseLocalDate,
  todayLocalDate,
} from './dates';

describe('date handling', () => {
  describe('LocalDate (estimatedDeliveryDate)', () => {
    it('formats the stored calendar day, independent of the viewer time zone', () => {
      expect(formatLocalDate('2026-08-12', 'en-US')).toBe('Wed, Aug 12, 2026');
    });

    it('documents the bug it avoids: new Date(localDate) shifts a day west of UTC', () => {
      const naive = new Intl.DateTimeFormat('en-US', {
        day: 'numeric',
        timeZone: 'America/Vancouver',
      }).format(new Date('2026-08-12'));
      expect(naive).toBe('11');
      // ...whereas the LocalDate formatter never builds a zoned Date from the value.
      expect(formatLocalDate('2026-08-12', 'en-US')).toContain('12');
    });

    it('rejects impossible and malformed dates', () => {
      expect(parseLocalDate('2026-02-31')).toBeNull();
      expect(parseLocalDate('2026-8-1')).toBeNull();
      expect(parseLocalDate('2026-08-12T00:00:00Z')).toBeNull();
      expect(parseLocalDate('2028-02-29')).toEqual({ year: 2028, month: 2, day: 29 });
    });

    it('passes unparseable input through unchanged rather than inventing a date', () => {
      expect(formatLocalDate('soon')).toBe('soon');
      expect(formatLocalDate(null)).toBe('');
    });

    it('computes today in a given zone', () => {
      // 02:30 UTC on 1 Aug is still 31 Jul in Vancouver (UTC-7 in summer).
      const instant = new Date('2026-08-01T02:30:00Z');
      expect(todayLocalDate(instant, 'UTC')).toBe('2026-08-01');
      expect(todayLocalDate(instant, 'America/Vancouver')).toBe('2026-07-31');
    });
  });

  describe('Instant fields', () => {
    it('renders a UTC instant in the requested zone', () => {
      const value = '2026-08-01T02:30:00Z';
      expect(formatInstant(value, { timeZone: 'America/Vancouver', locale: 'en-US' })).toContain('Jul 31, 2026');
      expect(formatInstant(value, { timeZone: 'Europe/Berlin', locale: 'en-US' })).toContain('Aug 1, 2026');
    });

    it('exposes the exact UTC value for tooltips', () => {
      expect(formatInstantUtc('2026-08-01T02:30:00Z')).toBe('2026-08-01T02:30:00Z');
      expect(formatInstantUtc('2026-08-01T02:30:00.123456Z')).toBe('2026-08-01T02:30:00.123Z');
      expect(formatInstantUtc('not a date')).toBe('');
    });

    it('formats relative times', () => {
      const now = new Date('2026-08-01T12:00:00Z');
      expect(formatRelative('2026-08-01T11:57:00Z', now, 'en')).toBe('3 minutes ago');
      expect(formatRelative('2026-08-01T11:59:50Z', now, 'en')).toBe('10 seconds ago');
    });
  });
});
