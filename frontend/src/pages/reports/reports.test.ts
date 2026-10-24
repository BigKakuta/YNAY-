import { describe, expect, it } from 'vitest';
import type { CashFlowRow, Payment, Subscription } from '../../api/types';
import { earliestStop, incomeTax, project, simulate, FIRE_DEFAULTS } from './plan';
import { activeMonths, deepDive, detectRecurring, incomeBySource, savingByAccount, spendingByCategory, thingsByMonth } from './stats';

const row = (r: Partial<CashFlowRow>): CashFlowRow => ({
  month: '2026-01',
  kind: 'SPENDING',
  categoryId: null,
  categoryName: null,
  groupId: null,
  groupName: null,
  payeeId: null,
  payeeName: null,
  subscriptionId: null,
  accountId: null,
  accountName: null,
  amount: 0,
  count: 1,
  ...r,
});

const pay = (date: string, amount: number, extra: Partial<Payment> = {}): Payment => ({
  transactionId: `${date}-${amount}`,
  splitId: null,
  date,
  time: null,
  accountId: 'a',
  accountName: 'Checking',
  payeeId: 'p1',
  payeeName: 'Shop',
  categoryId: 'c1',
  categoryName: 'Groceries',
  groupName: 'Needs',
  memo: null,
  amount,
  subscriptionId: null,
  review: null,
  ...extra,
});

describe('cash flow aggregations', () => {
  const rows = [
    row({ categoryId: 'g', categoryName: 'Groceries', payeeId: 'rewe', payeeName: 'REWE', amount: 40_000 }),
    row({ categoryId: 'g', categoryName: 'Groceries', payeeId: 'aldi', payeeName: 'Aldi', amount: 10_000 }),
    row({ categoryId: 'm', categoryName: 'Media', payeeId: 'sp', payeeName: 'Spotify', subscriptionId: 's1', amount: 9_990 }),
    row({ kind: 'INCOME', payeeId: 'emp', payeeName: 'Employer', amount: 3_000_000 }),
    row({ kind: 'INCOME', payeeId: 'bank', payeeName: 'Bank', amount: 1_000 }),
    row({ kind: 'SAVING', accountId: 'etf', accountName: 'ETF depot', amount: 400_000 }),
    row({ month: '2026-02', kind: 'SAVING', accountId: 'etf', accountName: 'ETF depot', amount: -100_000 }),
  ];

  it('groups spending by category with payees and the subscription share', () => {
    const cats = spendingByCategory(rows, new Set(['s1']));
    expect(cats.map((c) => [c.label, c.value, c.subscriptions])).toEqual([
      ['Groceries', 50_000, 0],
      ['Media', 9_990, 9_990],
    ]);
    expect(cats[0]!.payees.map((p) => p.label)).toEqual(['REWE', 'Aldi']);
  });

  it('sums income by source and saving by account', () => {
    expect(incomeBySource(rows).map((x) => [x.label, x.value])).toEqual([
      ['Employer', 3_000_000],
      ['Bank', 1_000],
    ]);
    expect(savingByAccount(rows)).toEqual([{ key: 'etf', label: 'ETF depot', value: 300_000 }]);
  });

  it('tracks things per month and skips months before the history starts', () => {
    const t = thingsByMonth(rows, ['2026-01', '2026-02'], ['g']);
    expect(t.map((m) => m.total)).toEqual([50_000, 0]);
    expect(t[0]!.topPayees[0]!.label).toBe('REWE');
    expect(activeMonths(['2025-11', '2025-12', '2026-01', '2026-02'], rows)).toEqual(['2026-01', '2026-02']);
  });
});

describe('spending deep dive', () => {
  it('computes per-month, weekly, hourly, weekday and size statistics', () => {
    const list = [
      pay('2026-01-05', 12_000, { time: '18:30', payeeName: 'REWE' }), // Monday
      pay('2026-01-06', 3_000, { time: '07:45', payeeName: 'Bakery', categoryName: 'Bakery' }),
      pay('2026-01-07', 25_000, { payeeName: 'REWE' }),
      pay('2026-01-10', 60_000, { time: '11:05', payeeName: 'REWE' }), // Saturday
      pay('2026-01-10', 2_000, { time: '11:40', payeeName: 'Bakery', categoryName: 'Bakery' }),
      pay('2026-01-12', -2_000, { payeeName: 'REWE' }), // refund
    ];
    const d = deepDive(list, ['2026-01'], '2026-01-31');
    expect(d.total).toBe(100_000);
    expect(d.count).toBe(5);
    expect(d.payees).toEqual(['REWE', 'Bakery']);
    expect(d.byPayee[0]!.values).toEqual([95_000]);
    expect(d.longestRun).toBe(3);
    expect(d.multiDays).toBe(1);
    expect(d.medianPayment).toBe(12_000);
    expect(d.saturdayAvg).toBe(31_000);
    expect(d.timeCoverage).toBe(0.8);
    expect(d.hours[0]).toEqual({ hour: 7, count: 1 });
    expect(d.hours.at(-1)).toEqual({ hour: 18, count: 1 });
    expect(d.weeks[0]).toMatchObject({ start: '2025-12-29', count: 0 });
    expect(d.weeks[1]).toMatchObject({ start: '2026-01-05', count: 5 });
    expect(d.sizeTypes).toEqual(['Groceries', 'Bakery']);
    expect(d.sizeCount[0]).toEqual([0, 0, 0, 0, 1, 0, 1, 0, 1]);
    expect(d.sizeCount[1]).toEqual([1, 1, 0, 0, 0, 0, 0, 0, 0]);
  });
});

describe('recurring payment detection', () => {
  it('suggests monthly charges of untracked payees only', () => {
    const monthly = ['2026-06-03', '2026-07-03', '2026-08-04', '2026-09-03'].map((d) => pay(d, 12_990, { payeeId: 'music', payeeName: 'MusicBox' }));
    const tracked = ['2026-06-05', '2026-07-05', '2026-08-05'].map((d) => pay(d, 9_990, { payeeId: 'tv', payeeName: 'StreamFlix' }));
    const random = ['2026-06-01', '2026-06-02', '2026-07-20'].map((d, i) => pay(d, 5_000 + i * 9_000, { payeeId: 'shop', payeeName: 'Shop' }));
    const transfer = ['2026-06-20', '2026-07-20', '2026-08-20'].map((d) => pay(d, 320_000, { payeeId: 'loan', payeeName: 'Transfer : Car loan' }));
    const subs = [{ payees: [{ id: 'tv', name: 'StreamFlix' }] } as Subscription];
    expect(detectRecurring([...monthly, ...tracked, ...random, ...transfer], subs)).toEqual([
      { payeeId: 'music', payeeName: 'MusicBox', cadence: 'MONTHLY', amount: 12_990, lastDate: '2026-09-03', count: 4 },
    ]);
  });
});

describe('planning calculators', () => {
  it('projects monthly investing after refilling the buffer', () => {
    const r = project(3000, 2000, 500, 0, 1000, 0, 1);
    expect(r.monthly).toBe(500);
    expect(r.bufferMonths).toBe(2);
    expect(r.points[1]!.value).toBe(5000);
    expect(project(3000, 2000, 500, 6, 0, 0, 20).points[20]!.value).toBeGreaterThan(500 * 12 * 20);
  });

  it('uses the 2026 German income tax brackets', () => {
    expect(incomeTax(12_348)).toBe(0);
    expect(incomeTax(20_000)).toBeGreaterThan(1_000);
    expect(incomeTax(100_000)).toBe(Math.floor(0.42 * 100_000 - 11_135.63));
  });

  it('finds an earlier stop age when investing more', () => {
    const base = { ...FIRE_DEFAULTS, age: 31, gross: 74_000, points: 4.5, netPay: 3750, spend: 2400 };
    const low = earliestStop({ ...base, invest: 478 });
    const high = earliestStop({ ...base, invest: 1500 });
    expect(high.stopAge).toBeLessThan(low.stopAge);
    expect(high.run.depletedAt).toBeNull();
    const broke = simulate({ ...base, invest: 0, start: 0 }, 40);
    expect(broke.depletedAt).not.toBeNull();
  });
});
