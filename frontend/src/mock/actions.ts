// Mock implementations of the action catalogue in docs/ARCHITECTURE.md, operating on MockState.
// The month math follows the documented rules (simplified where it doesn't matter for a demo).
import type {
  Account,
  AccountType,
  ApiToken,
  Budget,
  Category,
  CategoryGroup,
  CategoryMonth,
  Cleared,
  GroupMonth,
  ImportLine,
  Member,
  MonthBudget,
  Payee,
  SplitInput,
  Target,
  Transaction,
} from '../api/types';
import { addMonths, currentMonth, monthOf, monthRange, todayIso } from '../lib/dates';
import { createReportHandlers, MockError, parseTime } from './reports';
import {
  DEFAULT_CATEGORIES,
  uuid,
  type MAccount,
  type MCategory,
  type MGroup,
  type MockState,
  type MTxn,
} from './state';

export { MockError };

const notFound = (what: string) => new MockError(404, 'not_found', `${what} not found`);
const invalid = (msg: string) => new MockError(400, 'invalid', msg);
const forbidden = (msg: string) => new MockError(403, 'forbidden', msg);

const ON_BUDGET_TYPES: AccountType[] = ['CHECKING', 'SAVINGS', 'CASH', 'CREDIT_CARD', 'LINE_OF_CREDIT'];
const CREDIT_TYPES: AccountType[] = ['CREDIT_CARD', 'LINE_OF_CREDIT'];

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type In = any;

export function createHandlers(s: MockState) {
  const now = () => new Date().toISOString();
  // state saved by an older mock version
  s.subscriptions ??= [];
  s.devices ??= [];
  s.reviews ??= [];
  s.reportSettings ??= {};

  function requireMember(budgetId: string) {
    const m = s.members.find((x) => x.budgetId === budgetId && x.userId === s.meId);
    if (!m || !s.budgets.some((b) => b.id === budgetId)) throw notFound('Budget');
    return m;
  }
  function requireOwner(budgetId: string) {
    if (requireMember(budgetId).role !== 'OWNER') throw forbidden('Only the budget owner can do that');
  }
  function need<T>(v: T | undefined, what: string): T {
    if (v === undefined) throw notFound(what);
    return v;
  }
  function str(v: unknown, field: string): string {
    if (typeof v !== 'string' || !v.trim()) throw invalid(`${field} is required`);
    return v.trim();
  }

  // ---------- DTO mappers ----------
  const budgetDto = (b: (typeof s.budgets)[number]): Budget => ({
    id: b.id,
    name: b.name,
    currencyCode: b.currencyCode,
    role: s.members.find((m) => m.budgetId === b.id && m.userId === s.meId)!.role,
    createdAt: b.createdAt,
  });
  const membersOf = (budgetId: string): Member[] =>
    s.members
      .filter((m) => m.budgetId === budgetId)
      .map((m) => {
        const u = s.users.find((x) => x.id === m.userId)!;
        return { userId: u.id, username: u.username, displayName: u.displayName, role: m.role };
      });
  const accountDto = (a: MAccount): Account => {
    let balance = 0;
    let cleared = 0;
    for (const t of s.txns) {
      if (t.accountId !== a.id) continue;
      balance += t.amount;
      if (t.cleared !== 'UNCLEARED') cleared += t.amount;
    }
    return { ...a, balance, clearedBalance: cleared, unclearedBalance: balance - cleared };
  };
  const categoryDto = (c: MCategory): Category => ({
    id: c.id,
    groupId: c.groupId,
    name: c.name,
    hidden: c.hidden,
    note: c.note,
    sortOrder: c.sortOrder,
    systemKind: c.systemKind,
    linkedAccountId: c.linkedAccountId,
    target: c.target,
  });
  const groupDto = (g: MGroup, includeHidden: boolean): CategoryGroup => ({
    id: g.id,
    name: g.name,
    hidden: g.hidden,
    sortOrder: g.sortOrder,
    systemKind: g.systemKind,
    categories: s.categories
      .filter((c) => c.groupId === g.id && (includeHidden || !c.hidden))
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map(categoryDto),
  });
  const payeeDto = (p: (typeof s.payees)[number]): Payee => ({
    id: p.id,
    name: p.name,
    transferAccountId: p.transferAccountId,
  });
  const txnDto = (t: MTxn): Transaction => {
    const payee = s.payees.find((p) => p.id === t.payeeId);
    const cat = s.categories.find((c) => c.id === t.categoryId);
    return {
      id: t.id,
      budgetId: t.budgetId,
      accountId: t.accountId,
      accountName: s.accounts.find((a) => a.id === t.accountId)?.name ?? '',
      date: t.date,
      time: t.time ?? null,
      amount: t.amount,
      payeeId: t.payeeId,
      payeeName: payee?.name ?? null,
      categoryId: t.categoryId,
      categoryName: cat?.name ?? null,
      memo: t.memo,
      cleared: t.cleared,
      approved: t.approved,
      flagColor: t.flagColor,
      transferAccountId: t.transferAccountId,
      transferTransactionId: t.transferTransactionId,
      importId: t.importId,
      splits: t.splits.map((sp) => ({
        id: sp.id,
        amount: sp.amount,
        categoryId: sp.categoryId,
        categoryName: s.categories.find((c) => c.id === sp.categoryId)?.name ?? null,
        payeeId: sp.payeeId,
        payeeName: s.payees.find((p) => p.id === sp.payeeId)?.name ?? null,
        memo: sp.memo,
      })),
    };
  };

  // ---------- helpers ----------
  const rtaCategory = (budgetId: string) =>
    s.categories.find((c) => c.budgetId === budgetId && c.systemKind === 'READY_TO_ASSIGN')!;
  const nextSort = (items: { sortOrder: number }[]) => items.reduce((m, x) => Math.max(m, x.sortOrder), -1) + 1;

  function payeeByName(budgetId: string, name: string) {
    const existing = s.payees.find(
      (p) => p.budgetId === budgetId && p.name.toLowerCase() === name.trim().toLowerCase(),
    );
    if (existing) return existing;
    const p = { id: uuid(), budgetId, name: name.trim(), transferAccountId: null };
    s.payees.push(p);
    return p;
  }
  function transferPayee(budgetId: string, accountId: string) {
    return s.payees.find((p) => p.budgetId === budgetId && p.transferAccountId === accountId)!;
  }
  function getAccount(budgetId: string, accountId: string) {
    return need(
      s.accounts.find((a) => a.id === accountId && a.budgetId === budgetId),
      'Account',
    );
  }
  function getCategory(budgetId: string, categoryId: string) {
    return need(
      s.categories.find((c) => c.id === categoryId && c.budgetId === budgetId),
      'Category',
    );
  }
  function getTxn(budgetId: string, id: string) {
    return need(
      s.txns.find((t) => t.id === id && t.budgetId === budgetId),
      'Transaction',
    );
  }

  function createGroup(budgetId: string, name: string, systemKind: MGroup['systemKind'] = null, hidden = false) {
    const g: MGroup = {
      id: uuid(),
      budgetId,
      name,
      hidden,
      systemKind,
      sortOrder: nextSort(s.groups.filter((x) => x.budgetId === budgetId)),
    };
    s.groups.push(g);
    return g;
  }
  function createCategory(
    budgetId: string,
    groupId: string,
    name: string,
    extra: Partial<MCategory> = {},
  ): MCategory {
    const c: MCategory = {
      id: uuid(),
      budgetId,
      groupId,
      name,
      hidden: false,
      note: null,
      sortOrder: nextSort(s.categories.filter((x) => x.groupId === groupId)),
      systemKind: null,
      linkedAccountId: null,
      target: null,
      ...extra,
    };
    s.categories.push(c);
    return c;
  }

  /** Resolve payee/transfer fields of a create/update input. */
  function resolvePayee(budgetId: string, accountId: string, in_: In): { payeeId: string | null; transferAccountId: string | null } {
    let transferAccountId: string | null = in_.transferAccountId ?? null;
    let payeeId: string | null = null;
    if (in_.payeeId) {
      const p = need(
        s.payees.find((x) => x.id === in_.payeeId && x.budgetId === budgetId),
        'Payee',
      );
      payeeId = p.id;
      if (p.transferAccountId) transferAccountId = p.transferAccountId;
    } else if (in_.payeeName && !transferAccountId) {
      payeeId = payeeByName(budgetId, in_.payeeName).id;
      const p = s.payees.find((x) => x.id === payeeId)!;
      if (p.transferAccountId) transferAccountId = p.transferAccountId;
    }
    if (transferAccountId) {
      if (transferAccountId === accountId) throw invalid('Cannot transfer to the same account');
      getAccount(budgetId, transferAccountId);
      payeeId = transferPayee(budgetId, transferAccountId).id;
    }
    return { payeeId, transferAccountId };
  }

  function buildSplits(budgetId: string, splits: SplitInput[] | null | undefined, amount: number) {
    if (!splits || splits.length === 0) return [];
    const sum = splits.reduce((a, x) => a + Number(x.amount), 0);
    if (sum !== amount) throw invalid('Split amounts must add up to the transaction amount');
    return splits.map((x) => ({
      id: uuid(),
      amount: Number(x.amount),
      categoryId: x.categoryId ? getCategory(budgetId, x.categoryId).id : null,
      payeeId: x.payeeName ? payeeByName(budgetId, x.payeeName).id : null,
      memo: x.memo ?? null,
    }));
  }

  function memorizedCategory(budgetId: string, payeeId: string | null): string | null {
    if (!payeeId) return null;
    const last = s.txns
      .filter((t) => t.budgetId === budgetId && t.payeeId === payeeId && t.categoryId)
      .sort((a, b) => b.date.localeCompare(a.date) || b.seq - a.seq)[0];
    return last?.categoryId ?? null;
  }

  function transferNeedsCategory(a: MAccount, other: MAccount) {
    return a.onBudget && !other.onBudget;
  }

  function syncMirror(t: MTxn) {
    // Keep the other side of a transfer in step with `t`.
    const existing = t.transferTransactionId ? s.txns.find((x) => x.id === t.transferTransactionId) : undefined;
    if (!t.transferAccountId) {
      if (existing) s.txns = s.txns.filter((x) => x.id !== existing.id);
      t.transferTransactionId = null;
      return;
    }
    const a = s.accounts.find((x) => x.id === t.accountId)!;
    const other = s.accounts.find((x) => x.id === t.transferAccountId)!;
    const mirrorCategory = transferNeedsCategory(other, a) ? t.categoryId : null;
    if (existing && existing.accountId === t.transferAccountId) {
      Object.assign(existing, { date: t.date, amount: -t.amount, memo: t.memo, categoryId: existing.categoryId ?? mirrorCategory });
      return;
    }
    if (existing) s.txns = s.txns.filter((x) => x.id !== existing.id);
    const m: MTxn = {
      id: uuid(),
      budgetId: t.budgetId,
      accountId: t.transferAccountId,
      date: t.date,
      amount: -t.amount,
      payeeId: transferPayee(t.budgetId, t.accountId).id,
      categoryId: null,
      memo: t.memo,
      cleared: 'UNCLEARED',
      approved: true,
      flagColor: null,
      transferAccountId: t.accountId,
      transferTransactionId: t.id,
      importId: null,
      splits: [],
      seq: ++s.seq,
    };
    s.txns.push(m);
    t.transferTransactionId = m.id;
  }

  function createTxn(budgetId: string, in_: In): MTxn {
    const account = getAccount(budgetId, in_.accountId);
    if (!in_.date) throw invalid('date is required');
    const amount = Number(in_.amount);
    if (!Number.isInteger(amount)) throw invalid('amount must be an integer number of milliunits');
    const { payeeId, transferAccountId } = resolvePayee(budgetId, account.id, in_);
    const splits = transferAccountId ? [] : buildSplits(budgetId, in_.splits, amount);
    let categoryId: string | null = splits.length ? null : (in_.categoryId ?? null);
    if (categoryId) getCategory(budgetId, categoryId);
    if (transferAccountId) {
      const other = getAccount(budgetId, transferAccountId);
      if (!transferNeedsCategory(account, other)) categoryId = null;
    } else if (in_.categoryId === undefined && !splits.length) {
      categoryId = memorizedCategory(budgetId, payeeId);
    }
    const t: MTxn = {
      id: uuid(),
      budgetId,
      accountId: account.id,
      date: in_.date,
      time: parseTime(in_.time),
      amount,
      payeeId,
      categoryId,
      memo: in_.memo || null,
      cleared: in_.cleared ?? 'UNCLEARED',
      approved: in_.approved ?? true,
      flagColor: in_.flagColor ?? null,
      transferAccountId,
      transferTransactionId: null,
      importId: in_.importId ?? null,
      splits,
      seq: ++s.seq,
    };
    s.txns.push(t);
    syncMirror(t);
    return t;
  }

  // ---------- month math ----------
  function monthBudget(budgetId: string, month: string): MonthBudget {
    const budget = s.budgets.find((b) => b.id === budgetId)!;
    const accounts = new Map(s.accounts.filter((a) => a.budgetId === budgetId).map((a) => [a.id, a]));
    const cats = s.categories.filter((c) => c.budgetId === budgetId);
    const rta = rtaCategory(budgetId);
    const ccPaymentFor = new Map(
      cats.filter((c) => c.systemKind === 'CREDIT_CARD_PAYMENT' && c.linkedAccountId).map((c) => [c.linkedAccountId!, c.id]),
    );

    // activity[categoryId][month], uncategorized[month]
    const activity = new Map<string, Map<string, number>>();
    const uncategorized = new Map<string, number>();
    const add = (cat: string, m: string, amount: number) => {
      let byMonth = activity.get(cat);
      if (!byMonth) activity.set(cat, (byMonth = new Map()));
      byMonth.set(m, (byMonth.get(m) ?? 0) + amount);
    };
    let firstMonth = month;
    for (const t of s.txns) {
      if (t.budgetId !== budgetId || t.noBudgetEffect) continue;
      const acct = accounts.get(t.accountId);
      if (!acct?.onBudget) continue;
      const m = monthOf(t.date);
      if (m < firstMonth) firstMonth = m;
      const ccP = ccPaymentFor.get(acct.id);
      const lines = t.splits.length ? t.splits.map((x) => ({ amount: x.amount, categoryId: x.categoryId })) : [t];
      for (const line of lines) {
        if (line.categoryId) {
          add(line.categoryId, m, line.amount);
          if (ccP && line.categoryId !== ccP && line.categoryId !== rta.id) add(ccP, m, -line.amount);
        }
      }
      if (!t.splits.length && !t.categoryId) {
        if (t.transferAccountId) {
          const other = accounts.get(t.transferAccountId);
          if (ccP && other?.onBudget && t.amount > 0) add(ccP, m, -t.amount);
        } else {
          uncategorized.set(m, (uncategorized.get(m) ?? 0) + t.amount);
        }
      }
    }
    let totalAssigned = 0;
    for (const [key, v] of Object.entries(s.assignments)) {
      const [catId, m] = key.split('|') as [string, string];
      if (!cats.some((c) => c.id === catId)) continue;
      totalAssigned += v;
      if (m < firstMonth) firstMonth = m;
    }
    const assignedOf = (c: string, m: string) => s.assignments[`${c}|${m}`] ?? 0;
    const act = (c: string, m: string) => activity.get(c)?.get(m) ?? 0;

    const budgetCats = cats.filter((c) => c.systemKind !== 'READY_TO_ASSIGN');
    const carry = new Map<string, number>(); // available at end of previous month
    let incomeToDate = 0;
    let overspentBefore = 0;
    let lastOverspent = 0;
    let result: Map<string, CategoryMonth> = new Map();

    for (const m of monthRange(firstMonth, month)) {
      let overspent = 0;
      result = new Map();
      for (const c of budgetCats) {
        const carryover = Math.max(carry.get(c.id) ?? 0, 0);
        const assigned = assignedOf(c.id, m);
        const activity_ = act(c.id, m);
        const available = carryover + assigned + activity_;
        overspent += Math.max(-available, 0);
        carry.set(c.id, available);
        result.set(c.id, {
          categoryId: c.id,
          name: c.name,
          hidden: c.hidden,
          systemKind: c.systemKind,
          linkedAccountId: c.linkedAccountId,
          carryover,
          assigned,
          activity: activity_,
          available,
          target: c.target,
          targetNeeded: targetNeeded(c.target, m, carryover, assigned),
        });
      }
      overspent += Math.max(-(uncategorized.get(m) ?? 0), 0);
      incomeToDate += act(rta.id, m);
      if (m < month) {
        overspentBefore += overspent;
        lastOverspent = overspent;
      }
    }
    if (firstMonth > addMonths(month, -1)) lastOverspent = 0;

    const groups: GroupMonth[] = s.groups
      .filter((g) => g.budgetId === budgetId && g.systemKind !== 'INTERNAL')
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .map((g) => {
        const categories = budgetCats
          .filter((c) => c.groupId === g.id)
          .sort((a, b) => a.sortOrder - b.sortOrder)
          .map((c) => result.get(c.id)!);
        const sum = (k: 'assigned' | 'activity' | 'available') => categories.reduce((a, c) => a + c[k], 0);
        return {
          groupId: g.id,
          name: g.name,
          hidden: g.hidden,
          systemKind: g.systemKind,
          assigned: sum('assigned'),
          activity: sum('activity'),
          available: sum('available'),
          categories,
        };
      });
    const all = groups.flatMap((g) => g.categories);
    return {
      month,
      currencyCode: budget.currencyCode,
      readyToAssign: incomeToDate - totalAssigned - overspentBefore,
      income: act(rta.id, month),
      assigned: all.reduce((a, c) => a + c.assigned, 0),
      activity: all.reduce((a, c) => a + c.activity, 0),
      available: all.reduce((a, c) => a + c.available, 0),
      overspentLastMonth: lastOverspent,
      uncategorized: uncategorized.get(month) ?? 0,
      groups,
    };
  }

  function targetNeeded(t: Target | null, m: string, carryover: number, assigned: number): number {
    if (!t) return 0;
    switch (t.type) {
      case 'MONTHLY_SAVINGS':
        return Math.max(t.amount - assigned, 0);
      case 'MONTHLY_SPENDING':
      case 'TARGET_BALANCE':
        return Math.max(t.amount - (carryover + assigned), 0);
      case 'TARGET_BALANCE_BY_DATE': {
        if (!t.date) return Math.max(t.amount - (carryover + assigned), 0);
        const end = monthOf(t.date);
        if (m > end) return 0;
        const r = Math.max(monthRange(m, end).length, 1);
        return Math.max(Math.ceil((t.amount - carryover) / r) - assigned, 0);
      }
    }
  }

  function findCategoryMonth(mb: MonthBudget, categoryId: string) {
    for (const g of mb.groups) for (const c of g.categories) if (c.categoryId === categoryId) return c;
    throw notFound('Category');
  }

  function setAssigned(categoryId: string, month: string, value: number) {
    const key = `${categoryId}|${month}`;
    if (value === 0) delete s.assignments[key];
    else s.assignments[key] = value;
  }

  function accountBalanceAt(accountId: string, untilDate: string) {
    return s.txns.filter((t) => t.accountId === accountId && t.date <= untilDate).reduce((a, t) => a + t.amount, 0);
  }

  // ---------- handlers ----------
  const h: Record<string, (in_: In) => unknown> = {
    whoami: () => {
      const u = s.users.find((x) => x.id === s.meId)!;
      return { userId: u.id, username: u.username, displayName: u.displayName, email: u.email };
    },
    list_api_tokens: (): ApiToken[] =>
      s.tokens
        .filter((t) => t.userId === s.meId)
        .map(({ userId: _u, ...t }) => t)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    create_api_token: (in_) => {
      const name = str(in_.name, 'name');
      const secret = Array.from({ length: 40 }, () => 'abcdefghijklmnopqrstuvwxyz0123456789'[Math.floor(Math.random() * 36)]).join('');
      const token = `ynay_${secret}`;
      const t = {
        id: uuid(),
        userId: s.meId,
        name,
        tokenPrefix: token.slice(0, 12),
        createdAt: now(),
        lastUsedAt: null,
        expiresAt: in_.expiresInDays ? new Date(Date.now() + in_.expiresInDays * 86400000).toISOString() : null,
      };
      s.tokens.push(t);
      const { userId: _u, ...dto } = t;
      return { ...dto, token };
    },
    revoke_api_token: (in_) => {
      const before = s.tokens.length;
      s.tokens = s.tokens.filter((t) => !(t.id === in_.tokenId && t.userId === s.meId));
      if (s.tokens.length === before) throw notFound('Token');
      return { revoked: true };
    },

    list_budgets: () =>
      s.budgets
        .filter((b) => s.members.some((m) => m.budgetId === b.id && m.userId === s.meId))
        .map(budgetDto),
    get_budget: (in_) => {
      requireMember(in_.budgetId);
      return budgetDto(s.budgets.find((b) => b.id === in_.budgetId)!);
    },
    create_budget: (in_) => {
      const b = { id: uuid(), name: str(in_.name, 'name'), currencyCode: (in_.currencyCode || 'USD').toUpperCase(), createdAt: now() };
      s.budgets.push(b);
      s.members.push({ budgetId: b.id, userId: s.meId, role: 'OWNER' });
      const internal = createGroup(b.id, 'Internal', 'INTERNAL', true);
      createCategory(b.id, internal.id, 'Inflow: Ready to Assign', { systemKind: 'READY_TO_ASSIGN' });
      createGroup(b.id, 'Credit Card Payments', 'CREDIT_CARD_PAYMENTS');
      if (in_.withDefaultCategories !== false) {
        for (const [group, names] of DEFAULT_CATEGORIES) {
          const g = createGroup(b.id, group);
          for (const n of names) createCategory(b.id, g.id, n);
        }
      }
      return budgetDto(b);
    },
    update_budget: (in_) => {
      requireMember(in_.budgetId);
      const b = s.budgets.find((x) => x.id === in_.budgetId)!;
      if (in_.name != null) b.name = str(in_.name, 'name');
      if (in_.currencyCode != null) {
        if (!/^[A-Za-z]{3}$/.test(in_.currencyCode)) throw invalid('currencyCode must be a 3-letter ISO code');
        b.currencyCode = in_.currencyCode.toUpperCase();
      }
      return budgetDto(b);
    },
    delete_budget: (in_) => {
      requireOwner(in_.budgetId);
      const id = in_.budgetId;
      s.budgets = s.budgets.filter((b) => b.id !== id);
      s.members = s.members.filter((m) => m.budgetId !== id);
      s.accounts = s.accounts.filter((a) => a.budgetId !== id);
      s.txns = s.txns.filter((t) => t.budgetId !== id);
      return { deleted: true };
    },
    list_budget_members: (in_) => {
      requireMember(in_.budgetId);
      return membersOf(in_.budgetId);
    },
    share_budget: (in_) => {
      requireOwner(in_.budgetId);
      const u = s.users.find((x) => x.username.toLowerCase() === String(in_.username ?? '').trim().toLowerCase());
      if (!u) throw notFound(`User "${in_.username}" (they must sign in once first)`);
      const existing = s.members.find((m) => m.budgetId === in_.budgetId && m.userId === u.id);
      if (existing) existing.role = in_.role ?? existing.role;
      else s.members.push({ budgetId: in_.budgetId, userId: u.id, role: in_.role ?? 'EDITOR' });
      return membersOf(in_.budgetId);
    },
    remove_budget_member: (in_) => {
      requireOwner(in_.budgetId);
      if (in_.userId === s.meId) throw invalid('You cannot remove yourself');
      s.members = s.members.filter((m) => !(m.budgetId === in_.budgetId && m.userId === in_.userId));
      return membersOf(in_.budgetId);
    },

    list_accounts: (in_) => {
      requireMember(in_.budgetId);
      return s.accounts
        .filter((a) => a.budgetId === in_.budgetId && (in_.includeClosed || !a.closed))
        .sort((a, b) => a.sortOrder - b.sortOrder)
        .map(accountDto);
    },
    create_account: (in_) => {
      requireMember(in_.budgetId);
      const type = in_.type as AccountType;
      if (!type) throw invalid('type is required');
      const a: MAccount = {
        id: uuid(),
        budgetId: in_.budgetId,
        name: str(in_.name, 'name'),
        type,
        onBudget: in_.onBudget ?? ON_BUDGET_TYPES.includes(type),
        closed: false,
        note: in_.note ?? null,
        sortOrder: nextSort(s.accounts.filter((x) => x.budgetId === in_.budgetId)),
      };
      s.accounts.push(a);
      s.payees.push({ id: uuid(), budgetId: a.budgetId, name: `Transfer : ${a.name}`, transferAccountId: a.id });
      const credit = CREDIT_TYPES.includes(type);
      if (a.onBudget && credit) {
        const g = s.groups.find((x) => x.budgetId === a.budgetId && x.systemKind === 'CREDIT_CARD_PAYMENTS')!;
        createCategory(a.budgetId, g.id, a.name, { systemKind: 'CREDIT_CARD_PAYMENT', linkedAccountId: a.id });
      }
      const start = Number(in_.startingBalance ?? 0);
      const t = createTxn(a.budgetId, {
        accountId: a.id,
        date: in_.startingDate ?? todayIso(),
        amount: start,
        payeeName: 'Starting Balance',
        categoryId: a.onBudget && !credit ? rtaCategory(a.budgetId).id : null,
        cleared: 'CLEARED',
      });
      if (!a.onBudget || credit) t.noBudgetEffect = true;
      return accountDto(a);
    },
    update_account: (in_) => {
      requireMember(in_.budgetId);
      const a = getAccount(in_.budgetId, in_.accountId);
      if (in_.name != null) {
        a.name = str(in_.name, 'name');
        transferPayee(a.budgetId, a.id).name = `Transfer : ${a.name}`;
        const cc = s.categories.find((c) => c.linkedAccountId === a.id);
        if (cc) cc.name = a.name;
      }
      if (in_.note != null) a.note = in_.note || null; // like the backend: null = unchanged, "" clears
      if (in_.closed != null) {
        if (in_.closed && accountDto(a).balance !== 0) throw invalid('Only accounts with a zero balance can be closed');
        a.closed = in_.closed;
      }
      return accountDto(a);
    },
    delete_account: (in_) => {
      requireMember(in_.budgetId);
      const a = getAccount(in_.budgetId, in_.accountId);
      if (s.txns.some((t) => t.transferAccountId === a.id)) throw new MockError(409, 'conflict', 'Account has transfers; close it instead');
      s.txns = s.txns.filter((t) => t.accountId !== a.id);
      s.accounts = s.accounts.filter((x) => x.id !== a.id);
      return { deleted: true };
    },

    list_categories: (in_) => {
      requireMember(in_.budgetId);
      return s.groups
        .filter((g) => g.budgetId === in_.budgetId && (in_.includeHidden || !g.hidden || g.systemKind === 'INTERNAL'))
        .sort((a, b) => a.sortOrder - b.sortOrder)
        .map((g) => groupDto(g, !!in_.includeHidden || g.systemKind === 'INTERNAL'));
    },
    create_category_group: (in_) => {
      requireMember(in_.budgetId);
      return groupDto(createGroup(in_.budgetId, str(in_.name, 'name')), true);
    },
    update_category_group: (in_) => {
      requireMember(in_.budgetId);
      const g = need(
        s.groups.find((x) => x.id === in_.groupId && x.budgetId === in_.budgetId),
        'Group',
      );
      if (g.systemKind) throw invalid('System groups cannot be changed');
      if (in_.name != null) g.name = str(in_.name, 'name');
      if (in_.hidden != null) g.hidden = in_.hidden;
      if (in_.sortOrder != null) g.sortOrder = in_.sortOrder;
      return groupDto(g, true);
    },
    delete_category_group: (in_) => {
      requireMember(in_.budgetId);
      const g = need(
        s.groups.find((x) => x.id === in_.groupId && x.budgetId === in_.budgetId),
        'Group',
      );
      if (g.systemKind) throw invalid('System groups cannot be deleted');
      for (const c of s.categories.filter((x) => x.groupId === g.id)) {
        h.delete_category!({ budgetId: in_.budgetId, categoryId: c.id, reassignToCategoryId: in_.reassignToCategoryId });
      }
      s.groups = s.groups.filter((x) => x.id !== g.id);
      return { deleted: true };
    },
    create_category: (in_) => {
      requireMember(in_.budgetId);
      const g = need(
        s.groups.find((x) => x.id === in_.groupId && x.budgetId === in_.budgetId),
        'Group',
      );
      if (g.systemKind) throw invalid('Categories cannot be added to system groups');
      return categoryDto(createCategory(in_.budgetId, g.id, str(in_.name, 'name'), { note: in_.note ?? null }));
    },
    update_category: (in_) => {
      requireMember(in_.budgetId);
      const c = getCategory(in_.budgetId, in_.categoryId);
      if (in_.name != null) c.name = str(in_.name, 'name');
      if (in_.groupId != null) c.groupId = in_.groupId;
      if (in_.hidden != null) c.hidden = in_.hidden;
      if (in_.note != null) c.note = in_.note || null; // like the backend: null = unchanged, "" clears
      if (in_.sortOrder != null) c.sortOrder = in_.sortOrder;
      return categoryDto(c);
    },
    delete_category: (in_) => {
      requireMember(in_.budgetId);
      const c = getCategory(in_.budgetId, in_.categoryId);
      if (c.systemKind) throw invalid('System categories cannot be deleted');
      const to: string | null = in_.reassignToCategoryId ?? null;
      if (to) getCategory(in_.budgetId, to);
      for (const t of s.txns) {
        if (t.categoryId === c.id) t.categoryId = to;
        for (const sp of t.splits) if (sp.categoryId === c.id) sp.categoryId = to;
      }
      for (const key of Object.keys(s.assignments)) {
        const [catId, m] = key.split('|') as [string, string];
        if (catId !== c.id) continue;
        if (to) setAssigned(to, m, (s.assignments[`${to}|${m}`] ?? 0) + s.assignments[key]!);
        delete s.assignments[key];
      }
      s.categories = s.categories.filter((x) => x.id !== c.id);
      return { deleted: true };
    },

    list_payees: (in_) => {
      requireMember(in_.budgetId);
      return s.payees
        .filter((p) => p.budgetId === in_.budgetId)
        .sort((a, b) => a.name.localeCompare(b.name))
        .map(payeeDto);
    },
    update_payee: (in_) => {
      requireMember(in_.budgetId);
      const p = need(
        s.payees.find((x) => x.id === in_.payeeId && x.budgetId === in_.budgetId),
        'Payee',
      );
      p.name = str(in_.name, 'name');
      return payeeDto(p);
    },
    merge_payees: (in_) => {
      requireMember(in_.budgetId);
      const target = need(
        s.payees.find((x) => x.id === in_.targetPayeeId),
        'Payee',
      );
      const sources = new Set<string>(in_.sourcePayeeIds);
      for (const t of s.txns) if (t.payeeId && sources.has(t.payeeId)) t.payeeId = target.id;
      s.payees = s.payees.filter((p) => !sources.has(p.id) || p.id === target.id);
      return payeeDto(target);
    },

    list_transactions: (in_) => {
      requireMember(in_.budgetId);
      const q = (in_.search ?? '').trim().toLowerCase();
      const rows = s.txns
        .filter((t) => t.budgetId === in_.budgetId)
        .filter((t) => !in_.accountId || t.accountId === in_.accountId)
        .filter((t) => !in_.categoryId || t.categoryId === in_.categoryId || t.splits.some((x) => x.categoryId === in_.categoryId))
        .filter((t) => !in_.payeeId || t.payeeId === in_.payeeId)
        .filter((t) => !in_.sinceDate || t.date >= in_.sinceDate)
        .filter((t) => !in_.untilDate || t.date <= in_.untilDate)
        .filter((t) => !in_.unapprovedOnly || !t.approved)
        .filter((t) => !in_.subscriptionsOnly || [t.payeeId, ...t.splits.map((x) => x.payeeId)].some((id) => s.payees.find((p) => p.id === id)?.subscriptionId))
        .map(txnDto)
        .filter(
          (t) =>
            !q ||
            [t.payeeName, t.memo, t.categoryName, t.accountName, ...t.splits.map((x) => x.categoryName)]
              .some((v) => v?.toLowerCase().includes(q)),
        )
        .sort((a, b) => b.date.localeCompare(a.date) || seqOf(b.id) - seqOf(a.id));
      const offset = in_.offset ?? 0;
      const limit = in_.limit ?? 200;
      return { transactions: rows.slice(offset, offset + limit), total: rows.length };
    },
    get_transaction: (in_) => {
      requireMember(in_.budgetId);
      return txnDto(getTxn(in_.budgetId, in_.transactionId));
    },
    create_transaction: (in_) => {
      requireMember(in_.budgetId);
      return txnDto(createTxn(in_.budgetId, in_));
    },
    update_transaction: (in_) => {
      requireMember(in_.budgetId);
      const t = getTxn(in_.budgetId, in_.transactionId);
      if (in_.accountId != null) t.accountId = getAccount(in_.budgetId, in_.accountId).id;
      if (in_.date != null) t.date = in_.date;
      if (in_.time != null) t.time = parseTime(in_.time);
      if (in_.amount != null) t.amount = Number(in_.amount);
      if (in_.payeeId !== undefined || in_.payeeName !== undefined || in_.transferAccountId !== undefined) {
        const r = resolvePayee(in_.budgetId, t.accountId, in_);
        t.payeeId = r.payeeId;
        t.transferAccountId = r.transferAccountId;
      }
      if (in_.memo != null) t.memo = in_.memo || null;
      if (in_.cleared != null) t.cleared = in_.cleared;
      if (in_.approved != null) t.approved = in_.approved;
      if (in_.flagColor !== undefined) t.flagColor = in_.flagColor;
      if (in_.splits !== undefined) t.splits = buildSplits(in_.budgetId, in_.splits, t.amount);
      // Mirrors the backend: null means "unchanged"; clearCategory makes it uncategorized.
      if (in_.categoryId != null) t.categoryId = in_.categoryId;
      if (in_.clearCategory) t.categoryId = null;
      if (t.splits.length) t.categoryId = null;
      if (t.transferAccountId) {
        const other = getAccount(in_.budgetId, t.transferAccountId);
        if (!transferNeedsCategory(s.accounts.find((a) => a.id === t.accountId)!, other)) t.categoryId = null;
      }
      syncMirror(t);
      return txnDto(t);
    },
    delete_transaction: (in_) => {
      requireMember(in_.budgetId);
      const t = getTxn(in_.budgetId, in_.transactionId);
      s.txns = s.txns.filter((x) => x.id !== t.id && x.id !== t.transferTransactionId);
      return { deleted: true };
    },
    import_transactions: (in_) => {
      requireMember(in_.budgetId);
      const account = getAccount(in_.budgetId, in_.accountId);
      const lines: ImportLine[] = in_.transactions ?? [];
      const seen = new Map<string, number>();
      const ids: string[] = [];
      const dupes: string[] = [];
      for (const line of lines) {
        let importId = line.importId;
        if (!importId) {
          const base = `YNAY:${line.amount}:${line.date}`;
          const n = (seen.get(base) ?? 0) + 1;
          seen.set(base, n);
          importId = `${base}:${n}`;
        }
        if (s.txns.some((t) => t.accountId === account.id && t.importId === importId)) {
          dupes.push(importId);
          continue;
        }
        const t = createTxn(in_.budgetId, {
          accountId: account.id,
          date: line.date,
          time: line.time,
          amount: line.amount,
          payeeName: line.payeeName,
          memo: line.memo,
          categoryId: line.categoryId,
          cleared: line.cleared ?? 'CLEARED',
          approved: in_.approved ?? false,
          importId,
        });
        ids.push(t.id);
      }
      return { imported: ids.length, duplicates: dupes.length, transactionIds: ids, duplicateImportIds: dupes };
    },
    approve_transactions: (in_) => {
      requireMember(in_.budgetId);
      let n = 0;
      for (const t of s.txns) {
        if (t.budgetId === in_.budgetId && in_.transactionIds.includes(t.id) && !t.approved) {
          t.approved = true;
          n++;
        }
      }
      return { approved: n };
    },
    set_transactions_cleared: (in_) => {
      requireMember(in_.budgetId);
      let n = 0;
      for (const t of s.txns) {
        if (t.budgetId === in_.budgetId && in_.transactionIds.includes(t.id)) {
          t.cleared = in_.cleared as Cleared;
          n++;
        }
      }
      return { updated: n };
    },
    reconcile_account: (in_) => {
      requireMember(in_.budgetId);
      const a = getAccount(in_.budgetId, in_.accountId);
      const date = in_.statementDate ?? todayIso();
      const cleared = s.txns
        .filter((t) => t.accountId === a.id && t.cleared !== 'UNCLEARED' && t.date <= date)
        .reduce((x, t) => x + t.amount, 0);
      let adjustmentTransactionId: string | null = null;
      const diff = Number(in_.statementBalance) - cleared;
      if (diff !== 0) {
        const t = createTxn(a.budgetId, {
          accountId: a.id,
          date,
          amount: diff,
          payeeName: 'Reconciliation Balance Adjustment',
          categoryId: a.onBudget ? rtaCategory(a.budgetId).id : null,
          cleared: 'CLEARED',
        });
        adjustmentTransactionId = t.id;
      }
      let count = 0;
      for (const t of s.txns) {
        if (t.accountId === a.id && t.cleared === 'CLEARED' && t.date <= date) {
          t.cleared = 'RECONCILED';
          count++;
        }
      }
      return { reconciledCount: count, adjustmentTransactionId };
    },
    ...createReportHandlers(s, requireMember),

    get_month_budget: (in_) => {
      requireMember(in_.budgetId);
      return monthBudget(in_.budgetId, in_.month);
    },
    list_month_summaries: (in_) => {
      requireMember(in_.budgetId);
      return monthRange(in_.fromMonth, in_.toMonth).map((m) => {
        const mb = monthBudget(in_.budgetId, m);
        return { month: m, income: mb.income, assigned: mb.assigned, activity: mb.activity, readyToAssign: mb.readyToAssign };
      });
    },
    assign_to_category: (in_) => {
      requireMember(in_.budgetId);
      const c = getCategory(in_.budgetId, in_.categoryId);
      if (c.systemKind === 'READY_TO_ASSIGN') throw invalid('Cannot assign to Ready to Assign');
      setAssigned(c.id, in_.month, Number(in_.assigned));
      return findCategoryMonth(monthBudget(in_.budgetId, in_.month), c.id);
    },
    move_money: (in_) => {
      requireMember(in_.budgetId);
      const amount = Number(in_.amount);
      if (!(amount > 0)) throw invalid('amount must be positive');
      const from: string | null = in_.fromCategoryId ?? null;
      const to: string | null = in_.toCategoryId ?? null;
      if (from === to) throw invalid('Choose two different categories');
      if (from) setAssigned(getCategory(in_.budgetId, from).id, in_.month, (s.assignments[`${from}|${in_.month}`] ?? 0) - amount);
      if (to) setAssigned(getCategory(in_.budgetId, to).id, in_.month, (s.assignments[`${to}|${in_.month}`] ?? 0) + amount);
      return monthBudget(in_.budgetId, in_.month);
    },
    assign_underfunded: (in_) => {
      requireMember(in_.budgetId);
      const mb = monthBudget(in_.budgetId, in_.month);
      for (const g of mb.groups) {
        for (const c of g.categories) if (c.targetNeeded > 0) setAssigned(c.categoryId, in_.month, c.assigned + c.targetNeeded);
      }
      return monthBudget(in_.budgetId, in_.month);
    },
    set_category_target: (in_) => {
      requireMember(in_.budgetId);
      const c = getCategory(in_.budgetId, in_.categoryId);
      if (!in_.type) throw invalid('type is required');
      if (!(Number(in_.amount) > 0)) throw invalid('Target amount must be positive');
      if (in_.type === 'TARGET_BALANCE_BY_DATE' && !in_.targetDate) throw invalid('targetDate is required for this target');
      c.target = { type: in_.type, amount: Number(in_.amount), date: in_.targetDate ?? null };
      return categoryDto(c);
    },
    clear_category_target: (in_) => {
      requireMember(in_.budgetId);
      const c = getCategory(in_.budgetId, in_.categoryId);
      c.target = null;
      return categoryDto(c);
    },
    report_spending_by_category: (in_) => {
      requireMember(in_.budgetId);
      const totals = new Map<string, number>();
      for (const m of monthRange(in_.fromMonth, in_.toMonth)) {
        const mb = monthBudget(in_.budgetId, m);
        for (const g of mb.groups) {
          if (g.systemKind === 'CREDIT_CARD_PAYMENTS') continue;
          // Same convention as the backend: spending is reported as a positive number.
          for (const c of g.categories) if (c.activity) totals.set(c.categoryId, (totals.get(c.categoryId) ?? 0) - c.activity);
        }
      }
      const rows = [...totals]
        .map(([categoryId, total]) => {
          const c = s.categories.find((x) => x.id === categoryId)!;
          return { categoryId, categoryName: c.name, groupName: s.groups.find((g) => g.id === c.groupId)!.name, total };
        })
        .filter((r) => r.total !== 0)
        .sort((a, b) => b.total - a.total);
      return { rows, total: rows.reduce((a, r) => a + r.total, 0) };
    },
    report_income_vs_expense: (in_) => {
      requireMember(in_.budgetId);
      return monthRange(in_.fromMonth, in_.toMonth).map((m) => {
        const mb = monthBudget(in_.budgetId, m);
        const expense = mb.groups
          .filter((g) => g.systemKind !== 'CREDIT_CARD_PAYMENTS')
          .reduce((a, g) => a + g.categories.reduce((x, c) => x - c.activity, 0), 0);
        return { month: m, income: mb.income, expense, net: mb.income - expense };
      });
    },
    report_net_worth: (in_) => {
      requireMember(in_.budgetId);
      const accts = s.accounts.filter((a) => a.budgetId === in_.budgetId);
      return monthRange(in_.fromMonth, in_.toMonth).map((m) => {
        const end = m === currentMonth() ? todayIso() : `${m}-31`;
        let assets = 0;
        let liabilities = 0;
        for (const a of accts) {
          const bal = accountBalanceAt(a.id, end);
          if (bal >= 0) assets += bal;
          else liabilities += bal;
        }
        return { month: m, assets, liabilities, netWorth: assets + liabilities };
      });
    },
  };

  function seqOf(id: string) {
    return s.txns.find((t) => t.id === id)?.seq ?? 0;
  }

  return h;
}

export type Handlers = ReturnType<typeof createHandlers>;
