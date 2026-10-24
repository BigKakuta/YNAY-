// Mirrors the DTO shapes and action catalogue in docs/ARCHITECTURE.md. Keep in sync.
// Amounts are integer milliunits (-12340 = -12.34). Dates are YYYY-MM-DD, months YYYY-MM.

export type Uuid = string;
export type IsoDate = string;
export type IsoMonth = string;
export type Milliunits = number;

export type Me = { userId: Uuid; username: string; displayName: string | null; email: string | null };

export type ApiToken = {
  id: Uuid;
  name: string;
  tokenPrefix: string;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
};
export type CreatedApiToken = ApiToken & { token: string };

export type Role = 'OWNER' | 'EDITOR';
export type Budget = { id: Uuid; name: string; currencyCode: string; role: Role; createdAt: string };
export type Member = { userId: Uuid; username: string; displayName: string | null; role: Role };

export type AccountType =
  | 'CHECKING'
  | 'SAVINGS'
  | 'CASH'
  | 'CREDIT_CARD'
  | 'LINE_OF_CREDIT'
  | 'ASSET'
  | 'LIABILITY'
  | 'MORTGAGE'
  | 'LOAN'
  | 'OTHER';

export type Account = {
  id: Uuid;
  budgetId: Uuid;
  name: string;
  type: AccountType;
  onBudget: boolean;
  closed: boolean;
  note: string | null;
  sortOrder: number;
  balance: Milliunits;
  clearedBalance: Milliunits;
  unclearedBalance: Milliunits;
};

export type TargetType = 'MONTHLY_SAVINGS' | 'MONTHLY_SPENDING' | 'TARGET_BALANCE' | 'TARGET_BALANCE_BY_DATE';
export type Target = { type: TargetType; amount: Milliunits; date: IsoDate | null };

export type CategorySystemKind = 'READY_TO_ASSIGN' | 'CREDIT_CARD_PAYMENT';
export type GroupSystemKind = 'INTERNAL' | 'CREDIT_CARD_PAYMENTS';

export type Category = {
  id: Uuid;
  groupId: Uuid;
  name: string;
  hidden: boolean;
  note: string | null;
  sortOrder: number;
  systemKind: CategorySystemKind | null;
  linkedAccountId: Uuid | null;
  target: Target | null;
};

export type CategoryGroup = {
  id: Uuid;
  name: string;
  hidden: boolean;
  sortOrder: number;
  systemKind: GroupSystemKind | null;
  categories: Category[];
};

export type Payee = { id: Uuid; name: string; transferAccountId: Uuid | null };

export type Cleared = 'UNCLEARED' | 'CLEARED' | 'RECONCILED';

export type Split = {
  id: Uuid;
  amount: Milliunits;
  categoryId: Uuid | null;
  categoryName: string | null;
  payeeId: Uuid | null;
  payeeName: string | null;
  memo: string | null;
};

export type Transaction = {
  id: Uuid;
  budgetId: Uuid;
  accountId: Uuid;
  accountName: string;
  date: IsoDate;
  /** Local time of day (HH:MM) when the bank export had it. */
  time: string | null;
  amount: Milliunits;
  payeeId: Uuid | null;
  payeeName: string | null;
  categoryId: Uuid | null;
  categoryName: string | null;
  memo: string | null;
  cleared: Cleared;
  approved: boolean;
  flagColor: string | null;
  transferAccountId: Uuid | null;
  transferTransactionId: Uuid | null;
  importId: string | null;
  splits: Split[];
};

export type CategoryMonth = {
  categoryId: Uuid;
  name: string;
  hidden: boolean;
  systemKind: string | null;
  linkedAccountId: Uuid | null;
  carryover: Milliunits;
  assigned: Milliunits;
  activity: Milliunits;
  available: Milliunits;
  target: Target | null;
  targetNeeded: Milliunits;
};

export type GroupMonth = {
  groupId: Uuid;
  name: string;
  hidden: boolean;
  systemKind: string | null;
  assigned: Milliunits;
  activity: Milliunits;
  available: Milliunits;
  categories: CategoryMonth[];
};

/** groups excludes the INTERNAL group; totals exclude Ready to Assign. */
export type MonthBudget = {
  month: IsoMonth;
  currencyCode: string;
  readyToAssign: Milliunits;
  income: Milliunits;
  assigned: Milliunits;
  activity: Milliunits;
  available: Milliunits;
  overspentLastMonth: Milliunits;
  uncategorized: Milliunits;
  groups: GroupMonth[];
};

export type MonthSummary = {
  month: IsoMonth;
  income: Milliunits;
  assigned: Milliunits;
  activity: Milliunits;
  readyToAssign: Milliunits;
};

export type SplitInput = { amount: Milliunits; categoryId?: Uuid | null; payeeName?: string | null; memo?: string | null };

export type ImportLine = {
  date: IsoDate;
  time?: string;
  amount: Milliunits;
  payeeName?: string;
  memo?: string;
  categoryId?: Uuid;
  importId?: string;
  cleared?: Cleared;
};

export type TransactionFields = {
  accountId: Uuid;
  date: IsoDate;
  /** HH:MM; "" clears it on update. */
  time?: string | null;
  amount: Milliunits;
  payeeId?: Uuid | null;
  payeeName?: string | null;
  categoryId?: Uuid | null;
  /** update_transaction only: make the transaction uncategorized (categoryId null means "unchanged"). */
  clearCategory?: boolean;
  memo?: string | null;
  cleared?: Cleared;
  approved?: boolean;
  flagColor?: string | null;
  transferAccountId?: Uuid | null;
  splits?: SplitInput[] | null;
  importId?: string | null;
};

export type SpendingRow = { categoryId: Uuid; categoryName: string; groupName: string; total: Milliunits };
export type SpendingReport = { rows: SpendingRow[]; total: Milliunits };
export type IncomeExpenseRow = { month: IsoMonth; income: Milliunits; expense: Milliunits; net: Milliunits };
export type NetWorthRow = { month: IsoMonth; assets: Milliunits; liabilities: Milliunits; netWorth: Milliunits };

export type FlowKind = 'INCOME' | 'SPENDING' | 'SAVING';
/** Amounts are positive in their own direction: income received, money spent, money put into savings. */
export type CashFlowMonth = { month: IsoMonth; income: Milliunits; spending: Milliunits; saving: Milliunits; subscriptions: Milliunits };
export type CashFlowRow = {
  month: IsoMonth;
  kind: FlowKind;
  categoryId: Uuid | null;
  categoryName: string | null;
  groupId: Uuid | null;
  groupName: string | null;
  payeeId: Uuid | null;
  payeeName: string | null;
  subscriptionId: Uuid | null;
  /** SAVING: the tracking account the money went to or came from. */
  accountId: Uuid | null;
  accountName: string | null;
  amount: Milliunits;
  count: number;
};
export type CashFlow = { months: CashFlowMonth[]; rows: CashFlowRow[] };

export type PurchaseReview = { rating: number | null; sensibleReason: string | null };
/** One spending line (transaction or split line); amount is positive when spent, negative for a refund. */
export type Payment = {
  transactionId: Uuid;
  splitId: Uuid | null;
  date: IsoDate;
  time: string | null;
  accountId: Uuid;
  accountName: string;
  payeeId: Uuid | null;
  payeeName: string | null;
  categoryId: Uuid | null;
  categoryName: string | null;
  groupName: string | null;
  memo: string | null;
  amount: Milliunits;
  subscriptionId: Uuid | null;
  review: PurchaseReview | null;
};
export type PaymentsQuery = {
  fromMonth?: IsoMonth;
  toMonth?: IsoMonth;
  categoryIds?: Uuid[];
  payeeIds?: Uuid[];
  search?: string;
  minAmount?: Milliunits;
  limit?: number;
};

export type SubscriptionKind = 'SUBSCRIPTION' | 'FIXED';
export type Cadence = 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY';
export type SubscriptionStatus = 'ACTIVE' | 'OVERDUE' | 'ENDED';
export type Subscription = {
  id: Uuid;
  name: string;
  kind: SubscriptionKind;
  cadence: Cadence;
  groupName: string | null;
  note: string | null;
  variable: boolean;
  cancelledOn: IsoDate | null;
  payees: { id: Uuid; name: string }[];
  status: SubscriptionStatus;
  chargeCount: number;
  firstCharge: IsoDate | null;
  lastCharge: IsoDate | null;
  lastAmount: Milliunits;
  previousAmount: Milliunits | null;
  monthlyCost: Milliunits;
  last12Months: Milliunits;
  nextCharge: IsoDate | null;
};
export type SubscriptionFields = {
  name: string;
  kind?: SubscriptionKind;
  cadence?: Cadence;
  groupName?: string | null;
  note?: string | null;
  variable?: boolean;
  payeeIds?: Uuid[];
  cancelledOn?: IsoDate | null;
};

export type Device = {
  id: Uuid;
  name: string;
  price: Milliunits;
  purchasedOn: IsoDate;
  lifespanYears: number;
  active: boolean;
  note: string | null;
  monthlyCost: Milliunits;
  replaceOn: IsoDate;
};
export type DeviceFields = { name: string; price: Milliunits; purchasedOn: IsoDate; lifespanYears: number; active?: boolean; note?: string | null };

/** null = not set; the Reports page falls back to its defaults. */
export type ReportSettings = {
  thingsCategoryIds: Uuid[] | null;
  thingsMonthlyTarget: Milliunits | null;
  focusCategoryIds: Uuid[] | null;
  focusPayeeId: Uuid | null;
  reviewMinAmount: Milliunits | null;
  grossSalaryPerYear: Milliunits | null;
  salaryPayeeId: Uuid | null;
};

type B = { budgetId: Uuid };
type Deleted = { deleted: true };

/** Every action the SPA calls: name -> [input, output]. */
export interface Actions {
  whoami: [Record<string, never>, Me];
  list_api_tokens: [Record<string, never>, ApiToken[]];
  create_api_token: [{ name: string; expiresInDays?: number | null }, CreatedApiToken];
  revoke_api_token: [{ tokenId: Uuid }, { revoked: true }];

  list_budgets: [Record<string, never>, Budget[]];
  get_budget: [B, Budget];
  create_budget: [{ name: string; currencyCode?: string; withDefaultCategories?: boolean }, Budget];
  update_budget: [B & { name?: string; currencyCode?: string }, Budget];
  delete_budget: [B, Deleted];
  list_budget_members: [B, Member[]];
  share_budget: [B & { username: string; role?: Role }, Member[]];
  remove_budget_member: [B & { userId: Uuid }, Member[]];

  list_accounts: [B & { includeClosed?: boolean }, Account[]];
  create_account: [
    B & {
      name: string;
      type: AccountType;
      onBudget?: boolean;
      startingBalance?: Milliunits;
      startingDate?: IsoDate;
      note?: string | null;
    },
    Account,
  ];
  update_account: [B & { accountId: Uuid; name?: string; note?: string | null; closed?: boolean }, Account];
  delete_account: [B & { accountId: Uuid }, Deleted];

  list_categories: [B & { includeHidden?: boolean }, CategoryGroup[]];
  create_category_group: [B & { name: string }, CategoryGroup];
  update_category_group: [B & { groupId: Uuid; name?: string; hidden?: boolean; sortOrder?: number }, CategoryGroup];
  delete_category_group: [B & { groupId: Uuid; reassignToCategoryId?: Uuid | null }, Deleted];
  create_category: [B & { groupId: Uuid; name: string; note?: string | null }, Category];
  update_category: [
    B & { categoryId: Uuid; name?: string; groupId?: Uuid; hidden?: boolean; note?: string | null; sortOrder?: number },
    Category,
  ];
  delete_category: [B & { categoryId: Uuid; reassignToCategoryId?: Uuid | null }, Deleted];

  list_payees: [B, Payee[]];
  update_payee: [B & { payeeId: Uuid; name: string }, Payee];
  merge_payees: [B & { sourcePayeeIds: Uuid[]; targetPayeeId: Uuid }, Payee];

  list_transactions: [
    B & {
      accountId?: Uuid;
      categoryId?: Uuid;
      payeeId?: Uuid;
      sinceDate?: IsoDate;
      untilDate?: IsoDate;
      search?: string;
      unapprovedOnly?: boolean;
      subscriptionsOnly?: boolean;
      limit?: number;
      offset?: number;
    },
    { transactions: Transaction[]; total: number },
  ];
  get_transaction: [B & { transactionId: Uuid }, Transaction];
  create_transaction: [B & TransactionFields, Transaction];
  update_transaction: [B & { transactionId: Uuid } & Partial<TransactionFields>, Transaction];
  delete_transaction: [B & { transactionId: Uuid }, Deleted];
  import_transactions: [
    B & { accountId: Uuid; transactions: ImportLine[]; approved?: boolean },
    { imported: number; duplicates: number; transactionIds: Uuid[]; duplicateImportIds: string[] },
  ];
  approve_transactions: [B & { transactionIds: Uuid[] }, { approved: number }];
  set_transactions_cleared: [B & { transactionIds: Uuid[]; cleared: Cleared }, { updated: number }];
  reconcile_account: [
    B & { accountId: Uuid; statementBalance: Milliunits; statementDate?: IsoDate },
    { reconciledCount: number; adjustmentTransactionId?: Uuid | null },
  ];

  get_month_budget: [B & { month: IsoMonth }, MonthBudget];
  list_month_summaries: [B & { fromMonth: IsoMonth; toMonth: IsoMonth }, MonthSummary[]];
  assign_to_category: [B & { month: IsoMonth; categoryId: Uuid; assigned: Milliunits }, CategoryMonth];
  move_money: [
    B & { month: IsoMonth; amount: Milliunits; fromCategoryId?: Uuid | null; toCategoryId?: Uuid | null },
    MonthBudget,
  ];
  assign_underfunded: [B & { month: IsoMonth }, MonthBudget];
  set_category_target: [B & { categoryId: Uuid; type: TargetType; amount: Milliunits; targetDate?: IsoDate | null }, Category];
  clear_category_target: [B & { categoryId: Uuid }, Category];
  report_spending_by_category: [B & { fromMonth: IsoMonth; toMonth: IsoMonth }, SpendingReport];
  report_income_vs_expense: [B & { fromMonth: IsoMonth; toMonth: IsoMonth }, IncomeExpenseRow[]];
  report_net_worth: [B & { fromMonth: IsoMonth; toMonth: IsoMonth }, NetWorthRow[]];
  report_cash_flow: [B & { fromMonth: IsoMonth; toMonth: IsoMonth }, CashFlow];
  report_payments: [B & PaymentsQuery, { payments: Payment[]; truncated: boolean }];
  review_purchase: [
    B & { transactionId: Uuid; splitId?: Uuid | null; rating?: number | null; sensibleReason?: string | null; clear?: boolean },
    { transactionId: Uuid; splitId: Uuid | null; rating: number | null; sensibleReason: string | null } | { cleared: true },
  ];
  get_report_settings: [B, ReportSettings];
  update_report_settings: [B & { settings: ReportSettings }, ReportSettings];

  list_subscriptions: [B & { asOf?: IsoDate }, Subscription[]];
  create_subscription: [B & SubscriptionFields, Subscription];
  update_subscription: [B & { subscriptionId: Uuid; reactivate?: boolean } & Partial<SubscriptionFields>, Subscription];
  delete_subscription: [B & { subscriptionId: Uuid }, Deleted];

  list_devices: [B, Device[]];
  create_device: [B & DeviceFields, Device];
  update_device: [B & { deviceId: Uuid } & Partial<DeviceFields>, Device];
  delete_device: [B & { deviceId: Uuid }, Deleted];
}

export type ActionName = keyof Actions;
export type ActionInput<N extends ActionName> = Actions[N][0];
export type ActionOutput<N extends ActionName> = Actions[N][1];

export type ApiErrorBody = { error: { code: string; message: string } };

export type AgentStatus = { enabled: boolean; provider: string | null; model: string | null };
export type ChatMessage = { role: 'user' | 'assistant'; content: string };
export type AgentToolCall = { name: string; input: unknown; output: unknown; isError: boolean };
export type AgentChatRequest = { budgetId: Uuid; messages: ChatMessage[] };
export type AgentChatResponse = { reply: string; toolCalls: AgentToolCall[] };
