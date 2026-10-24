// Pure aggregations behind the Reports page. Amounts are milliunits; months YYYY-MM; dates YYYY-MM-DD.
import type { Cadence, CashFlowRow, Payment, Subscription } from '../../api/types';
import { addMonths, currentMonth, monthRange, todayIso } from '../../lib/dates';

export type Ranked = { key: string; label: string; value: number };

const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);

export function median(xs: number[]): number {
  if (!xs.length) return 0;
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2;
}

/**
 * The period's months from the first one with any data: averages shouldn't count the months
 * before the budget's history starts.
 */
export function activeMonths(months: string[], rows: { month: string }[]): string[] {
  const first = rows.reduce<string | null>((a, r) => (a === null || r.month < a ? r.month : a), null);
  return first === null ? months : months.filter((m) => m >= first);
}

/** Months of the period that are over (the running month counts as partial). */
export function fullMonthCount(months: string[], now = currentMonth()) {
  return months.filter((m) => m < now).length;
}

/** Length of the period in months, counting the running month by its elapsed days. */
export function monthSpan(months: string[], today = todayIso()) {
  const now = today.slice(0, 7);
  let n = 0;
  for (const m of months) {
    if (m < now) n += 1;
    else if (m === now) {
      const [y, mo] = m.split('-').map(Number) as [number, number];
      n += Number(today.slice(8, 10)) / new Date(y, mo, 0).getDate();
    }
  }
  return Math.max(n, 1 / 30);
}

// ------------------------------------------------------------------ overview

export type CategorySpend = {
  key: string;
  categoryId: string | null;
  label: string;
  groupName: string | null;
  value: number;
  subscriptions: number;
  payees: Ranked[];
};

export function spendingByCategory(rows: CashFlowRow[], subscriptionIds: Set<string>): CategorySpend[] {
  const by = new Map<string, CategorySpend & { payeeMap: Map<string, Ranked> }>();
  for (const r of rows) {
    if (r.kind !== 'SPENDING') continue;
    const key = r.categoryId ?? 'uncategorized';
    const c = by.get(key) ?? {
      key,
      categoryId: r.categoryId,
      label: r.categoryName ?? 'Uncategorized',
      groupName: r.groupName,
      value: 0,
      subscriptions: 0,
      payees: [],
      payeeMap: new Map<string, Ranked>(),
    };
    c.value += r.amount;
    if (r.subscriptionId && subscriptionIds.has(r.subscriptionId)) c.subscriptions += r.amount;
    const pk = r.payeeId ?? 'none';
    const p = c.payeeMap.get(pk) ?? { key: pk, label: r.payeeName ?? '(no payee)', value: 0 };
    p.value += r.amount;
    c.payeeMap.set(pk, p);
    by.set(key, c);
  }
  return [...by.values()]
    .map(({ payeeMap, ...c }) => ({ ...c, payees: [...payeeMap.values()].filter((p) => p.value > 0).sort((a, b) => b.value - a.value) }))
    .filter((c) => c.value > 0)
    .sort((a, b) => b.value - a.value);
}

/** Income per payee (largest first), the tail folded into "Other income". */
export function incomeBySource(rows: CashFlowRow[], top = 5): Ranked[] {
  const by = new Map<string, Ranked>();
  for (const r of rows) {
    if (r.kind !== 'INCOME') continue;
    const k = r.payeeId ?? 'none';
    const x = by.get(k) ?? { key: k, label: r.payeeName ?? 'Other income', value: 0 };
    x.value += r.amount;
    by.set(k, x);
  }
  const list = [...by.values()].sort((a, b) => b.value - a.value);
  if (list.length <= top + 1) return list.filter((x) => x.value !== 0);
  const rest = sum(list.slice(top).map((x) => x.value));
  return [...list.slice(0, top), { key: 'other', label: 'Other income', value: rest }].filter((x) => x.value !== 0);
}

/** Net money put into each tracking account (negative = taken out). */
export function savingByAccount(rows: CashFlowRow[]): Ranked[] {
  const by = new Map<string, Ranked>();
  for (const r of rows) {
    if (r.kind !== 'SAVING') continue;
    const k = r.accountId ?? 'none';
    const x = by.get(k) ?? { key: k, label: r.accountName ?? 'Savings', value: 0 };
    x.value += r.amount;
    by.set(k, x);
  }
  return [...by.values()].filter((x) => x.value !== 0).sort((a, b) => b.value - a.value);
}

// ------------------------------------------------------------------ things

export type ThingsMonth = { month: string; byCategory: number[]; total: number; topPayees: Ranked[] };

export function thingsByMonth(rows: CashFlowRow[], months: string[], categoryIds: string[]): ThingsMonth[] {
  return months.map((month) => {
    const mine = rows.filter((r) => r.kind === 'SPENDING' && r.month === month && r.categoryId && categoryIds.includes(r.categoryId));
    const byCategory = categoryIds.map((id) => Math.max(0, sum(mine.filter((r) => r.categoryId === id).map((r) => r.amount))));
    const payees = new Map<string, Ranked>();
    for (const r of mine) {
      const k = r.payeeId ?? 'none';
      const p = payees.get(k) ?? { key: k, label: r.payeeName ?? '(no payee)', value: 0 };
      p.value += r.amount;
      payees.set(k, p);
    }
    return {
      month,
      byCategory,
      total: sum(byCategory),
      topPayees: [...payees.values()].filter((p) => p.value > 0).sort((a, b) => b.value - a.value).slice(0, 4),
    };
  });
}

/** Average monthly spending in the given categories over the full months of the rows. */
export function monthlyAverage(rows: CashFlowRow[], months: string[], filter: (r: CashFlowRow) => boolean, now = currentMonth()) {
  const full = months.filter((m) => m < now);
  if (!full.length) return 0;
  return sum(rows.filter((r) => full.includes(r.month) && filter(r)).map((r) => r.amount)) / full.length;
}

// ------------------------------------------------------------------ spending deep dive

export const SIZE_BUCKETS = [2_500, 5_000, 7_500, 10_000, 15_000, 20_000, 30_000, 50_000];

export function bucketLabels(fmt: (v: number) => string) {
  return SIZE_BUCKETS.map((t, i) => (i === 0 ? `<${fmt(t)}` : `${fmt(SIZE_BUCKETS[i - 1]!)}–${fmt(t)}`)).concat(`${fmt(SIZE_BUCKETS.at(-1)!)}+`);
}

const bucketOf = (amount: number) => {
  const i = SIZE_BUCKETS.findIndex((t) => amount < t);
  return i < 0 ? SIZE_BUCKETS.length : i;
};

const dayIndex = (date: string) => {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  return Math.round(Date.UTC(y, m - 1, d) / 86_400_000);
};
const fromDayIndex = (i: number) => new Date(i * 86_400_000).toISOString().slice(0, 10);
/** 0 = Monday … 6 = Sunday */
const weekday = (date: string) => (new Date(dayIndex(date) * 86_400_000).getUTCDay() + 6) % 7;

export type DeepDive = {
  total: number;
  count: number;
  perMonth: number;
  paymentsPerWeek: number;
  dayShare: number;
  multiDays: number;
  longestRun: number;
  medianPayment: number;
  weekdayAvg: number;
  saturdayAvg: number;
  timeCoverage: number;
  payees: string[];
  /** months × top payees (+ "Other" as the last entry when needed) */
  byPayee: { label: string; values: number[] }[];
  monthCount: number[];
  monthAvg: number[];
  weeks: { start: string; count: number; amount: number; avg4: number | null }[];
  lastWeekPartial: boolean;
  hours: { hour: number; count: number }[];
  weekdays: { count: number; avg: number }[];
  sizeTypes: string[];
  sizeCount: number[][];
  sizeSum: number[][];
  smallShareCount: number;
  smallShareSum: number;
  bigShareCount: number;
  bigShareSum: number;
};

/** Everything the spending deep dive shows, from the period's payments (refunds count against the total). */
export function deepDive(payments: Payment[], months: string[], today = todayIso(), topPayees = 6): DeepDive {
  const spend = payments.filter((p) => p.amount > 0);
  const total = sum(payments.map((p) => p.amount));
  const earliest = payments.reduce<string | null>((a, p) => (a === null || p.date < a ? p.date : a), null);
  const first = earliest && earliest.slice(0, 7) > months[0]! ? `${earliest.slice(0, 7)}-01` : `${months[0]}-01`;
  const lastMonth = months.at(-1)!;
  const [ly, lm] = lastMonth.split('-').map(Number) as [number, number];
  const periodEnd = `${lastMonth}-${String(new Date(ly, lm, 0).getDate()).padStart(2, '0')}`;
  const end = today < periodEnd ? today : periodEnd;
  const days = Math.max(1, dayIndex(end) - dayIndex(first) + 1);

  const perPayee = new Map<string, number>();
  for (const p of payments) perPayee.set(p.payeeName ?? '(no payee)', (perPayee.get(p.payeeName ?? '(no payee)') ?? 0) + p.amount);
  const ranked = [...perPayee].sort((a, b) => b[1] - a[1]).map(([n]) => n);
  const shown = ranked.length > topPayees + 1 ? ranked.slice(0, topPayees) : ranked;
  const byPayee = shown.map((label) => ({ label, values: months.map(() => 0) }));
  const other = { label: 'Other', values: months.map(() => 0) };
  const monthCount = months.map(() => 0);
  const monthSum = months.map(() => 0);
  for (const p of payments) {
    const mi = months.indexOf(p.date.slice(0, 7));
    if (mi < 0) continue;
    const s = byPayee.find((x) => x.label === (p.payeeName ?? '(no payee)')) ?? other;
    s.values[mi]! += p.amount;
    if (p.amount > 0) {
      monthCount[mi]!++;
      monthSum[mi]! += p.amount;
    }
  }
  if (ranked.length > shown.length) byPayee.push(other);

  // days with payments
  const perDay = new Map<number, number>();
  for (const p of spend) perDay.set(dayIndex(p.date), (perDay.get(dayIndex(p.date)) ?? 0) + 1);
  let longestRun = 0;
  let run = 0;
  for (let d = dayIndex(first); d <= dayIndex(end); d++) {
    if (perDay.has(d)) longestRun = Math.max(longestRun, ++run);
    else if (weekday(fromDayIndex(d)) !== 6) run = 0; // shops are often closed on Sundays
  }

  // weeks (Monday-based)
  const firstMonday = dayIndex(first) - weekday(first);
  const weeks: DeepDive['weeks'] = [];
  for (let w = firstMonday; w <= dayIndex(end); w += 7) weeks.push({ start: fromDayIndex(w), count: 0, amount: 0, avg4: null });
  for (const p of spend) {
    const wi = Math.floor((dayIndex(p.date) - firstMonday) / 7);
    if (weeks[wi]) {
      weeks[wi]!.count++;
      weeks[wi]!.amount += p.amount;
    }
  }
  const lastWeekPartial = dayIndex(end) - (dayIndex(weeks.at(-1)?.start ?? end)) < 6;
  const complete = lastWeekPartial ? weeks.length - 1 : weeks.length;
  weeks.forEach((w, i) => {
    if (i >= 3 && i < complete) w.avg4 = sum(weeks.slice(i - 3, i + 1).map((x) => x.count)) / 4;
  });

  const timed = spend.filter((p) => p.time);
  const hourCounts = Array.from({ length: 24 }, () => 0);
  for (const p of timed) hourCounts[Number(p.time!.slice(0, 2))]!++;
  const used = hourCounts.map((c, h) => (c ? h : -1)).filter((h) => h >= 0);
  const hours = used.length ? hourCounts.slice(used[0], used.at(-1)! + 1).map((count, i) => ({ hour: used[0]! + i, count })) : [];

  const wd = Array.from({ length: 7 }, () => ({ n: 0, s: 0 }));
  for (const p of spend) {
    const x = wd[weekday(p.date)]!;
    x.n++;
    x.s += p.amount;
  }
  const weekdays = wd.map((x) => ({ count: x.n, avg: x.n ? x.s / x.n : 0 }));
  const workdays = wd.slice(0, 5);
  const weekdayAvg = sum(workdays.map((x) => x.s)) / Math.max(1, sum(workdays.map((x) => x.n)));

  // payment sizes by category (top 3 + other)
  const catTotals = new Map<string, number>();
  for (const p of spend) catTotals.set(p.categoryName ?? 'Uncategorized', (catTotals.get(p.categoryName ?? 'Uncategorized') ?? 0) + p.amount);
  const cats = [...catTotals].sort((a, b) => b[1] - a[1]).map(([c]) => c);
  const sizeTypes = cats.length > 4 ? [...cats.slice(0, 3), 'Other'] : cats;
  const nb = SIZE_BUCKETS.length + 1;
  const sizeCount = sizeTypes.map(() => Array.from({ length: nb }, () => 0));
  const sizeSum = sizeTypes.map(() => Array.from({ length: nb }, () => 0));
  for (const p of spend) {
    let ti = sizeTypes.indexOf(p.categoryName ?? 'Uncategorized');
    if (ti < 0) ti = sizeTypes.length - 1;
    const b = bucketOf(p.amount);
    sizeCount[ti]![b]!++;
    sizeSum[ti]![b]! += p.amount;
  }
  const spendTotal = Math.max(1, sum(spend.map((p) => p.amount)));
  const small = spend.filter((p) => p.amount < 10_000);
  const big = spend.filter((p) => p.amount >= 20_000);
  const n = Math.max(1, spend.length);

  return {
    total,
    count: spend.length,
    perMonth: total / Math.max(days / 30.44, 1 / 30),
    paymentsPerWeek: spend.length / (days / 7),
    dayShare: perDay.size / days,
    multiDays: [...perDay.values()].filter((c) => c >= 2).length,
    longestRun,
    medianPayment: median(spend.map((p) => p.amount)),
    weekdayAvg,
    saturdayAvg: weekdays[5]!.avg,
    timeCoverage: spend.length ? timed.length / spend.length : 0,
    payees: byPayee.map((s) => s.label),
    byPayee,
    monthCount,
    monthAvg: monthCount.map((c, i) => (c ? monthSum[i]! / c : 0)),
    weeks,
    lastWeekPartial,
    hours,
    weekdays,
    sizeTypes,
    sizeCount,
    sizeSum,
    smallShareCount: small.length / n,
    smallShareSum: sum(small.map((p) => p.amount)) / spendTotal,
    bigShareCount: big.length / n,
    bigShareSum: sum(big.map((p) => p.amount)) / spendTotal,
  };
}

// ------------------------------------------------------------------ subscriptions

export type RecurringSuggestion = { payeeId: string; payeeName: string; cadence: Cadence; amount: number; lastDate: string; count: number };

/**
 * Payees charged a similar amount about once a month (or year) that aren't tracked yet:
 * at least three charges within 15% of their median, spaced 25–35 days apart on average.
 */
export function detectRecurring(payments: Payment[], tracked: Subscription[]): RecurringSuggestion[] {
  const linked = new Set(tracked.flatMap((s) => s.payees.map((p) => p.id)));
  const by = new Map<string, Payment[]>();
  for (const p of payments) {
    if (!p.payeeId || p.amount <= 0 || p.subscriptionId || linked.has(p.payeeId) || p.payeeName?.startsWith('Transfer : ')) continue;
    by.set(p.payeeId, [...(by.get(p.payeeId) ?? []), p]);
  }
  const out: RecurringSuggestion[] = [];
  for (const [payeeId, list] of by) {
    const sorted = [...list].sort((a, b) => a.date.localeCompare(b.date));
    const med = median(sorted.map((p) => p.amount));
    const similar = sorted.filter((p) => Math.abs(p.amount - med) <= med * 0.15);
    if (similar.length < 3 || similar.length < sorted.length * 0.6) continue;
    const gaps = similar.slice(1).map((p, i) => dayIndex(p.date) - dayIndex(similar[i]!.date));
    const gap = median(gaps);
    if (gap < 25 || gap > 35) continue;
    out.push({ payeeId, payeeName: similar[0]!.payeeName ?? '', cadence: 'MONTHLY', amount: Math.round(med), lastDate: similar.at(-1)!.date, count: similar.length });
  }
  return out.sort((a, b) => b.amount - a.amount).slice(0, 10);
}

/** Subscription months shown in the "last 12 months" totals: the 12 full months before this one. */
export function lastTwelveMonths(now = currentMonth()) {
  return monthRange(addMonths(now, -12), addMonths(now, -1));
}
