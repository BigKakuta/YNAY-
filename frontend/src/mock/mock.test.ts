import { describe, expect, it } from 'vitest';
import type { Account, Budget, CategoryGroup, MonthBudget } from '../api/types';
import { currentMonth } from '../lib/dates';
import { createHandlers } from './actions';
import { parseStatement } from './agent';
import { seedState } from './seed';
import { emptyState } from './state';

function setup() {
  const s = emptyState();
  const h = createHandlers(s);
  const run = <T>(name: string, input: object) => h[name]!(input) as T;
  const budget = run<Budget>('create_budget', { name: 'Test', currencyCode: 'USD' });
  const b = budget.id;
  const groups = run<CategoryGroup[]>('list_categories', { budgetId: b });
  const cat = (name: string) => groups.flatMap((g) => g.categories).find((c) => c.name === name)!.id;
  const month = (m: string) => run<MonthBudget>('get_month_budget', { budgetId: b, month: m });
  const avail = (mb: MonthBudget, id: string) => mb.groups.flatMap((g) => g.categories).find((c) => c.categoryId === id)!;
  return { s, run, b, cat, month, avail };
}

describe('mock month math', () => {
  it('creates system groups and default categories', () => {
    const { run, b } = setup();
    const groups = run<CategoryGroup[]>('list_categories', { budgetId: b });
    expect(groups.find((g) => g.systemKind === 'INTERNAL')?.categories[0]?.systemKind).toBe('READY_TO_ASSIGN');
    expect(groups.some((g) => g.systemKind === 'CREDIT_CARD_PAYMENTS')).toBe(true);
    expect(groups.map((g) => g.name)).toEqual(expect.arrayContaining(['Bills', 'Needs', 'Wants', 'Savings']));
  });

  it('computes ready to assign, carryover and overspending', () => {
    const { run, b, cat, month, avail } = setup();
    const checking = run<Account>('create_account', { budgetId: b, name: 'Checking', type: 'CHECKING', startingBalance: 1_000_000, startingDate: '2026-01-05' });
    expect(month('2026-01').readyToAssign).toBe(1_000_000);

    run('assign_to_category', { budgetId: b, month: '2026-01', categoryId: cat('Groceries'), assigned: 300_000 });
    run('assign_to_category', { budgetId: b, month: '2026-01', categoryId: cat('Dining Out'), assigned: 50_000 });
    run('create_transaction', { budgetId: b, accountId: checking.id, date: '2026-01-10', amount: -100_000, payeeName: 'Shop', categoryId: cat('Groceries') });
    run('create_transaction', { budgetId: b, accountId: checking.id, date: '2026-01-11', amount: -80_000, payeeName: 'Cafe', categoryId: cat('Dining Out') });

    const jan = month('2026-01');
    expect(jan.readyToAssign).toBe(650_000);
    expect(avail(jan, cat('Groceries')).available).toBe(200_000);
    expect(avail(jan, cat('Dining Out')).available).toBe(-30_000);

    const feb = month('2026-02');
    expect(avail(feb, cat('Groceries')).carryover).toBe(200_000);
    expect(avail(feb, cat('Dining Out')).carryover).toBe(0); // overspending doesn't roll over
    expect(feb.overspentLastMonth).toBe(30_000);
    expect(feb.readyToAssign).toBe(620_000); // ...it hits RTA next month

    // Future assignments reduce RTA immediately.
    run('assign_to_category', { budgetId: b, month: '2026-03', categoryId: cat('Vacation'), assigned: 20_000 });
    expect(month('2026-01').readyToAssign).toBe(630_000);
  });

  it('moves spending on a credit card into its payment category', () => {
    const { run, b, cat, month, avail } = setup();
    const checking = run<Account>('create_account', { budgetId: b, name: 'Checking', type: 'CHECKING', startingBalance: 500_000, startingDate: '2026-01-01' });
    const visa = run<Account>('create_account', { budgetId: b, name: 'Visa', type: 'CREDIT_CARD', startingDate: '2026-01-01' });
    const groups = run<CategoryGroup[]>('list_categories', { budgetId: b });
    const payment = groups.flatMap((g) => g.categories).find((c) => c.linkedAccountId === visa.id)!;
    expect(payment.systemKind).toBe('CREDIT_CARD_PAYMENT');

    run('assign_to_category', { budgetId: b, month: '2026-01', categoryId: cat('Groceries'), assigned: 100_000 });
    run('create_transaction', { budgetId: b, accountId: visa.id, date: '2026-01-03', amount: -50_000, payeeName: 'Shop', categoryId: cat('Groceries') });
    expect(avail(month('2026-01'), payment.id).available).toBe(50_000);

    run('create_transaction', { budgetId: b, accountId: checking.id, date: '2026-01-20', amount: -50_000, transferAccountId: visa.id });
    const jan = month('2026-01');
    expect(avail(jan, payment.id).available).toBe(0);
    expect(jan.uncategorized).toBe(0);
    const accounts = run<Account[]>('list_accounts', { budgetId: b });
    expect(accounts.find((a) => a.id === visa.id)!.balance).toBe(0);
  });

  it('computes target needs and assigns underfunded', () => {
    const { run, b, cat, month, avail } = setup();
    run('create_account', { budgetId: b, name: 'Checking', type: 'CHECKING', startingBalance: 2_000_000, startingDate: '2026-01-01' });
    run('set_category_target', { budgetId: b, categoryId: cat('Groceries'), type: 'MONTHLY_SPENDING', amount: 400_000 });
    run('set_category_target', { budgetId: b, categoryId: cat('Vacation'), type: 'TARGET_BALANCE_BY_DATE', amount: 1_200_000, targetDate: '2026-06-01' });
    const jan = month('2026-01');
    expect(avail(jan, cat('Groceries')).targetNeeded).toBe(400_000);
    expect(avail(jan, cat('Vacation')).targetNeeded).toBe(200_000);
    run('assign_underfunded', { budgetId: b, month: '2026-01' });
    const after = month('2026-01');
    expect(avail(after, cat('Groceries')).targetNeeded).toBe(0);
    expect(avail(after, cat('Vacation')).assigned).toBe(200_000);
    expect(after.readyToAssign).toBe(1_400_000);
  });

  it('deduplicates imports and leaves them unapproved', () => {
    const { run, b } = setup();
    const acct = run<Account>('create_account', { budgetId: b, name: 'Checking', type: 'CHECKING' });
    const lines = [
      { date: '2026-02-01', amount: -5_000, payeeName: 'Coffee' },
      { date: '2026-02-01', amount: -5_000, payeeName: 'Coffee' },
    ];
    expect(run('import_transactions', { budgetId: b, accountId: acct.id, transactions: lines })).toMatchObject({ imported: 2, duplicates: 0 });
    expect(run('import_transactions', { budgetId: b, accountId: acct.id, transactions: lines })).toMatchObject({ imported: 0, duplicates: 2 });
    const list = run<{ total: number }>('list_transactions', { budgetId: b, unapprovedOnly: true });
    expect(list.total).toBe(2);
  });

  it('seeds a consistent sample budget', () => {
    const s = seedState();
    const h = createHandlers(s);
    const budgets = h.list_budgets!({}) as Budget[];
    expect(budgets.map((x) => x.name)).toEqual(['Household', 'Holiday flat']);
    const mb = h.get_month_budget!({ budgetId: budgets[0]!.id, month: currentMonth() }) as MonthBudget;
    expect(mb.readyToAssign).toBe(412_500);
  });
});

describe('mock reports', () => {
  it('classifies cash flow like the backend and derives subscription figures', () => {
    const { run, b, cat } = setup();
    const checking = run<Account>('create_account', { budgetId: b, name: 'Checking', type: 'CHECKING', startingBalance: 1_000_000, startingDate: '2026-01-01' });
    const depot = run<Account>('create_account', { budgetId: b, name: 'Depot', type: 'ASSET', startingBalance: 0, startingDate: '2026-01-01' });
    const txn = (date: string, amount: number, payeeName: string, categoryName: string | null, extra: object = {}) =>
      run('create_transaction', { budgetId: b, accountId: checking.id, date, amount, payeeName, categoryId: categoryName ? cat(categoryName) : null, ...extra });
    txn('2026-01-25', 3_000_000, 'Employer', 'Inflow: Ready to Assign');
    txn('2026-01-03', -40_000, 'REWE', 'Groceries', { time: '9:05' });
    txn('2026-01-12', -400_000, '', 'Emergency Fund', { transferAccountId: depot.id, payeeName: undefined });
    txn('2026-01-20', -9_990, 'Spotify', 'Entertainment');
    txn('2026-02-20', -9_990, 'Spotify', 'Entertainment');
    const spotify = run<{ id: string; name: string }[]>('list_payees', { budgetId: b }).find((p) => p.name === 'Spotify')!;
    run('create_subscription', { budgetId: b, name: 'Spotify', payeeIds: [spotify.id] });

    const flow = run<{ months: { income: number; spending: number; saving: number; subscriptions: number }[] }>('report_cash_flow', {
      budgetId: b,
      fromMonth: '2026-01',
      toMonth: '2026-02',
    });
    expect(flow.months[0]).toMatchObject({ income: 3_000_000, spending: 49_990, saving: 400_000, subscriptions: 9_990 });
    const payments = run<{ payments: { time: string | null; amount: number }[] }>('report_payments', { budgetId: b, categoryIds: [cat('Groceries')] });
    expect(payments.payments).toEqual([expect.objectContaining({ time: '09:05', amount: 40_000 })]);
    const [sub] = run<{ chargeCount: number; monthlyCost: number; lastCharge: string; nextCharge: string }[]>('list_subscriptions', {
      budgetId: b,
      asOf: '2026-03-01',
    });
    expect(sub).toMatchObject({ chargeCount: 2, monthlyCost: 9_990, lastCharge: '2026-02-20', nextCharge: '2026-03-20' });
  });
});

describe('mock agent statement parser', () => {
  it('extracts date, payee and amount from CSV and free text', () => {
    expect(parseStatement('Date,Description,Amount\n2026-10-21,Bookshop,-24.90\n22.10.2026;Bakery;-6,40\n10/23/2026 Refund 45.00')).toEqual([
      { date: '2026-10-21', amount: -24_900, payeeName: 'Bookshop' },
      { date: '2026-10-22', amount: -6_400, payeeName: 'Bakery' },
      { date: '2026-10-23', amount: 45_000, payeeName: 'Refund' },
    ]);
  });
});
