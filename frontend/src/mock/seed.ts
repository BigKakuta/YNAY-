// Realistic sample data for the mock backend, relative to the current month so it always looks fresh.
import type { Account, Budget, CategoryGroup } from '../api/types';
import { addMonths, currentMonth } from '../lib/dates';
import { createHandlers } from './actions';
import { dayIn, emptyState, type MockState } from './state';

export function seedState(): MockState {
  const s = emptyState();
  const h = createHandlers(s);
  const run = <T>(name: string, input: object) => h[name]!(input) as T;

  // ----- Household budget (USD) -----
  const budget = run<Budget>('create_budget', { name: 'Household', currencyCode: 'USD' });
  const b = budget.id;
  const sam = s.users.find((u) => u.username === 'sam')!;
  s.members.push({ budgetId: b, userId: sam.id, role: 'EDITOR' });

  const startDate = dayIn(-3, 1);
  const acct = (name: string, type: string, startingBalance: number, note: string | null = null) =>
    run<Account>('create_account', { budgetId: b, name, type, startingBalance, startingDate: startDate, note });
  const checking = acct('Checking', 'CHECKING', 3_250_000, 'Main account at the credit union');
  const savings = acct('Savings', 'SAVINGS', 8_000_000);
  const visa = acct('Visa', 'CREDIT_CARD', 0);
  const cash = acct('Wallet', 'CASH', 120_000);
  const brokerage = acct('Brokerage', 'ASSET', 15_400_000);
  const carLoan = acct('Car loan', 'LOAN', -9_800_000);

  const initial = run<CategoryGroup[]>('list_categories', { budgetId: b });
  const bills = initial.find((g) => g.name === 'Bills')!;
  run('create_category', { budgetId: b, groupId: bills.id, name: 'Car Payment' });
  run('create_category', { budgetId: b, groupId: initial.find((g) => g.name === 'Wants')!.id, name: 'Electronics' });
  run('create_category', { budgetId: b, groupId: initial.find((g) => g.name === 'Savings')!.id, name: 'Investing' });
  const groups = run<CategoryGroup[]>('list_categories', { budgetId: b });
  const cat = (name: string) => groups.flatMap((g) => g.categories).find((c) => c.name === name)!.id;
  const rta = cat('Inflow: Ready to Assign');

  const txn = (accountId: string, date: string, amount: number, payeeName: string, categoryName: string | null, extra: object = {}) =>
    run('create_transaction', {
      budgetId: b,
      accountId,
      date,
      amount,
      payeeName,
      categoryId: categoryName ? cat(categoryName) : null,
      cleared: 'CLEARED',
      ...extra,
    });

  const assignments: [string, number][] = [
    ['Rent/Mortgage', 1_450_000],
    ['Utilities', 140_000],
    ['Phone', 45_000],
    ['Internet', 60_000],
    ['Insurance', 110_000],
    ['Car Payment', 320_000],
    ['Groceries', 520_000],
    ['Transportation', 160_000],
    ['Medical', 50_000],
    ['Dining Out', 180_000],
    ['Entertainment', 80_000],
    ['Shopping', 120_000],
    ['Emergency Fund', 400_000],
    ['Vacation', 250_000],
    ['Investing', 300_000],
    ['Electronics', 60_000],
    ['Visa', 0],
  ];

  for (const offset of [-3, -2, -1, 0]) {
    const current = offset === 0;
    const extra = current ? { cleared: 'UNCLEARED' } : {};
    // Income
    txn(checking.id, dayIn(offset, 1), 2_450_000, 'Acme Corp Payroll', 'Inflow: Ready to Assign');
    if (!current || new Date().getDate() >= 15) txn(checking.id, dayIn(offset, 15), 2_450_000, 'Acme Corp Payroll', 'Inflow: Ready to Assign', extra);
    // Bills
    txn(checking.id, dayIn(offset, 2), -1_450_000, 'Parkside Apartments', 'Rent/Mortgage');
    txn(checking.id, dayIn(offset, 6), -(118_000 + offset * 4_000), 'City Power & Water', 'Utilities');
    txn(visa.id, dayIn(offset, 8), -45_000, 'Mobile Co', 'Phone');
    txn(checking.id, dayIn(offset, 9), -60_000, 'FiberNet', 'Internet');
    txn(checking.id, dayIn(offset, 12), -110_000, 'SafeHome Insurance', 'Insurance');
    // Groceries, weekly-ish on the card
    const groceryDays = current ? [3, 10, 17] : [3, 10, 17, 24];
    groceryDays.forEach((d, i) =>
      txn(visa.id, dayIn(offset, d), -(96_000 + ((i * 37 + offset * 13) % 50) * 1_000 + 470), i % 2 ? 'Green Market' : 'FreshCo', 'Groceries', {
        time: `${17 + (i % 3)}:${String((i * 17 + 5) % 60).padStart(2, '0')}`,
        ...(current && i === 2 ? extra : {}),
      }),
    );
    // Small top-up shops and the bakery, with card times
    const smallDays = current ? [2, 6, 9, 13, 16] : [2, 6, 9, 13, 16, 20, 23, 27];
    smallDays.forEach((d, i) => {
      if (dayIn(offset, d) !== `${addMonths(currentMonth(), offset)}-${String(d).padStart(2, '0')}`) return;
      txn(cash.id, dayIn(offset, d), -(4_200 + ((i * 53 + offset * 7) % 40) * 250), 'Corner Bakery', 'Groceries', { time: `0${7 + (i % 2)}:${String((i * 11) % 60).padStart(2, '0')}` });
      if (i % 2 === 0) txn(visa.id, dayIn(offset, d), -(12_000 + ((i * 29) % 30) * 500), 'FreshCo', 'Groceries', { time: `12:${String((i * 7) % 60).padStart(2, '0')}` });
    });
    // Subscriptions
    txn(visa.id, dayIn(offset, 3), -10_990, 'MusicBox', 'Entertainment');
    txn(visa.id, dayIn(offset, 14), -20_000, 'AI Assistant', 'Entertainment');
    if (offset <= -2) txn(visa.id, dayIn(offset, 22), -12_990, 'GameStream', 'Entertainment');
    // Investing: on-budget → tracking asset account counts as saving
    if (!current || new Date().getDate() >= 25) txn(checking.id, dayIn(offset, 25), -300_000, '', 'Investing', { transferAccountId: brokerage.id, payeeName: undefined, memo: 'Index fund savings plan' });
    txn(visa.id, dayIn(offset, 5), -54_300, 'Shell', 'Transportation');
    txn(visa.id, dayIn(offset, 19), -48_900, 'Shell', 'Transportation', current ? extra : {});
    txn(visa.id, dayIn(offset, 7), -38_500, 'Luigi’s Trattoria', 'Dining Out');
    txn(cash.id, dayIn(offset, 13), -14_000, 'Corner Café', 'Dining Out');
    if (offset !== -2) txn(visa.id, dayIn(offset, 21), -64_800, 'Sushi Place', 'Dining Out', current ? extra : {});
    txn(visa.id, dayIn(offset, 11), -15_990, 'StreamFlix', 'Entertainment');
    if (offset === -1) txn(visa.id, dayIn(offset, 16), -229_000, 'Outdoor Store', 'Shopping', { memo: 'Hiking boots' });
    // Card payment from checking (transfer) for last month's spending
    if (offset > -3) txn(checking.id, dayIn(offset, 4), -480_000, 'Transfer : Visa', null, { transferAccountId: visa.id, payeeName: undefined });
    // Savings transfer
    txn(checking.id, dayIn(offset, 16), -400_000, '', null, { transferAccountId: savings.id, payeeName: undefined, memo: 'Emergency fund', ...extra });
    // Car loan payment (on-budget → tracking transfer needs a category)
    txn(checking.id, dayIn(offset, 20), -320_000, '', 'Car Payment', { transferAccountId: carLoan.id, payeeName: undefined, memo: 'Car payment' });

    const month = addMonths(currentMonth(), offset);
    for (const [name, amount] of assignments) {
      const value = current && ['Vacation', 'Shopping', 'Entertainment'].includes(name) ? 0 : amount;
      if (value) run('assign_to_category', { budgetId: b, month, categoryId: cat(name), assigned: value });
    }
  }
  // Online orders, item by item
  run('create_transaction', {
    budgetId: b,
    accountId: visa.id,
    date: dayIn(-2, 9),
    amount: -208_890,
    payeeName: 'ShopMart Online',
    cleared: 'CLEARED',
    splits: [
      { amount: -129_000, categoryId: cat('Electronics'), memo: 'Wireless earbuds' },
      { amount: -39_900, categoryId: cat('Shopping'), memo: 'Desk lamp' },
      { amount: -12_990, categoryId: cat('Groceries'), memo: 'Coffee beans' },
      { amount: -27_000, categoryId: cat('Shopping'), memo: 'Bath towels (2)' },
    ],
  });
  txn(visa.id, dayIn(-1, 12), -34_990, 'ShopMart Online', 'Electronics', { memo: 'USB-C dock' });
  txn(visa.id, dayIn(-1, 18), -18_490, 'ShopMart Online', 'Shopping', { memo: 'Paperback: Deep Work' });
  txn(visa.id, dayIn(0, 4), -89_000, 'ShopMart Online', 'Electronics', { memo: 'Noise-cancelling headphones' });
  txn(visa.id, dayIn(-3, 15), -99_000, 'CloudDrive', 'Entertainment', { memo: 'Yearly plan' });
  // A split transaction
  run('create_transaction', {
    budgetId: b,
    accountId: visa.id,
    date: dayIn(-1, 26),
    amount: -183_400,
    payeeName: 'MegaMart',
    cleared: 'CLEARED',
    memo: 'Big shop',
    splits: [
      { amount: -121_400, categoryId: cat('Groceries'), memo: 'Food' },
      { amount: -62_000, categoryId: cat('Shopping'), memo: 'Kitchen stuff' },
    ],
  });
  // Brokerage growth (tracking account, no budget effect)
  run('create_transaction', { budgetId: b, accountId: brokerage.id, date: dayIn(-2, 28), amount: 310_000, payeeName: 'Market adjustment', cleared: 'CLEARED' });
  run('create_transaction', { budgetId: b, accountId: brokerage.id, date: dayIn(-1, 28), amount: -120_000, payeeName: 'Market adjustment', cleared: 'CLEARED' });
  // Unapproved imported transactions awaiting review
  run('import_transactions', {
    budgetId: b,
    accountId: checking.id,
    transactions: [
      { date: dayIn(0, 18), amount: -23_450, payeeName: 'Hardware Hut', memo: 'Card 1234' },
      { date: dayIn(0, 19), amount: -8_990, payeeName: 'StreamFlix' },
      { date: dayIn(0, 20), amount: 35_000, payeeName: 'Venmo Sam', memo: 'Concert tickets' },
    ],
  });

  // Targets
  const target = (name: string, type: string, amount: number, targetDate: string | null = null) =>
    run('set_category_target', { budgetId: b, categoryId: cat(name), type, amount, targetDate });
  target('Rent/Mortgage', 'MONTHLY_SPENDING', 1_450_000);
  target('Groceries', 'MONTHLY_SPENDING', 550_000);
  target('Emergency Fund', 'TARGET_BALANCE', 10_000_000);
  target('Vacation', 'TARGET_BALANCE_BY_DATE', 2_400_000, `${addMonths(currentMonth(), 6)}-01`);
  target('Dining Out', 'MONTHLY_SAVINGS', 200_000);
  run('update_category', { budgetId: b, categoryId: cat('Medical'), note: 'Co-pays and prescriptions' });

  // Subscriptions and fixed contracts
  const payeeId = (name: string) => s.payees.find((p) => p.budgetId === b && p.name === name)!.id;
  const sub = (name: string, payee: string, extra: object = {}) =>
    run('create_subscription', { budgetId: b, name, payeeIds: [payeeId(payee)], ...extra });
  sub('StreamFlix', 'StreamFlix', { groupName: 'Media' });
  sub('MusicBox', 'MusicBox', { groupName: 'Media' });
  sub('AI Assistant', 'AI Assistant', { groupName: 'AI', note: 'Pro plan; check the cheaper tier' });
  sub('CloudDrive', 'CloudDrive', { groupName: 'Digital', cadence: 'YEARLY' });
  sub('GameStream', 'GameStream', { groupName: 'Games', cancelledOn: dayIn(-1, 1) });
  sub('Rent', 'Parkside Apartments', { kind: 'FIXED', groupName: 'Home' });
  sub('Power & water', 'City Power & Water', { kind: 'FIXED', groupName: 'Home' });
  sub('Mobile phone', 'Mobile Co', { kind: 'FIXED', groupName: 'Home' });
  sub('Internet', 'FiberNet', { kind: 'FIXED', groupName: 'Home' });
  sub('Home insurance', 'SafeHome Insurance', { kind: 'FIXED', groupName: 'Insurance' });
  // Devices for the replacement budget
  const device = (name: string, price: number, purchasedOn: string, lifespanYears: number) =>
    run('create_device', { budgetId: b, name, price, purchasedOn, lifespanYears });
  device('Laptop', 1_899_000, `${addMonths(currentMonth(), -20)}-10`, 5);
  device('Phone', 899_000, `${addMonths(currentMonth(), -9)}-02`, 4);
  device('TV', 1_150_000, `${addMonths(currentMonth(), -38)}-21`, 8);
  device('Wireless earbuds', 129_000, dayIn(-2, 9), 3);
  // Report settings and a purchase review
  run('update_report_settings', {
    budgetId: b,
    settings: {
      thingsCategoryIds: [cat('Shopping'), cat('Electronics')],
      thingsMonthlyTarget: 250_000,
      focusCategoryIds: [cat('Groceries')],
      focusPayeeId: payeeId('ShopMart Online'),
      reviewMinAmount: 20_000,
      grossSalaryPerYear: 78_000_000,
      salaryPayeeId: payeeId('Acme Corp Payroll'),
    },
  });
  const dock = s.txns.find((t) => t.memo === 'USB-C dock')!;
  run('review_purchase', { budgetId: b, transactionId: dock.id, rating: 4 });

  // Park the opening balances in the emergency fund so Ready to Assign shows a realistic remainder.
  const first = addMonths(currentMonth(), -3);
  const rtaNow = run<{ readyToAssign: number }>('get_month_budget', { budgetId: b, month: currentMonth() }).readyToAssign;
  const ef = cat('Emergency Fund');
  run('assign_to_category', {
    budgetId: b,
    month: first,
    categoryId: ef,
    assigned: (s.assignments[`${ef}|${first}`] ?? 0) + rtaNow - 412_500,
  });
  void rta;

  // ----- Second, shared budget (EUR) -----
  const holiday = run<Budget>('create_budget', { name: 'Holiday flat', currencyCode: 'EUR', withDefaultCategories: false });
  const hg = run<CategoryGroup>('create_category_group', { budgetId: holiday.id, name: 'Running costs' });
  for (const n of ['Cleaning', 'Repairs', 'Utilities']) run('create_category', { budgetId: holiday.id, groupId: hg.id, name: n });
  run('create_account', { budgetId: holiday.id, name: 'Joint account', type: 'CHECKING', startingBalance: 1_800_000, startingDate: startDate });
  const owner = s.members.find((m) => m.budgetId === holiday.id)!;
  owner.userId = sam.id;
  s.members.push({ budgetId: holiday.id, userId: s.meId, role: 'EDITOR' });

  // An API token
  run('create_api_token', { name: 'Claude Desktop', expiresInDays: 90 });
  s.tokens[0]!.lastUsedAt = new Date(Date.now() - 3 * 3600_000).toISOString();
  void cash;
  return s;
}
