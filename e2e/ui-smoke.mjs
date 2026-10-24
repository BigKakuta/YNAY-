#!/usr/bin/env node
// End-to-end UI smoke test of the whole YNAY stack: real Kanidm login, then a budget built and
// checked entirely through the React UI. See e2e/README.md for how to start the stack.
//
//   set -a; source .kanidm-dev/ynay.env; set +a
//   node e2e/ui-smoke.mjs            # screenshots + mcp-token.txt land in e2e/out/
//
// Env: YNAY_PUBLIC_URL (default http://localhost:8080), YNAY_DEV_USER, YNAY_DEV_USER_PASSWORD,
// E2E_OUT (default e2e/out), E2E_HEADED=1 to watch it.
//
// The import assistant step needs the backend started with the fake LLM from e2e/fake-openai.mjs
// (YNAY_AGENT_PROVIDER=openai, YNAY_AGENT_BASE_URL=http://localhost:4010/v1, YNAY_AGENT_MODEL=fake).
// Without an enabled agent the same CSV is imported through the import_transactions action instead.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const APP = (process.env.YNAY_PUBLIC_URL || 'http://localhost:8080').replace(/\/$/, '');
const USER = process.env.YNAY_DEV_USER || 'alice';
const PASSWORD = process.env.YNAY_DEV_USER_PASSWORD;
const OUT = process.env.E2E_OUT || path.join(path.dirname(fileURLToPath(import.meta.url)), 'out');
if (!PASSWORD) {
  console.error('YNAY_DEV_USER_PASSWORD is not set (source .kanidm-dev/ynay.env)');
  process.exit(2);
}
fs.mkdirSync(OUT, { recursive: true });

// ---------------------------------------------------------------------------------- helpers

const failures = [];
function check(cond, msg) {
  if (cond) console.log('ok   ' + msg);
  else {
    console.log('FAIL ' + msg);
    failures.push(msg);
  }
  return cond;
}
function must(cond, msg) {
  if (!check(cond, msg)) throw new Error('aborting: ' + msg);
}

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
/** milliunits -> "$1,234.50" / "-$12.50", as the SPA renders it in en-US. */
const fmt = (m) => usd.format(m / 1000);

const pad = (n) => String(n).padStart(2, '0');
const now = new Date();
const TODAY = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
const MONTH = TODAY.slice(0, 7);
const NEXT_MONTH = (() => {
  const d = new Date(now.getFullYear(), now.getMonth() + 1, 1);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}`;
})();
const STAMP = now.toISOString().slice(0, 19).replace('T', ' ');
const BUDGET = `E2E Household ${STAMP}`;
const TOKEN_NAME = `e2e mcp-smoke ${STAMP}`;

let shot = 0;
async function snap(page, name) {
  const file = path.join(OUT, `${pad(++shot)}-${name}.png`);
  await page.waitForTimeout(250); // let toasts / refetches settle
  await page.screenshot({ path: file, fullPage: true });
  console.log('     screenshot ' + file);
}

/** Waits for POST /api/actions/<name> (optionally more than one) triggered by `fn`. */
async function action(page, name, fn) {
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith('/api/actions/' + name) && r.request().method() === 'POST', { timeout: 15000 }),
    fn(),
  ]);
  const body = await res.json().catch(() => null);
  must(res.ok(), `${name} -> ${res.status()} ${res.ok() ? '' : JSON.stringify(body)}`);
  return body;
}

/** Calls an action directly with the browser's session (for cross-checking what the UI shows). */
async function api(page, name, input) {
  const xsrf = (await page.context().cookies(APP)).find((c) => c.name === 'XSRF-TOKEN')?.value;
  const res = await page.request.post(`${APP}/api/actions/${name}`, {
    data: input,
    headers: { 'X-XSRF-TOKEN': xsrf ?? '', Accept: 'application/json' },
  });
  const body = await res.json();
  if (!res.ok()) throw new Error(`${name}: ${res.status()} ${JSON.stringify(body)}`);
  return body;
}

// In-flight /api requests of the page. After a mutation the SPA invalidates and refetches its
// queries, so "settled" means: no /api request in flight for a little while.
let inflight = 0;
async function settle(page, quietMs = 300) {
  let quietSince = Date.now();
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    await page.waitForTimeout(50);
    if (inflight > 0) quietSince = Date.now();
    else if (Date.now() - quietSince >= quietMs) return;
  }
  console.log('     (settle: requests still in flight after 15s)');
}

/** Polls `fn` until it returns true (or `timeout` ms pass), then records the check. */
async function eventually(fn, msg, timeout = 5000) {
  const deadline = Date.now() + timeout;
  let ok = false;
  for (;;) {
    try {
      ok = !!(await fn());
    } catch {
      ok = false;
    }
    if (ok || Date.now() > deadline) break;
    await new Promise((r) => setTimeout(r, 100));
  }
  return check(ok, msg);
}

// ---------------------------------------------------------------------------------- scenario
//
// Amounts in milliunits. All transactions are dated today, so everything lands in this month.
//
//   Checking  starting balance           +3000.00   Ready to Assign (income)
//   Visa      starting balance owed       -200.00   no budget effect
//   Checking  Paycheck                    +2000.00   Ready to Assign (income)
//   Checking  Whole Foods                   -85.20   Groceries, memo "weekly shop"
//   Checking  Target (split)               -120.00   Groceries -70.00 / Shopping -50.00
//   Checking  Transfer : Visa              -100.00   (Visa +100.00: a card payment)
//   Checking  Corner Store                  -12.50   Groceries "snacks" -> Dining Out -> cleared category + memo
//   Visa      Chipotle                      -30.00   Dining Out (moves 30 into the Visa payment category)
//   Checking  import: Whole Foods           -40.00   memorized -> Groceries, unapproved
//   Checking  import: Shell                 -45.00   uncategorized, unapproved -> Transportation in the register
//   Visa      reconcile at -135.00 (cleared -130.00) -> adjustment -5.00 to Ready to Assign
//
// Assigned: Rent/Mortgage 1200, Utilities 150, Groceries 400, Dining Out 100, Transportation 60,
//           Visa payment 70 (to cover the part of the payment that paid down the old debt) = 1980.
const ASSIGN = [
  ['Rent/Mortgage', 1200_000],
  ['Utilities', 150_000],
  ['Groceries', 400_000],
  ['Dining Out', 100_000],
  ['Transportation', 60_000],
  ['Visa', 70_000],
];
const IMPORT_CSV = [`${TODAY},Whole Foods,-40.00,refill`, `${TODAY},Shell,-45.00,fuel`].join('\n');

// Expected month math (docs/ARCHITECTURE.md "Month math")
const EXPECT = {
  income: 3000_000 + 2000_000 - 5_000,
  assigned: 1980_000,
  categories: {
    // name: [assigned, activity, available]
    'Rent/Mortgage': [1200_000, 0, 1200_000],
    Utilities: [150_000, 0, 150_000],
    Groceries: [400_000, -85_200 - 70_000 - 40_000, 400_000 - 195_200],
    'Dining Out': [100_000, -30_000, 70_000],
    Shopping: [0, -50_000, -50_000],
    Transportation: [60_000, -45_000, 15_000],
    // card spending +30, payment -100
    Visa: [70_000, 30_000 - 100_000, 0],
  },
  uncategorized: -12_500,
  overspent: 50_000 + 12_500, // Shopping + uncategorized; hits next month's Ready to Assign
  balances: { Checking: 3000_000 + 2000_000 - 85_200 - 120_000 - 100_000 - 12_500 - 40_000 - 45_000, Visa: -200_000 + 100_000 - 30_000 - 5_000 },
};
EXPECT.readyToAssign = EXPECT.income - EXPECT.assigned;
EXPECT.activity = Object.values(EXPECT.categories).reduce((a, c) => a + c[1], 0);
EXPECT.available = Object.values(EXPECT.categories).reduce((a, c) => a + c[2], 0);

// ---------------------------------------------------------------------------------- UI steps

async function login(page) {
  await page.goto(APP + '/');
  await page.getByRole('link', { name: /sign in with kanidm/i }).waitFor();
  await snap(page, 'login');
  await page.getByRole('link', { name: /sign in with kanidm/i }).click();
  await page.waitForSelector('input[name=username]', { timeout: 20000 });
  await page.fill('input[name=username]', USER);
  await page.click('button[type=submit]');
  await page.waitForSelector('input[name=password]', { timeout: 15000 });
  await page.fill('input[name=password]', PASSWORD);
  await page.click('button[type=submit]');
  try {
    const proceed = page.getByRole('button', { name: /proceed|allow|consent/i });
    await proceed.waitFor({ timeout: 4000 });
    await proceed.click();
  } catch {
    // consent already given
  }
  await page.waitForURL((u) => u.toString().startsWith(APP), { timeout: 20000 });
  await page.getByRole('heading', { name: 'Your budgets' }).waitFor();
  must(!page.url().includes('login_error'), 'logged in through Kanidm, landed on ' + page.url());
  await snap(page, 'budgets');
}

async function createBudget(page) {
  await page.getByRole('button', { name: 'New budget' }).click();
  await page.getByRole('textbox', { name: 'Name' }).fill(BUDGET);
  await page.locator('.modal select').selectOption('USD');
  const b = await action(page, 'create_budget', () => page.getByRole('button', { name: 'Create budget' }).click());
  await page.waitForURL(new RegExp(`/budgets/${b.id}/budget/${MONTH}$`));
  await page.locator('[data-testid=rta]').waitFor();
  check((await page.locator('.rta-amount').innerText()) === fmt(0), 'new budget: Ready to Assign is $0.00');
  for (const g of ['Bills', 'Needs', 'Wants', 'Savings']) {
    check(await page.locator('tr.group-row', { hasText: g }).count() > 0, `starter group "${g}" shown`);
  }
  await snap(page, 'budget-created');
  return b.id;
}

async function addAccount(page, name, typeLabel, balance) {
  await page.getByRole('button', { name: 'Add account' }).click();
  await page.getByLabel('Account name').fill(name);
  await page.locator('.modal select').selectOption({ label: typeLabel });
  await page.locator('.modal input.money-input').fill(balance);
  await snap(page, `add-account-${name.toLowerCase()}`);
  const a = await action(page, 'create_account', () => page.locator('.modal').getByRole('button', { name: 'Add account' }).click());
  await page.getByRole('heading', { name, exact: true }).waitFor();
  await settle(page);
  return a;
}

function catRow(page, name) {
  return page.locator('tr.cat-row').filter({ has: page.getByRole('button', { name, exact: true }) });
}

async function assignMoney(page, budgetId) {
  await page.goto(`${APP}/budgets/${budgetId}/budget/${MONTH}`);
  await page.locator('[data-testid=rta]').waitFor();
  await settle(page);
  check((await page.locator('.rta-amount').innerText()) === fmt(3000_000), 'after starting balances: Ready to Assign = $3,000.00');
  for (const [name, amount] of ASSIGN) {
    const input = page.getByLabel(`Assigned to ${name}`, { exact: true });
    await input.click();
    await input.fill(String(amount / 1000));
    await action(page, 'assign_to_category', () => input.press('Tab'));
    await settle(page);
  }
  await page.waitForFunction((v) => document.querySelector('.rta-amount')?.textContent === v, fmt(3000_000 - 1980_000));
  check(true, 'after assigning: Ready to Assign = ' + fmt(3000_000 - 1980_000));
  await snap(page, 'assigned');
}

async function gotoAccount(page, budgetId, accountId, name) {
  await page.goto(`${APP}/budgets/${budgetId}/accounts/${accountId}`);
  await page.getByRole('heading', { name, exact: true }).waitFor();
  await settle(page);
}

/** Fills the transaction editor row; `payee` may be a new name or an existing (transfer) payee. */
async function fillTxn(page, { payee, choose, category, memo, outflow, inflow }) {
  const editor = page.locator('tr.editing').first();
  if (payee !== undefined) {
    const input = editor.getByRole('combobox', { name: 'Payee' });
    await input.fill(payee);
    if (choose) await page.locator('.combo-list li', { hasText: choose }).first().click();
    else await input.press('Escape');
  }
  if (memo !== undefined) await editor.getByLabel('Memo').fill(memo);
  if (outflow !== undefined) await editor.getByLabel('Outflow').fill(outflow);
  if (inflow !== undefined) await editor.getByLabel('Inflow').fill(inflow);
  if (category !== undefined) {
    await editor.getByLabel('Category').selectOption(category === null ? '' : { label: category });
  }
}

async function saveTxn(page, actionName = 'create_transaction') {
  return action(page, actionName, () => page.locator('.edit-actions').getByRole('button', { name: /^Save/ }).click());
}

function txnRow(page, payee) {
  return page.locator('tr.txn').filter({ has: page.locator('td', { hasText: new RegExp(`^${payee}`) }) });
}

async function addTransactions(page, budgetId, checking, visa) {
  await gotoAccount(page, budgetId, checking.id, 'Checking');
  const add = () => page.getByRole('button', { name: 'Add transaction' }).first().click();

  // regular inflow to Ready to Assign
  await add();
  await fillTxn(page, { payee: 'Paycheck', inflow: '2000', category: 'Ready to Assign' });
  await saveTxn(page);
  // regular outflow
  await add();
  await fillTxn(page, { payee: 'Whole Foods', memo: 'weekly shop', outflow: '85.20', category: 'Groceries' });
  await snap(page, 'register-new-txn');
  await saveTxn(page);
  // split
  await add();
  await fillTxn(page, { payee: 'Target', outflow: '120' });
  await page.locator('.edit-actions').getByRole('button', { name: 'Split' }).click();
  await page.getByLabel('Split 1 category').selectOption({ label: 'Groceries' });
  await page.getByLabel('Split 1 outflow').fill('70');
  await page.getByLabel('Split 2 category').selectOption({ label: 'Shopping' });
  await page.getByLabel('Split 2 outflow').fill('50');
  check(await page.getByText('Splits add up').isVisible(), 'split editor: "Splits add up"');
  await snap(page, 'register-split-editor');
  await saveTxn(page);
  // transfer to the credit card
  await add();
  await fillTxn(page, { payee: 'Visa', choose: 'Transfer : Visa', outflow: '100' });
  check(await page.getByText('Transfer — no category needed').isVisible(), 'transfer to on-budget card needs no category');
  await saveTxn(page);
  // a transaction to edit later
  await add();
  await fillTxn(page, { payee: 'Corner Store', memo: 'snacks', outflow: '12.50', category: 'Groceries' });
  await saveTxn(page);
  await settle(page);

  check(await txnRow(page, 'Target').locator('td', { hasText: 'Split (2)' }).count() === 1, 'split txn shows "Split (2)"');
  check(await page.locator('tr.split-line', { hasText: 'Shopping' }).count() === 1, 'split line "Shopping" listed');
  check(await txnRow(page, 'Transfer : Visa').count() === 1, 'transfer row in Checking');
  await snap(page, 'register-checking');

  // categorized card spending
  await gotoAccount(page, budgetId, visa.id, 'Visa');
  check(await txnRow(page, 'Transfer : Checking').count() === 1, 'other side of the transfer in Visa');
  check(await txnRow(page, 'Starting Balance').locator('.badge', { hasText: 'Uncategorized' }).count() === 0,
    'card opening debt is not flagged Uncategorized (it has no budget effect)');
  check((await txnRow(page, 'Transfer : Checking').locator('td.num').nth(1).innerText()).includes('100.00'), 'Visa side is a +100.00 inflow');
  await add();
  await fillTxn(page, { payee: 'Chipotle', outflow: '30', category: 'Dining Out' });
  await saveTxn(page);
  await settle(page);
  await snap(page, 'register-visa');
}

async function editTransaction(page, budgetId, checking) {
  await gotoAccount(page, budgetId, checking.id, 'Checking');
  // change category
  await txnRow(page, 'Corner Store').click();
  await fillTxn(page, { category: 'Dining Out' });
  await snap(page, 'register-edit');
  let t = await saveTxn(page, 'update_transaction');
  check(t.categoryName === 'Dining Out' && t.memo === 'snacks', 'edit: category changed to Dining Out, memo kept');
  await settle(page);
  // clear category and memo
  await txnRow(page, 'Corner Store').click();
  await fillTxn(page, { category: null, memo: '' });
  t = await saveTxn(page, 'update_transaction');
  check(t.categoryId === null, 'edit: category cleared (' + t.categoryName + ')');
  check(t.memo === null || t.memo === '', 'edit: memo cleared (' + JSON.stringify(t.memo) + ')');
  check(t.amount === -12_500 && t.payeeName === 'Corner Store', 'edit: amount and payee unchanged');
  await settle(page);
  const row = txnRow(page, 'Corner Store');
  await eventually(async () => (await row.locator('.badge', { hasText: 'Uncategorized' }).count()) === 1, 'register shows Corner Store as Uncategorized');
  await eventually(async () => (await row.locator('td').nth(3).innerText()).trim() === '', 'register shows Corner Store memo empty');
  await snap(page, 'register-edited');
}

async function importStatement(page, budgetId, checking) {
  const status = await (await page.request.get(APP + '/api/agent/status')).json();
  if (!status.enabled) {
    console.log('     import agent disabled (' + status.reason + '); importing through import_transactions instead');
    const r = await api(page, 'import_transactions', {
      budgetId,
      accountId: checking.id,
      transactions: IMPORT_CSV.split('\n').map((l) => {
        const [date, payeeName, amount, memo] = l.split(',');
        return { date, payeeName, amount: Math.round(Number(amount) * 1000), memo };
      }),
    });
    check(r.imported === 2, 'import_transactions imported 2');
    await gotoAccount(page, budgetId, checking.id, 'Checking');
    return false;
  }
  await page.getByRole('link', { name: 'Import assistant' }).click();
  await page.getByRole('heading', { name: 'Import assistant' }).waitFor();
  await page.getByLabel('Import into account').selectOption({ label: 'Account: Checking' });
  await page.getByLabel('Message').fill('date,payee,amount,memo\n' + IMPORT_CSV);
  await snap(page, 'import-pasted');
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith('/api/agent/chat'), { timeout: 60000 }),
    page.getByRole('button', { name: 'Send' }).click(),
  ]);
  const body = await res.json();
  must(res.ok(), 'POST /api/agent/chat -> ' + res.status() + ' ' + JSON.stringify(body).slice(0, 300));
  const names = body.toolCalls.map((c) => c.name + (c.isError ? '(error)' : ''));
  check(JSON.stringify(names) === JSON.stringify(['list_accounts', 'import_transactions']), 'agent tool calls: ' + names.join(', '));
  check(body.toolCalls[1]?.output?.imported === 2, 'agent imported 2 transactions');
  await page.locator('.tool-call').nth(1).waitFor();
  check(await page.locator('.tool-call code', { hasText: 'import_transactions' }).count() === 1, 'tool calls listed in the chat');
  await page.locator('.tool-call').nth(1).locator('summary').click();
  await snap(page, 'import-done');

  // the same statement again: everything is a duplicate, nothing new to review
  await page.getByLabel('Import into account').selectOption({ label: 'Account: Checking' });
  await page.getByLabel('Message').fill('date,payee,amount,memo\n' + IMPORT_CSV);
  const [again] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith('/api/agent/chat'), { timeout: 60000 }),
    page.getByRole('button', { name: 'Send' }).click(),
  ]);
  const second = (await again.json()).toolCalls.find((c) => c.name === 'import_transactions')?.output;
  check(second?.imported === 0 && second?.duplicates === 2, 'agent re-import of the same CSV: 0 imported, 2 duplicates');
  await eventually(async () => (await page.locator('.tool-call').count()) === 4, 'second turn listed');
  check(await page.getByRole('link', { name: /Review .* imported/ }).count() === 1, 'no "Review 0 imported" link for the duplicate-only turn');
  await snap(page, 'import-again');
  await page.getByRole('link', { name: /Review 2 imported transactions in Checking/ }).click();
  await page.getByRole('heading', { name: 'Checking', exact: true }).waitFor();
  await settle(page);
  return true;
}

async function approveAndCategorize(page, budgetId, checking) {
  const shell = txnRow(page, 'Shell');
  const wf = page.locator('tr.txn.unapproved').filter({ has: page.locator('td', { hasText: /^Whole Foods/ }) });
  await eventually(async () => (await page.locator('tr.txn.unapproved').count()) === 2, 'register: 2 unapproved (imported) transactions');
  check(await wf.locator('td', { hasText: 'Groceries' }).count() === 1, 'imported Whole Foods got the memorized category Groceries');
  check(await shell.locator('.badge', { hasText: 'Uncategorized' }).count() === 1, 'imported Shell is uncategorized');
  check(await page.getByRole('button', { name: /Approve all \(2\)/ }).isVisible(), '"Approve all (2)" offered');
  await snap(page, 'register-unapproved');

  // categorize Shell (saving an unapproved txn approves it)
  await shell.click();
  check(await page.locator('.edit-actions').getByRole('button', { name: 'Save & approve' }).isVisible(), 'editor offers "Save & approve"');
  await fillTxn(page, { category: 'Transportation' });
  await saveTxn(page, 'update_transaction');
  await settle(page);
  // approve the rest with the row button
  await action(page, 'approve_transactions', () => wf.getByRole('button', { name: 'Approve' }).click());
  await settle(page);
  await eventually(async () => (await page.locator('tr.txn.unapproved').count()) === 0, 'all transactions approved');
  await eventually(async () => (await page.locator('.acct-link .dot').count()) === 0, 'sidebar has no "to approve" dots');
  await snap(page, 'register-approved');
}

async function clearAll(page) {
  await settle(page);
  const buttons = page.locator('tr.txn').getByRole('button', { name: 'Mark cleared' });
  for (let n = await buttons.count(); n > 0; n--) {
    await action(page, 'set_transactions_cleared', () => buttons.first().click());
    await page.waitForFunction(
      (left) => document.querySelectorAll('tr.txn button[aria-label="Mark cleared"]').length === left, n - 1);
  }
  await settle(page);
}

async function reconcile(page, budgetId, checking, visa) {
  await gotoAccount(page, budgetId, checking.id, 'Checking');
  await clearAll(page);
  const balance = EXPECT.balances.Checking;
  await page.getByRole('button', { name: 'Reconcile' }).click();
  const input = page.locator('.modal input.money-input');
  check((await input.inputValue()) === (balance / 1000).toFixed(2), 'reconcile dialog pre-fills the cleared balance ' + (await input.inputValue()));
  await snap(page, 'reconcile-checking');
  let r = await action(page, 'reconcile_account', () => page.locator('.modal').getByRole('button', { name: 'Reconcile' }).click());
  check(r.reconciledCount === 8 && !r.adjustmentTransactionId, `Checking reconciled ${r.reconciledCount} txns, no adjustment`);
  await settle(page);
  await eventually(async () => (await page.locator('tr.txn .clear-toggle.reconciled').count()) === 8, 'all 8 Checking rows show R');

  await gotoAccount(page, budgetId, visa.id, 'Visa');
  await clearAll(page);
  await page.getByRole('button', { name: 'Reconcile' }).click();
  await page.locator('.modal input.money-input').fill('-135.00');
  check(await page.locator('.modal .alert-warn').isVisible(), 'reconcile warns about the difference');
  await snap(page, 'reconcile-visa');
  r = await action(page, 'reconcile_account', () => page.locator('.modal').getByRole('button', { name: /adjustment/ }).click());
  check(!!r.adjustmentTransactionId, 'Visa reconciled with an adjustment transaction');
  await settle(page);
  await eventually(async () => (await txnRow(page, 'Reconciliation Balance Adjustment').count()) === 1, 'adjustment row shown');
  await snap(page, 'register-visa-reconciled');
}

async function verifyMonth(page, budgetId, accounts) {
  await page.goto(`${APP}/budgets/${budgetId}/budget/${MONTH}`);
  await page.locator('[data-testid=rta]').waitFor();
  await settle(page);
  await snap(page, 'month-final');

  check((await page.locator('.rta-amount').innerText()) === fmt(EXPECT.readyToAssign), `UI Ready to Assign = ${fmt(EXPECT.readyToAssign)}`);
  const facts = await page.locator('.toolbar').innerText();
  check(facts.includes('Income ' + fmt(EXPECT.income)), `UI income ${fmt(EXPECT.income)} (${facts.replace(/\s+/g, ' ')})`);
  check(facts.includes('Uncategorized: ' + fmt(EXPECT.uncategorized)), `UI shows Uncategorized: ${fmt(EXPECT.uncategorized)}`);
  for (const [name, [assigned, activity, available]] of Object.entries(EXPECT.categories)) {
    const row = catRow(page, name);
    const cells = await row.locator('td').allInnerTexts();
    const assignedText = await row.getByRole('textbox').inputValue();
    check(
      assignedText === fmt(assigned).replace('$', '') && cells[2].trim() === fmt(activity) && cells[3].trim() === fmt(available),
      `UI ${name}: assigned ${assignedText}, activity ${cells[2].trim()}, available ${cells[3].trim()} (want ${fmt(assigned)} / ${fmt(activity)} / ${fmt(available)})`,
    );
  }
  check(await catRow(page, 'Shopping').locator('.pill-neg').count() === 1, 'overspent Shopping is shown red');
  const total = await page.locator('tfoot td').allInnerTexts();
  check(total[1] === fmt(EXPECT.assigned) && total[2] === fmt(EXPECT.activity) && total[3] === fmt(EXPECT.available),
    `UI totals ${total.slice(1).join(' / ')} (want ${fmt(EXPECT.assigned)} / ${fmt(EXPECT.activity)} / ${fmt(EXPECT.available)})`);
  const body = await page.locator('main, .main').first().innerText();
  check(!/NaN|undefined|Infinity/.test(body), 'no NaN/undefined on the month page');

  // the same numbers straight from the action
  const mb = await api(page, 'get_month_budget', { budgetId, month: MONTH });
  check(mb.readyToAssign === EXPECT.readyToAssign && mb.income === EXPECT.income && mb.assigned === EXPECT.assigned
    && mb.activity === EXPECT.activity && mb.available === EXPECT.available && mb.uncategorized === EXPECT.uncategorized,
    `get_month_budget rta=${mb.readyToAssign} income=${mb.income} assigned=${mb.assigned} activity=${mb.activity} available=${mb.available} uncategorized=${mb.uncategorized}`);

  // balances in the sidebar
  for (const [name, bal] of Object.entries(EXPECT.balances)) {
    const text = await page.locator('.acct-link', { hasText: name }).locator('.money').innerText();
    check(text === fmt(bal), `sidebar ${name} balance ${text} (want ${fmt(bal)})`);
  }
  const acc = await api(page, 'list_accounts', { budgetId });
  for (const a of acc) check(a.balance === EXPECT.balances[a.name] && a.clearedBalance === a.balance && a.unclearedBalance === 0, `list_accounts ${a.name}: balance ${a.balance}, cleared ${a.clearedBalance}`);

  // next month: overspending hits Ready to Assign
  await page.getByRole('link', { name: 'Next month' }).click();
  await page.waitForURL(new RegExp(`/budget/${NEXT_MONTH}$`));
  await settle(page);
  check((await page.locator('.rta-amount').innerText()) === fmt(EXPECT.readyToAssign - EXPECT.overspent),
    `next month Ready to Assign = ${fmt(EXPECT.readyToAssign - EXPECT.overspent)} (got ${await page.locator('.rta-amount').innerText()})`);
  check((await page.locator('.toolbar').innerText()).includes(fmt(EXPECT.overspent)), 'next month shows "Overspent in ..." ' + fmt(EXPECT.overspent));
  check((await catRow(page, 'Groceries').locator('td').nth(3).innerText()).trim() === fmt(EXPECT.categories.Groceries[2]), 'Groceries carries over');
  check((await catRow(page, 'Shopping').locator('td').nth(3).innerText()).trim() === fmt(0), 'Shopping overspending does not carry over');
  await snap(page, 'month-next');
}

async function verifyReports(page, budgetId) {
  await page.getByRole('link', { name: 'Reports' }).click();
  await page.getByRole('heading', { name: 'Reports' }).waitFor();
  await settle(page);
  await page.getByRole('button', { name: 'This month' }).click();
  await settle(page);
  await snap(page, 'reports');
  const spending = page.locator('section.card', { has: page.getByRole('heading', { name: 'Spending by category' }) });
  const text = await spending.innerText();
  for (const c of ['Groceries', 'Dining Out', 'Shopping', 'Transportation']) check(text.includes(c), `spending report lists ${c}`);
  check(!text.includes('No spending'), 'spending report is not empty');
  const report = await api(page, 'report_spending_by_category', { budgetId, fromMonth: MONTH, toMonth: MONTH });
  const byName = Object.fromEntries(report.rows.map((r) => [r.categoryName, r.total]));
  console.log('     spending rows: ' + JSON.stringify(byName) + ' total ' + report.total);
  check(byName.Groceries === 195_200 && byName['Dining Out'] === 30_000 && byName.Shopping === 50_000 && byName.Transportation === 45_000,
    'spending report values are positive and match the register');
  check(report.rows.every((r) => r.total > 0), 'all spending rows positive');
  const tiles = await page.locator('.stat-tiles').innerText();
  check(!/NaN|undefined/.test(tiles + text), 'no NaN/undefined in reports');
  const ie = await api(page, 'report_income_vs_expense', { budgetId, fromMonth: MONTH, toMonth: MONTH });
  console.log('     income vs expense: ' + JSON.stringify(ie));
  const nw = await api(page, 'report_net_worth', { budgetId, fromMonth: MONTH, toMonth: MONTH });
  console.log('     net worth: ' + JSON.stringify(nw));
  check(nw[0]?.netWorth === EXPECT.balances.Checking + EXPECT.balances.Visa, 'net worth = Checking + Visa');
}

async function createToken(page) {
  await page.getByRole('link', { name: 'Switch budget' }).click();
  await page.getByRole('link', { name: 'Settings' }).click();
  await page.getByRole('heading', { name: 'MCP access tokens' }).waitFor();
  await page.getByRole('button', { name: 'New token' }).click();
  await page.getByRole('textbox', { name: 'Name' }).fill(TOKEN_NAME);
  await action(page, 'create_api_token', () => page.getByRole('button', { name: 'Create token' }).click());
  const token = (await page.locator('[data-testid=new-token]').innerText()).trim();
  check(/^ynay_/.test(token), 'token shown once: ' + token.slice(0, 12) + '…');
  check((await page.locator('[data-testid=mcp-snippet]').innerText()).includes(token), 'Claude Code snippet contains the token');
  await eventually(async () => (await page.locator('table.dtable tr', { hasText: TOKEN_NAME }).count()) === 1, 'token listed');
  fs.writeFileSync(path.join(OUT, 'mcp-token.txt'), token + '\n', { mode: 0o600 });
  await snap(page, 'settings-token');
  return token;
}

async function logout(page) {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.getByRole('link', { name: /sign in with kanidm/i }).waitFor();
  const res = await page.request.get(APP + '/api/me');
  check(res.status() === 401, 'after sign out /api/me -> 401');
  await snap(page, 'logged-out');
}

// ---------------------------------------------------------------------------------- main

const browser = await chromium.launch({ headless: !process.env.E2E_HEADED });
const context = await browser.newContext({ ignoreHTTPSErrors: true, locale: 'en-US', viewport: { width: 1360, height: 900 } });
const page = await context.newPage();
const consoleErrors = [];
const badResponses = [];
let loggedIn = false;
page.on('console', (m) => {
  if (!page.url().startsWith(APP)) return; // Kanidm's own pages are not ours to judge
  if (m.type() !== 'error' && m.type() !== 'warning') return;
  // the anonymous /api/me probe on the login page is a 401 by design
  if (!loggedIn && /status of 401/.test(m.text())) return;
  consoleErrors.push(`[${m.type()}] ${m.text()} @ ${page.url()}`);
});
page.on('pageerror', (e) => consoleErrors.push('[pageerror] ' + e.message));
page.on('response', (r) => {
  const u = new URL(r.url());
  if (u.origin !== APP || !/^\/(api|mcp|logout)/.test(u.pathname) || r.status() < 400) return;
  // before login (and after logout) the SPA probes /api/me and gets 401 by design
  if (u.pathname === '/api/me' && r.status() === 401 && !loggedIn) return;
  badResponses.push(`${r.request().method()} ${u.pathname} -> ${r.status()}`);
});
const isApi = (url) => url.startsWith(APP + '/api/') || url.startsWith(APP + '/mcp');
page.on('request', (r) => { if (isApi(r.url())) inflight++; });
page.on('requestfinished', (r) => { if (isApi(r.url())) inflight--; });
page.on('requestfailed', (r) => {
  if (!isApi(r.url())) return;
  inflight--;
  badResponses.push(`${r.method()} ${r.url()} failed: ${r.failure()?.errorText}`);
});

let budgetId;
try {
  console.log(`YNAY UI smoke against ${APP}, today ${TODAY}, budget "${BUDGET}"`);
  await login(page);
  loggedIn = true;
  budgetId = await createBudget(page);
  const checking = await addAccount(page, 'Checking', 'Checking', '3000');
  const visa = await addAccount(page, 'Visa', 'Credit card', '200');
  check(checking.balance === 3000_000 && visa.balance === -200_000, `starting balances: Checking ${checking.balance}, Visa ${visa.balance}`);
  await assignMoney(page, budgetId);
  await addTransactions(page, budgetId, checking, visa);
  await editTransaction(page, budgetId, checking);
  await importStatement(page, budgetId, checking);
  await approveAndCategorize(page, budgetId, checking);
  await reconcile(page, budgetId, checking, visa);
  await verifyMonth(page, budgetId);
  await verifyReports(page, budgetId);
  await createToken(page);
  loggedIn = false;
  await logout(page);
} catch (e) {
  failures.push(String(e.stack || e));
  console.log('FAIL ' + (e.stack || e));
  await page.screenshot({ path: path.join(OUT, 'failure.png'), fullPage: true }).catch(() => {});
} finally {
  if (budgetId) fs.writeFileSync(path.join(OUT, 'budget-id.txt'), budgetId + '\n');
  await browser.close();
}

check(consoleErrors.length === 0, 'no console errors/warnings' + (consoleErrors.length ? ':\n       ' + consoleErrors.join('\n       ') : ''));
check(badResponses.length === 0, 'no failed /api requests' + (badResponses.length ? ':\n       ' + badResponses.join('\n       ') : ''));
console.log(failures.length ? `\nUI SMOKE FAILED (${failures.length})` : '\nUI SMOKE PASSED');
process.exit(failures.length ? 1 : 0);
