import { FormControl } from '@angular/forms';
import { JAVA_LONG_MAX, JAVA_LONG_MIN, javaLongValidator, parseJavaLong } from './java-long';

describe('parseJavaLong (mirrors Long.parseLong for the simulator CLI)', () => {
  it('accepts the exact signed 64-bit boundaries', () => {
    expect(parseJavaLong('9223372036854775807')).toBe(JAVA_LONG_MAX);
    expect(parseJavaLong('-9223372036854775808')).toBe(JAVA_LONG_MIN);
  });

  it('rejects one past each boundary', () => {
    expect(parseJavaLong('9223372036854775808')).toBeNull();
    expect(parseJavaLong('-9223372036854775809')).toBeNull();
  });

  it('keeps integers beyond Number.MAX_SAFE_INTEGER exact', () => {
    // 2^53 + 1 is not representable as a JS number (it rounds to 2^53).
    expect(parseJavaLong('9007199254740993')?.toString()).toBe('9007199254740993');
    expect(Number('9007199254740993').toString()).toBe('9007199254740992');
  });

  it('rejects exponent notation, decimals, hex, blanks and junk', () => {
    for (const bad of ['1e21', '1E3', '12.5', '0x10', '', ' ', '42abc', '--1', '½', 'NaN', 'Infinity']) {
      expect(parseJavaLong(bad), bad).toBeNull();
    }
  });

  it('trims like the CLI and accepts an explicit sign', () => {
    expect(parseJavaLong('  42 ')).toBe(42n);
    expect(parseJavaLong('+7')).toBe(7n);
    expect(parseJavaLong('007')).toBe(7n);
  });

  it('applies the non-negative rule used for --delay-ms', () => {
    expect(parseJavaLong('0', { nonNegative: true })).toBe(0n);
    expect(parseJavaLong('-0', { nonNegative: true })).toBe(0n);
    expect(parseJavaLong('-1', { nonNegative: true })).toBeNull();
    expect(parseJavaLong('9223372036854775807', { nonNegative: true })).toBe(JAVA_LONG_MAX);
  });
});

describe('javaLongValidator', () => {
  const errors = (value: string, nonNegative = false) =>
    new FormControl(value, { nonNullable: true, validators: [javaLongValidator({ nonNegative })] }).errors;

  it('lets an empty optional field through', () => {
    expect(errors('')).toBeNull();
    expect(errors('   ')).toBeNull();
  });

  it('distinguishes "not an integer" from "out of range"', () => {
    expect(errors('1e21')).toEqual({ integer: true });
    expect(errors('3.5')).toEqual({ integer: true });
    expect(errors('9223372036854775808')).toEqual({
      javaLongRange: { min: '-9223372036854775808', max: '9223372036854775807' },
    });
    expect(errors('-5', true)).toEqual({ javaLongRange: { min: '0', max: '9223372036854775807' } });
    expect(errors('-9223372036854775808')).toBeNull();
  });
});
