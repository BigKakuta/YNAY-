import { describe, expect, it } from 'vitest';
import { formatAmount, formatMoney, formatPlain, fractionDigits, parseMoney, roundToMinor } from './money';

describe('parseMoney', () => {
  it.each([
    ['12.34', 12340],
    ['-5', -5000],
    ['1,234.50', 1234500],
    ['1234.5', 1234500],
    ['0.001', 1],
    ['.5', 500],
    ['5.', 5000],
    ['', 0],
    ['  42  ', 42000],
    ['$12.00', 12000],
    ['-$12.00', -12000],
    ['$-12.00', -12000],
    ['(12.00)', -12000],
    ['−3', -3000],
    ['+7.25', 7250],
    ['1.234,50', 1234500],
    ['12,5', 12500],
    ['12,50', 12500],
    ['1,234', 1234000],
    ['0,123', 123],
    ['1 234,56 €', 1234560],
    ['1,234,567.89', 1234567890],
    ['1.234.567', 1234567000],
    ['12.3456', 12346],
    ['12.3454', 12345],
    ['-0', 0],
  ])('parses %j → %d', (input, expected) => {
    expect(parseMoney(input)).toBe(expected);
  });

  it.each(['abc', '12abc34', '1,2,3', '1.2.3,4', '--5', '.', ',', '1,23.4.5'])('rejects %j', (input) => {
    expect(parseMoney(input)).toBeNull();
  });
});

describe('formatting', () => {
  const intl = (v: number, currency: string) => new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(v);

  it('formats milliunits as currency in the user locale', () => {
    expect(formatMoney(-12340, 'USD')).toBe(intl(-12.34, 'USD'));
    expect(formatMoney(1234567, 'EUR')).toBe(intl(1234.567, 'EUR'));
    expect(formatMoney(-0, 'USD')).toBe(intl(0, 'USD'));
  });

  it('respects currency minor units', () => {
    expect(fractionDigits('USD')).toBe(2);
    expect(fractionDigits('JPY')).toBe(0);
    expect(formatMoney(1500_000, 'JPY')).toBe(intl(1500, 'JPY'));
    expect(formatMoney(1500_000, 'JPY').replace(/\D/g, '')).toBe('1500');
  });

  it('falls back for unknown currency codes', () => {
    expect(formatMoney(1000, 'XYZ1')).toContain('XYZ1');
  });

  it('formats plain values for inputs and round-trips through parseMoney', () => {
    expect(formatPlain(12340)).toBe('12.34');
    expect(formatPlain(1234500)).toBe('1234.50');
    expect(formatPlain(-5000)).toBe('-5.00');
    expect(formatPlain(1)).toBe('0.001');
    expect(formatPlain(5000, 'JPY')).toBe('5');
    for (const v of [0, 1, 999, 12340, -987650, 123456789]) expect(parseMoney(formatPlain(v))).toBe(v);
  });

  it('formats amounts without currency symbol', () => {
    const s = formatAmount(1450000, 'USD');
    expect(s).not.toContain('$');
    expect(parseMoney(s)).toBe(1450000);
  });

  it('rounds to minor units', () => {
    expect(roundToMinor(12345, 'USD')).toBe(12350);
    expect(roundToMinor(1499, 'JPY')).toBe(1000);
  });
});
