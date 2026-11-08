import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/**
 * The simulator CLI parses `--seed` and `--delay-ms` with Java's `Long.parseLong` after trimming
 * (SimulationRequest.parseSeed / parseDelay), and rejects a negative delay. These values are kept
 * as strings end to end and compared with BigInt: a JavaScript `number` cannot represent every
 * long (anything beyond 2^53 is silently rounded) and prints large values in exponent form
 * ("1e+21"), which `Long.parseLong` rejects.
 */
export const JAVA_LONG_MIN = -(2n ** 63n);
export const JAVA_LONG_MAX = 2n ** 63n - 1n;

/** Optional sign followed by ASCII digits: the subset of `Long.parseLong` syntax we accept. */
const INTEGER = /^[+-]?\d+$/;

export interface JavaLongOptions {
  /** Reject values below zero (the CLI's rule for --delay-ms). */
  nonNegative?: boolean;
}

/**
 * Parses a decimal Java `long` exactly.
 *
 * @returns the value, or null when it is not an integer or is outside the allowed range
 */
export function parseJavaLong(raw: string, options: JavaLongOptions = {}): bigint | null {
  const value = raw.trim();
  if (!INTEGER.test(value)) {
    return null;
  }
  const parsed = BigInt(value);
  const min = options.nonNegative ? 0n : JAVA_LONG_MIN;
  return parsed < min || parsed > JAVA_LONG_MAX ? null : parsed;
}

/**
 * Form validator for an optional Java `long` field (empty passes). Errors:
 * `{ integer: true }` for anything that is not a whole number, `{ javaLongRange: { min, max } }`
 * when it is out of range.
 */
export function javaLongValidator(options: JavaLongOptions = {}): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const raw: unknown = control.value;
    if (raw === null || raw === undefined || (typeof raw === 'string' && raw.trim() === '')) {
      return null;
    }
    if (typeof raw !== 'string' || !INTEGER.test(raw.trim())) {
      return { integer: true };
    }
    return parseJavaLong(raw, options) === null
      ? {
          javaLongRange: {
            min: (options.nonNegative ? 0n : JAVA_LONG_MIN).toString(),
            max: JAVA_LONG_MAX.toString(),
          },
        }
      : null;
  };
}
