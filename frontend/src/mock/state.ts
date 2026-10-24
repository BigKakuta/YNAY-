// In-memory data model of the mock backend (see ./index.ts). Shapes follow V1__init.sql loosely.
import type { AccountType, Cleared, Role, Target } from '../api/types';
import { addMonths, currentMonth, todayIso } from '../lib/dates';

export interface MUser { id: string; username: string; displayName: string | null; email: string | null }
export interface MBudget { id: string; name: string; currencyCode: string; createdAt: string }
export interface MMember { budgetId: string; userId: string; role: Role }
export interface MAccount {
  id: string; budgetId: string; name: string; type: AccountType; onBudget: boolean;
  closed: boolean; note: string | null; sortOrder: number;
}
export interface MGroup {
  id: string; budgetId: string; name: string; hidden: boolean; sortOrder: number;
  systemKind: 'INTERNAL' | 'CREDIT_CARD_PAYMENTS' | null;
}
export interface MCategory {
  id: string; budgetId: string; groupId: string; name: string; hidden: boolean; note: string | null;
  sortOrder: number; systemKind: 'READY_TO_ASSIGN' | 'CREDIT_CARD_PAYMENT' | null;
  linkedAccountId: string | null; target: Target | null;
}
export interface MPayee { id: string; budgetId: string; name: string; transferAccountId: string | null }
export interface MSplit { id: string; amount: number; categoryId: string | null; payeeId: string | null; memo: string | null }
export interface MTxn {
  id: string; budgetId: string; accountId: string; date: string; amount: number;
  payeeId: string | null; categoryId: string | null; memo: string | null; cleared: Cleared;
  approved: boolean; flagColor: string | null; transferAccountId: string | null;
  transferTransactionId: string | null; importId: string | null; splits: MSplit[]; seq: number;
  /** Starting balance of credit/tracking accounts: no budget effect. */
  noBudgetEffect?: boolean;
}
export interface MToken {
  id: string; userId: string; name: string; tokenPrefix: string; createdAt: string;
  lastUsedAt: string | null; expiresAt: string | null;
}

export interface MockState {
  meId: string;
  users: MUser[];
  budgets: MBudget[];
  members: MMember[];
  accounts: MAccount[];
  groups: MGroup[];
  categories: MCategory[];
  payees: MPayee[];
  txns: MTxn[];
  /** `${categoryId}|${month}` → assigned milliunits */
  assignments: Record<string, number>;
  tokens: MToken[];
  seq: number;
}

export function uuid(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c?.randomUUID) return c.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0;
    return (ch === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function emptyState(): MockState {
  const me: MUser = { id: uuid(), username: 'alex', displayName: 'Alex Example', email: 'alex@example.com' };
  const other: MUser = { id: uuid(), username: 'sam', displayName: 'Sam Example', email: 'sam@example.com' };
  return {
    meId: me.id,
    users: [me, other, { id: uuid(), username: 'jordan', displayName: null, email: null }],
    budgets: [],
    members: [],
    accounts: [],
    groups: [],
    categories: [],
    payees: [],
    txns: [],
    assignments: {},
    tokens: [],
    seq: 0,
  };
}

export const DEFAULT_CATEGORIES: [string, string[]][] = [
  ['Bills', ['Rent/Mortgage', 'Utilities', 'Phone', 'Internet', 'Insurance']],
  ['Needs', ['Groceries', 'Transportation', 'Medical']],
  ['Wants', ['Dining Out', 'Entertainment', 'Shopping']],
  ['Savings', ['Emergency Fund', 'Vacation']],
];

/** Day `d` of the month `offset` months from now, clamped to today for the current month. */
export function dayIn(offset: number, d: number): string {
  const m = addMonths(currentMonth(), offset);
  const date = `${m}-${String(d).padStart(2, '0')}`;
  const today = todayIso();
  return offset === 0 && date > today ? today : date;
}
