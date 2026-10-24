// Mock implementations of the report, subscription and device actions (see docs/ARCHITECTURE.md).
// The flow classification mirrors the backend's FlowLines.
import type {
  CashFlowMonth,
  CashFlowRow,
  Device,
  FlowKind,
  Payment,
  ReportSettings,
  Subscription,
} from '../api/types';
import { addMonths, monthRange, todayIso } from '../lib/dates';
import { uuid, type MDevice, type MockState, type MSubscription } from './state';

export class MockError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

const invalid = (msg: string) => new MockError(400, 'invalid', msg);
const notFound = (what: string) => new MockError(404, 'not_found', `${what} not found`);

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type In = any;

/** "9:05" → "09:05"; blank → null. */
export function parseTime(v: unknown): string | null {
  if (v == null) return null;
  const t = String(v).trim();
  if (!t) return null;
  const m = /^(\d{1,2}):(\d{2})(?::\d{2})?$/.exec(t);
  if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) throw invalid(`time must be a time of day like 14:05, got '${t}'`);
  return `${m[1]!.padStart(2, '0')}:${m[2]}`;
}

const EMPTY_SETTINGS: ReportSettings = {
  thingsCategoryIds: null,
  thingsMonthlyTarget: null,
  focusCategoryIds: null,
  focusPayeeId: null,
  reviewMinAmount: null,
  grossSalaryPerYear: null,
  salaryPayeeId: null,
};

type Line = Omit<Payment, 'review' | 'amount'> & {
  amount: number; // signed as stored: negative = outflow
  groupId: string | null;
  kind: FlowKind | null;
  transferAccountId: string | null;
  transferAccountName: string | null;
};

export function createReportHandlers(s: MockState, requireMember: (budgetId: string) => unknown) {
  function flowLines(budgetId: string, start = '0000-01-01', end = '9999-12-31'): Line[] {
    const out: Line[] = [];
    for (const t of s.txns) {
      if (t.budgetId !== budgetId || t.date < start || t.date >= end) continue;
      const account = s.accounts.find((a) => a.id === t.accountId)!;
      if (!account.onBudget) continue;
      const parts = t.splits.length
        ? t.splits.map((x) => ({ splitId: x.id, amount: x.amount, categoryId: x.categoryId, payeeId: x.payeeId ?? t.payeeId, memo: x.memo ?? t.memo, transfer: null }))
        : [{ splitId: null, amount: t.amount, categoryId: t.categoryId, payeeId: t.payeeId, memo: t.memo, transfer: t.transferAccountId }];
      for (const x of parts) {
        const ta = x.transfer ? s.accounts.find((a) => a.id === x.transfer) : undefined;
        const payee = s.payees.find((p) => p.id === x.payeeId);
        const cat = s.categories.find((c) => c.id === x.categoryId);
        const group = cat ? s.groups.find((g) => g.id === cat.groupId) : undefined;
        let kind: FlowKind | null = 'SPENDING';
        if (ta?.onBudget) kind = null;
        else if (ta && (ta.type === 'ASSET' || ta.type === 'OTHER')) kind = 'SAVING';
        else if (!ta && payee?.name === 'Starting Balance') kind = null;
        else if (cat?.systemKind === 'READY_TO_ASSIGN') kind = 'INCOME';
        out.push({
          transactionId: t.id,
          splitId: x.splitId,
          date: t.date,
          time: t.time ?? null,
          accountId: account.id,
          accountName: account.name,
          payeeId: payee?.id ?? null,
          payeeName: payee?.name ?? null,
          categoryId: cat?.id ?? null,
          categoryName: cat?.name ?? null,
          groupId: group?.id ?? null,
          groupName: group?.name ?? null,
          memo: x.memo,
          amount: x.amount,
          subscriptionId: payee?.subscriptionId ?? null,
          kind,
          transferAccountId: ta?.id ?? null,
          transferAccountName: ta?.name ?? null,
        });
      }
    }
    return out;
  }

  const monthStart = (m: string) => `${m}-01`;

  function subscriptionDto(sub: MSubscription, asOf: string): Subscription {
    const payees = s.payees
      .filter((p) => p.subscriptionId === sub.id)
      .map((p) => ({ id: p.id, name: p.name }))
      .sort((a, b) => a.name.localeCompare(b.name));
    const ids = new Set(payees.map((p) => p.id));
    const byDate = new Map<string, number>();
    for (const l of flowLines(sub.budgetId)) {
      if (l.transferAccountId || !l.payeeId || !ids.has(l.payeeId)) continue;
      byDate.set(l.date, (byDate.get(l.date) ?? 0) - l.amount);
    }
    const thisMonth = monthStart(asOf.slice(0, 7));
    const yearAgo = monthStart(addMonths(asOf.slice(0, 7), -12));
    const quarterAgo = monthStart(addMonths(asOf.slice(0, 7), -3));
    let count = 0;
    let last12 = 0;
    let last3 = 0;
    let lastAmount = 0;
    let previous: number | null = null;
    let first: string | null = null;
    let last: string | null = null;
    for (const [date, amount] of [...byDate].sort(([a], [b]) => a.localeCompare(b))) {
      if (date > asOf) continue;
      if (date >= yearAgo && date < thisMonth) last12 += amount;
      if (date >= quarterAgo && date < thisMonth) last3 += amount;
      if (amount <= 0) continue;
      count++;
      first ??= date;
      if (last) previous = lastAmount;
      last = date;
      lastAmount = amount;
    }
    const factor = { WEEKLY: 52 / 12, MONTHLY: 1, QUARTERLY: 1 / 3, YEARLY: 1 / 12 }[sub.cadence];
    const monthlyCost = Math.round(sub.variable ? last3 / 3 : lastAmount * factor);
    let status: Subscription['status'] = 'ACTIVE';
    let nextCharge: string | null = null;
    if (sub.cancelledOn && sub.cancelledOn <= asOf) status = 'ENDED';
    else if (last) {
      nextCharge = addPeriod(last, sub.cadence);
      const grace = { WEEKLY: 4, MONTHLY: 10, QUARTERLY: 20, YEARLY: 31 }[sub.cadence];
      if (asOf > addDays(nextCharge, grace)) status = 'OVERDUE';
    }
    return {
      id: sub.id,
      name: sub.name,
      kind: sub.kind,
      cadence: sub.cadence,
      groupName: sub.groupName,
      note: sub.note,
      variable: sub.variable,
      cancelledOn: sub.cancelledOn,
      payees,
      status,
      chargeCount: count,
      firstCharge: first,
      lastCharge: last,
      lastAmount,
      previousAmount: previous,
      monthlyCost,
      last12Months: last12,
      nextCharge,
    };
  }

  function getSub(budgetId: string, id: string) {
    const sub = s.subscriptions.find((x) => x.id === id && x.budgetId === budgetId);
    if (!sub) throw notFound('Subscription');
    return sub;
  }
  function linkPayees(budgetId: string, subId: string, payeeIds: string[]) {
    for (const id of payeeIds) {
      const p = s.payees.find((x) => x.id === id && x.budgetId === budgetId);
      if (!p || p.transferAccountId) throw notFound('A payee in payeeIds');
    }
    for (const p of s.payees) {
      if (p.subscriptionId === subId) p.subscriptionId = null;
      if (payeeIds.includes(p.id)) p.subscriptionId = subId;
    }
  }
  const blank = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : null);

  function deviceDto(d: MDevice): Device {
    const [y, m, day] = d.purchasedOn.split('-');
    return {
      id: d.id,
      name: d.name,
      price: d.price,
      purchasedOn: d.purchasedOn,
      lifespanYears: d.lifespanYears,
      active: d.active,
      note: d.note,
      monthlyCost: Math.round(d.price / (d.lifespanYears * 12)),
      replaceOn: `${Number(y) + d.lifespanYears}-${m}-${day}`,
    };
  }
  function getDevice(budgetId: string, id: string) {
    const d = s.devices.find((x) => x.id === id && x.budgetId === budgetId);
    if (!d) throw notFound('Device');
    return d;
  }
  function checkDevice(d: MDevice) {
    if (!d.name.trim()) throw invalid('name must not be blank');
    if (!(d.price >= 0)) throw invalid('price must be 0 or more (milliunits)');
    if (!(d.lifespanYears >= 1 && d.lifespanYears <= 50)) throw invalid('lifespanYears must be between 1 and 50');
    if (!d.purchasedOn) throw invalid('purchasedOn is required');
  }

  return {
    report_cash_flow: (in_: In) => {
      requireMember(in_.budgetId);
      if (!in_.fromMonth || !in_.toMonth) throw invalid('fromMonth and toMonth are required');
      const plans = new Set(s.subscriptions.filter((x) => x.budgetId === in_.budgetId && x.kind === 'SUBSCRIPTION').map((x) => x.id));
      const rows = new Map<string, CashFlowRow>();
      for (const l of flowLines(in_.budgetId, monthStart(in_.fromMonth), monthStart(addMonths(in_.toMonth, 1)))) {
        if (!l.kind) continue;
        const month = l.date.slice(0, 7);
        const saving = l.kind === 'SAVING';
        const key = [month, l.kind, l.categoryId, l.payeeId, saving ? l.transferAccountId : ''].join('|');
        const row = rows.get(key) ?? {
          month,
          kind: l.kind,
          categoryId: l.categoryId,
          categoryName: l.categoryName,
          groupId: l.groupId,
          groupName: l.groupName,
          payeeId: l.payeeId,
          payeeName: l.payeeName,
          subscriptionId: l.subscriptionId,
          accountId: saving ? l.transferAccountId : null,
          accountName: saving ? l.transferAccountName : null,
          amount: 0,
          count: 0,
        };
        row.amount += l.kind === 'INCOME' ? l.amount : -l.amount;
        row.count++;
        rows.set(key, row);
      }
      const list = [...rows.values()].sort((a, b) => a.month.localeCompare(b.month) || a.kind.localeCompare(b.kind) || b.amount - a.amount);
      const months: CashFlowMonth[] = monthRange(in_.fromMonth, in_.toMonth).map((month) => {
        const m: CashFlowMonth = { month, income: 0, spending: 0, saving: 0, subscriptions: 0 };
        for (const r of list) {
          if (r.month !== month) continue;
          if (r.kind === 'INCOME') m.income += r.amount;
          else if (r.kind === 'SAVING') m.saving += r.amount;
          else {
            m.spending += r.amount;
            if (r.subscriptionId && plans.has(r.subscriptionId)) m.subscriptions += r.amount;
          }
        }
        return m;
      });
      return { months, rows: list };
    },
    report_payments: (in_: In) => {
      requireMember(in_.budgetId);
      const limit = in_.limit ?? 2000;
      if (limit < 1 || limit > 10000) throw invalid('limit must be between 1 and 10000');
      const q = (in_.search ?? '').trim().toLowerCase();
      const rows = flowLines(
        in_.budgetId,
        in_.fromMonth ? monthStart(in_.fromMonth) : undefined,
        in_.toMonth ? monthStart(addMonths(in_.toMonth, 1)) : undefined,
      )
        .filter((l) => l.kind === 'SPENDING')
        .filter((l) => !in_.categoryIds || in_.categoryIds.includes(l.categoryId))
        .filter((l) => !in_.payeeIds || in_.payeeIds.includes(l.payeeId))
        .filter((l) => !q || [l.memo, l.payeeName, l.categoryName].some((v) => v?.toLowerCase().includes(q)))
        .filter((l) => in_.minAmount == null || -l.amount >= in_.minAmount)
        .sort((a, b) => b.date.localeCompare(a.date) || (b.time ?? '').localeCompare(a.time ?? '') || a.amount - b.amount)
        .map((l): Payment => {
          const r = s.reviews.find((x) => x.transactionId === l.transactionId && x.splitId === l.splitId);
          return {
            transactionId: l.transactionId,
            splitId: l.splitId,
            date: l.date,
            time: l.time,
            accountId: l.accountId,
            accountName: l.accountName,
            payeeId: l.payeeId,
            payeeName: l.payeeName,
            categoryId: l.categoryId,
            categoryName: l.categoryName,
            groupName: l.groupName,
            memo: l.memo,
            amount: -l.amount,
            subscriptionId: l.subscriptionId,
            review: r ? { rating: r.rating, sensibleReason: r.sensibleReason } : null,
          };
        });
      return { payments: rows.slice(0, limit), truncated: rows.length > limit };
    },
    review_purchase: (in_: In) => {
      requireMember(in_.budgetId);
      const t = s.txns.find((x) => x.id === in_.transactionId && x.budgetId === in_.budgetId);
      if (!t) throw notFound('Transaction');
      const splitId: string | null = in_.splitId ?? null;
      if (splitId && !t.splits.some((x) => x.id === splitId)) throw notFound('Split line');
      s.reviews = s.reviews.filter((x) => !(x.transactionId === t.id && x.splitId === splitId));
      if (in_.clear) return { cleared: true };
      const rating: number | null = in_.rating ?? null;
      if (rating !== null && !(rating >= 1 && rating <= 5)) throw invalid('rating must be between 1 and 5');
      const sensibleReason = blank(in_.sensibleReason);
      if (rating === null && !sensibleReason) throw invalid('Give a rating or a sensibleReason (or clear=true)');
      const review = { transactionId: t.id, splitId, rating, sensibleReason };
      s.reviews.push(review);
      return review;
    },
    get_report_settings: (in_: In) => {
      requireMember(in_.budgetId);
      return { ...EMPTY_SETTINGS, ...s.reportSettings[in_.budgetId] };
    },
    update_report_settings: (in_: In) => {
      requireMember(in_.budgetId);
      const next = { ...EMPTY_SETTINGS, ...(in_.settings ?? {}) } as ReportSettings;
      for (const id of [...(next.thingsCategoryIds ?? []), ...(next.focusCategoryIds ?? [])]) {
        if (!s.categories.some((c) => c.id === id && c.budgetId === in_.budgetId)) throw notFound('Category');
      }
      s.reportSettings[in_.budgetId] = next;
      return next;
    },

    list_subscriptions: (in_: In) => {
      requireMember(in_.budgetId);
      const asOf = in_.asOf ?? todayIso();
      return s.subscriptions
        .filter((x) => x.budgetId === in_.budgetId)
        .map((x) => subscriptionDto(x, asOf))
        .sort((a, b) => Number(a.status === 'ENDED') - Number(b.status === 'ENDED') || b.monthlyCost - a.monthlyCost || a.name.localeCompare(b.name));
    },
    create_subscription: (in_: In) => {
      requireMember(in_.budgetId);
      const name = blank(in_.name);
      if (!name) throw invalid('name must not be blank');
      const sub: MSubscription = {
        id: uuid(),
        budgetId: in_.budgetId,
        name,
        kind: in_.kind ?? 'SUBSCRIPTION',
        cadence: in_.cadence ?? 'MONTHLY',
        groupName: blank(in_.groupName),
        note: blank(in_.note),
        variable: !!in_.variable,
        cancelledOn: in_.cancelledOn ?? null,
      };
      s.subscriptions.push(sub);
      if (in_.payeeIds) linkPayees(in_.budgetId, sub.id, in_.payeeIds);
      return subscriptionDto(sub, todayIso());
    },
    update_subscription: (in_: In) => {
      requireMember(in_.budgetId);
      const sub = getSub(in_.budgetId, in_.subscriptionId);
      if (in_.name != null) {
        const name = blank(in_.name);
        if (!name) throw invalid('name must not be blank');
        sub.name = name;
      }
      if (in_.kind != null) sub.kind = in_.kind;
      if (in_.cadence != null) sub.cadence = in_.cadence;
      if (in_.groupName != null) sub.groupName = blank(in_.groupName);
      if (in_.note != null) sub.note = blank(in_.note);
      if (in_.variable != null) sub.variable = !!in_.variable;
      if (in_.reactivate) sub.cancelledOn = null;
      else if (in_.cancelledOn != null) sub.cancelledOn = in_.cancelledOn;
      if (in_.payeeIds) linkPayees(in_.budgetId, sub.id, in_.payeeIds);
      return subscriptionDto(sub, todayIso());
    },
    delete_subscription: (in_: In) => {
      requireMember(in_.budgetId);
      const sub = getSub(in_.budgetId, in_.subscriptionId);
      s.subscriptions = s.subscriptions.filter((x) => x !== sub);
      for (const p of s.payees) if (p.subscriptionId === sub.id) p.subscriptionId = null;
      return { deleted: true };
    },

    list_devices: (in_: In) => {
      requireMember(in_.budgetId);
      return s.devices
        .filter((d) => d.budgetId === in_.budgetId)
        .sort((a, b) => b.price - a.price || a.name.localeCompare(b.name))
        .map(deviceDto);
    },
    create_device: (in_: In) => {
      requireMember(in_.budgetId);
      const d: MDevice = {
        id: uuid(),
        budgetId: in_.budgetId,
        name: String(in_.name ?? '').trim(),
        price: Number(in_.price),
        purchasedOn: in_.purchasedOn,
        lifespanYears: Number(in_.lifespanYears),
        active: in_.active ?? true,
        note: blank(in_.note),
      };
      checkDevice(d);
      s.devices.push(d);
      return deviceDto(d);
    },
    update_device: (in_: In) => {
      requireMember(in_.budgetId);
      const d = getDevice(in_.budgetId, in_.deviceId);
      const next: MDevice = {
        ...d,
        name: in_.name != null ? String(in_.name).trim() : d.name,
        price: in_.price != null ? Number(in_.price) : d.price,
        purchasedOn: in_.purchasedOn ?? d.purchasedOn,
        lifespanYears: in_.lifespanYears != null ? Number(in_.lifespanYears) : d.lifespanYears,
        active: in_.active ?? d.active,
        note: in_.note != null ? blank(in_.note) : d.note,
      };
      checkDevice(next);
      Object.assign(d, next);
      return deviceDto(d);
    },
    delete_device: (in_: In) => {
      requireMember(in_.budgetId);
      const d = getDevice(in_.budgetId, in_.deviceId);
      s.devices = s.devices.filter((x) => x !== d);
      return { deleted: true };
    },
  };
}

function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  return todayIso(new Date(y, m - 1, d + days));
}

function addPeriod(date: string, cadence: MSubscription['cadence']): string {
  if (cadence === 'WEEKLY') return addDays(date, 7);
  const months = { MONTHLY: 1, QUARTERLY: 3, YEARLY: 12 }[cadence];
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  const target = addMonths(`${y}-${String(m).padStart(2, '0')}`, months);
  const [ty, tm] = target.split('-').map(Number) as [number, number];
  const lastDay = new Date(ty, tm, 0).getDate();
  return `${target}-${String(Math.min(d, lastDay)).padStart(2, '0')}`;
}

