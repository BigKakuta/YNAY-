import { useEffect, useMemo, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router';
import { logout } from '../api/client';
import { mutate, useQuery } from '../api/query';
import type { Account } from '../api/types';
import { MoneyInput } from '../components/inputs';
import { ErrorBox, Field, FormModal, Icon, Money, PageLoading, initials } from '../components/ui';
import { ACCOUNT_TYPES, BudgetContext, useBudget, useMe, type BudgetCtx } from '../context';
import { currentMonth, todayIso } from '../lib/dates';
import { parseMoney } from '../lib/money';

export function BudgetLayout() {
  const { budgetId = '' } = useParams();
  const budget = useQuery('get_budget', { budgetId });
  const accounts = useQuery('list_accounts', { budgetId, includeClosed: true });
  const categories = useQuery('list_categories', { budgetId, includeHidden: true });
  const [navOpen, setNavOpen] = useState(false);
  const location = useLocation();
  useEffect(() => setNavOpen(false), [location.pathname]);

  const ctx = useMemo<BudgetCtx | null>(
    () =>
      budget.data && accounts.data && categories.data
        ? { budgetId, budget: budget.data, currency: budget.data.currencyCode, accounts: accounts.data, categories: categories.data }
        : null,
    [budgetId, budget.data, accounts.data, categories.data],
  );

  const error = budget.error ?? accounts.error ?? categories.error;
  if (error && !ctx) {
    return (
      <div className="page-narrow stack">
        <ErrorBox error={error} onRetry={() => void budget.reload()} />
        <Link to="/">Back to your budgets</Link>
      </div>
    );
  }
  if (!ctx) return <PageLoading />;

  return (
    <BudgetContext.Provider value={ctx}>
      <div className={`shell ${navOpen ? 'nav-open' : ''}`} onClick={(e) => navOpen && e.target === e.currentTarget && setNavOpen(false)}>
        <Sidebar onClose={() => setNavOpen(false)} />
        <div className="main">
          <div className="mobile-bar">
            <button onClick={() => setNavOpen(true)} aria-label="Open navigation">
              <Icon name="menu" />
            </button>
            <strong>{ctx.budget.name}</strong>
          </div>
          <Outlet />
        </div>
      </div>
    </BudgetContext.Provider>
  );
}

function Sidebar({ onClose }: { onClose: () => void }) {
  const me = useMe();
  const { budget, budgetId, accounts, currency } = useBudget();
  const { pathname } = useLocation();
  const [adding, setAdding] = useState(false);
  const [showClosed, setShowClosed] = useState(false);
  const unapproved = useQuery('list_transactions', { budgetId, unapprovedOnly: true, limit: 500 });
  const pending = useMemo(() => {
    const m = new Map<string, number>();
    for (const t of unapproved.data?.transactions ?? []) m.set(t.accountId, (m.get(t.accountId) ?? 0) + 1);
    return m;
  }, [unapproved.data]);

  const open = accounts.filter((a) => !a.closed);
  const groups: { key: string; label: string; items: Account[]; collapsible?: boolean }[] = [
    { key: 'on', label: 'On budget', items: open.filter((a) => a.onBudget) },
    { key: 'tracking', label: 'Tracking', items: open.filter((a) => !a.onBudget) },
    { key: 'closed', label: 'Closed', items: accounts.filter((a) => a.closed), collapsible: true },
  ];

  return (
    <aside className="sidebar" aria-label="Budget navigation">
      <Link to="/" className="sidebar-budget" title="Switch budget">
        <span className="logo-mark">Y</span>
        <span className="grow" style={{ minWidth: 0 }}>
          <div className="title">{budget.name}</div>
          <div className="sub">Switch budget</div>
        </span>
        <Icon name="down" size={16} />
      </Link>
      <nav className="nav">
        <NavLink to={`/budgets/${budgetId}/budget/${currentMonth()}`} className={({ isActive }) => (isActive || pathname.includes('/budget/') ? 'active' : '')}>
          <Icon name="budget" /> Budget
        </NavLink>
        <NavLink to={`/budgets/${budgetId}/reports`}>
          <Icon name="reports" /> Reports
        </NavLink>
        <NavLink to={`/budgets/${budgetId}/import`}>
          <Icon name="import" /> Import assistant
        </NavLink>
        <NavLink to={`/budgets/${budgetId}/accounts`} end>
          <Icon name="accounts" /> All accounts
        </NavLink>
        <NavLink to={`/budgets/${budgetId}/settings`}>
          <Icon name="settings" /> Settings
        </NavLink>
      </nav>

      {groups.map((g) => {
        if (g.items.length === 0) return null;
        const total = g.items.reduce((a, x) => a + x.balance, 0);
        const collapsed = g.collapsible && !showClosed;
        return (
          <div className="acct-group" key={g.key}>
            <button className="acct-group-head" onClick={() => g.collapsible && setShowClosed((v) => !v)} aria-expanded={!collapsed}>
              <span>
                {g.label}
                {g.collapsible && ` (${g.items.length})`}
              </span>
              {!g.collapsible && <Money value={total} currency={currency} signColor={false} />}
            </button>
            {!collapsed &&
              g.items.map((a) => (
                <NavLink key={a.id} to={`/budgets/${budgetId}/accounts/${a.id}`} className="acct-link" title={a.name}>
                  {pending.get(a.id) ? <span className="dot" title={`${pending.get(a.id)} to approve`} /> : null}
                  <span className="name">{a.name}</span>
                  <Money value={a.balance} currency={currency} />
                </NavLink>
              ))}
          </div>
        );
      })}

      <button className="btn-side" onClick={() => setAdding(true)}>
        <Icon name="plus" size={16} /> Add account
      </button>

      <div className="sidebar-footer">
        <div className="user-chip">
          <span className="avatar">{initials(me.displayName ?? me.username)}</span>
          <span className="grow" style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {me.displayName ?? me.username}
          </span>
        </div>
        <button className="btn-side" onClick={() => void logout()}>
          <Icon name="logout" size={16} /> Sign out
        </button>
      </div>
      {adding && (
        <AddAccountDialog
          onClose={() => {
            setAdding(false);
            onClose();
          }}
        />
      )}
    </aside>
  );
}

function AddAccountDialog({ onClose }: { onClose: () => void }) {
  const { budgetId } = useBudget();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [type, setType] = useState<Account['type']>('CHECKING');
  const [balance, setBalance] = useState('');
  const [date, setDate] = useState(todayIso());
  const [owed, setOwed] = useState(true);
  const debtType = ['CREDIT_CARD', 'LINE_OF_CREDIT', 'LIABILITY', 'MORTGAGE', 'LOAN'].includes(type);
  const parsed = parseMoney(balance);
  const info = ACCOUNT_TYPES.find((t) => t.value === type)!;

  return (
    <FormModal
      title="Add account"
      submitLabel="Add account"
      onClose={onClose}
      disabled={!name.trim() || parsed === null}
      onSubmit={async () => {
        const amount = parsed ?? 0;
        const startingBalance = debtType && owed ? -Math.abs(amount) : amount;
        const a = await mutate('create_account', { budgetId, name: name.trim(), type, startingBalance, startingDate: date });
        onClose();
        navigate(`/budgets/${budgetId}/accounts/${a.id}`);
      }}
    >
      <Field label="Account name">
        <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Checking" autoFocus />
      </Field>
      <Field
        label="Type"
        hint={info.onBudget ? 'On-budget: transactions affect your budget.' : 'Tracking: shows in net worth, but does not affect your budget.'}
      >
        <select className="select" value={type} onChange={(e) => setType(e.target.value as Account['type'])}>
          <optgroup label="Budget accounts">
            {ACCOUNT_TYPES.filter((t) => t.onBudget).map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </optgroup>
          <optgroup label="Tracking accounts">
            {ACCOUNT_TYPES.filter((t) => !t.onBudget).map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </optgroup>
        </select>
      </Field>
      <div className="field-row">
        <Field label={debtType ? 'Current balance owed' : 'Current balance'}>
          <MoneyInput value={balance} onChange={setBalance} placeholder="0.00" />
        </Field>
        <Field label="As of">
          <input className="input" type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
        </Field>
      </div>
      {debtType && (
        <label className="check">
          <input type="checkbox" checked={owed} onChange={(e) => setOwed(e.target.checked)} />
          This is money I owe (stored as a negative balance)
        </label>
      )}
    </FormModal>
  );
}
