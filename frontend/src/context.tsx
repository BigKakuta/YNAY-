import { createContext, useContext } from 'react';
import type { Account, Budget, CategoryGroup, Me } from './api/types';

export const MeContext = createContext<Me | null>(null);
export const useMe = () => useContext(MeContext)!;

export type BudgetCtx = {
  budgetId: string;
  budget: Budget;
  currency: string;
  /** Open and closed accounts. */
  accounts: Account[];
  /** All groups incl. hidden ones and the INTERNAL group (for selects). */
  categories: CategoryGroup[];
};

export const BudgetContext = createContext<BudgetCtx | null>(null);

export function useBudget(): BudgetCtx {
  const ctx = useContext(BudgetContext);
  if (!ctx) throw new Error('useBudget outside of a budget');
  return ctx;
}

export function rtaCategoryId(groups: CategoryGroup[]): string | null {
  for (const g of groups) for (const c of g.categories) if (c.systemKind === 'READY_TO_ASSIGN') return c.id;
  return null;
}

export const ACCOUNT_TYPES: { value: Account['type']; label: string; onBudget: boolean }[] = [
  { value: 'CHECKING', label: 'Checking', onBudget: true },
  { value: 'SAVINGS', label: 'Savings', onBudget: true },
  { value: 'CASH', label: 'Cash', onBudget: true },
  { value: 'CREDIT_CARD', label: 'Credit card', onBudget: true },
  { value: 'LINE_OF_CREDIT', label: 'Line of credit', onBudget: true },
  { value: 'ASSET', label: 'Asset (e.g. investment)', onBudget: false },
  { value: 'LIABILITY', label: 'Liability', onBudget: false },
  { value: 'MORTGAGE', label: 'Mortgage', onBudget: false },
  { value: 'LOAN', label: 'Loan', onBudget: false },
  { value: 'OTHER', label: 'Other tracking', onBudget: false },
];

export const accountTypeLabel = (t: Account['type']) => ACCOUNT_TYPES.find((x) => x.value === t)?.label ?? t;
