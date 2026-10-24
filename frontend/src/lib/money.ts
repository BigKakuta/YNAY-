// Money helpers. All amounts are integer milliunits (1.00 = 1000).

const currencyFormatters = new Map<string, Intl.NumberFormat>();
const plainFormatters = new Map<string, Intl.NumberFormat>();

function currencyFormatter(currency: string): Intl.NumberFormat {
  let f = currencyFormatters.get(currency);
  if (!f) {
    try {
      f = new Intl.NumberFormat(undefined, { style: 'currency', currency });
    } catch {
      // Unknown ISO code: fall back to a plain number with the code appended.
      f = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }
    currencyFormatters.set(currency, f);
  }
  return f;
}

/** Number of minor-unit digits for a currency (USD 2, JPY 0, ...). */
export function fractionDigits(currency: string): number {
  return currencyFormatter(currency).resolvedOptions().maximumFractionDigits ?? 2;
}

/** `formatMoney(-12340, 'USD')` → "-$12.34" (locale dependent). */
export function formatMoney(milliunits: number, currency: string): string {
  const f = currencyFormatter(currency);
  const value = milliunits / 1000;
  const out = f.format(Object.is(value, -0) ? 0 : value);
  return f.resolvedOptions().style === 'currency' ? out : `${out} ${currency}`;
}

/** Locale-formatted amount without the currency symbol: 1450000 → "1,450.00" (en-US). */
export function formatAmount(milliunits: number, currency: string): string {
  const f = currencyFormatter(currency);
  const value = milliunits / 1000;
  return f
    .formatToParts(Object.is(value, -0) ? 0 : value)
    .filter((p) => p.type !== 'currency')
    .map((p) => p.value)
    .join('')
    .replace(/^[\s  ]+|[\s  ]+$/g, '');
}

/** Plain decimal for an input field, no currency symbol or grouping: 12340 → "12.34". */
export function formatPlain(milliunits: number, currency = 'USD'): string {
  const digits = fractionDigits(currency);
  let f = plainFormatters.get(currency);
  if (!f) {
    f = new Intl.NumberFormat('en-US', {
      minimumFractionDigits: digits,
      maximumFractionDigits: Math.max(digits, 3),
      useGrouping: false,
    });
    plainFormatters.set(currency, f);
  }
  return f.format(milliunits / 1000);
}

/**
 * Parse user input into milliunits. Accepts "12.34", "-5", "1,234.50", "1.234,50", "12,5",
 * "$ 1,000", "(12.00)" (accounting negative), "−3" (unicode minus). Empty → 0.
 * Returns null when the text is not a number.
 */
export function parseMoney(input: string): number | null {
  let s = input.trim().replace(/[\s  ']/g, '').replace(/−/g, '-');
  if (s === '') return 0;

  let negative = false;
  if (/^\(.*\)$/.test(s)) {
    negative = true;
    s = s.slice(1, -1);
  }
  // Strip currency symbols/codes around the number, keep sign.
  s = s.replace(/^[^\d.,+-]+/, '').replace(/[^\d.,]+$/, '');
  if (s.startsWith('-') || s.startsWith('+')) {
    if (s.startsWith('-')) negative = !negative;
    s = s.slice(1).replace(/^[^\d.,+-]+/, '');
  }
  if (s.startsWith('-') || s.startsWith('+')) return null;
  if (!/^[\d.,]+$/.test(s) || !/\d/.test(s)) return null;

  const lastDot = s.lastIndexOf('.');
  const lastComma = s.lastIndexOf(',');
  let intPart: string;
  let fracPart = '';

  if (lastDot >= 0 && lastComma >= 0) {
    // Both present: the last one is the decimal separator, the other groups thousands.
    const dec = lastDot > lastComma ? '.' : ',';
    const group = dec === '.' ? ',' : '.';
    const idx = s.lastIndexOf(dec);
    intPart = s.slice(0, idx);
    fracPart = s.slice(idx + 1);
    if (intPart.includes(dec) || fracPart.includes(group) || !validGrouping(intPart, group)) return null;
    intPart = intPart.split(group).join('');
  } else if (lastDot >= 0 || lastComma >= 0) {
    const sep = lastDot >= 0 ? '.' : ',';
    const parts = s.split(sep);
    if (parts.length > 2) {
      // "1,234,567" or "1.234.567": grouping only.
      if (!validGrouping(s, sep)) return null;
      intPart = parts.join('');
    } else {
      const [a, b] = parts as [string, string];
      // "1,234" is a thousands group; "12,5" / "12,50" / "1.5" are decimals.
      if (sep === ',' && b.length === 3 && a.length > 0 && a !== '0') {
        intPart = a + b;
      } else {
        intPart = a;
        fracPart = b;
      }
    }
  } else {
    intPart = s;
  }

  if (!/^\d*$/.test(intPart) || !/^\d*$/.test(fracPart)) return null;
  if (intPart === '' && fracPart === '') return null;

  // Integer arithmetic: milliunits = int*1000 + first 3 fraction digits, rounded on the 4th.
  const frac = (fracPart + '000').slice(0, 3);
  let value = Number(intPart || '0') * 1000 + Number(frac);
  if (fracPart.length > 3 && Number(fracPart[3]) >= 5) value += 1;
  if (!Number.isSafeInteger(value)) return null;
  return negative && value !== 0 ? -value : value;
}

function validGrouping(s: string, sep: string): boolean {
  if (!s.includes(sep)) return true;
  const groups = s.split(sep);
  return /^\d{1,3}$/.test(groups[0]!) && groups.slice(1).every((g) => /^\d{3}$/.test(g));
}

/** Round milliunits to the currency's minor unit (e.g. cents). */
export function roundToMinor(milliunits: number, currency: string): number {
  const step = 10 ** (3 - Math.min(3, fractionDigits(currency)));
  return Math.round(milliunits / step) * step;
}
