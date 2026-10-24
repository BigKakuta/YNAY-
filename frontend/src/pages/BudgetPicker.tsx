import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { logout } from '../api/client';
import { mutate, useQuery } from '../api/query';
import { Field, FormModal, Icon, PageLoading, ErrorBox, initials } from '../components/ui';
import { useMe } from '../context';
import { formatDate } from '../lib/dates';

const COMMON_CURRENCIES = ['USD', 'EUR', 'GBP', 'CAD', 'AUD', 'CHF', 'JPY', 'SEK', 'NOK', 'DKK', 'PLN', 'CZK', 'NZD', 'INR', 'BRL', 'MXN'];

export function currencyOptions(): string[] {
  try {
    const all = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('currency');
    if (all?.length) return [...COMMON_CURRENCIES, ...all.filter((c) => !COMMON_CURRENCIES.includes(c))];
  } catch {
    // older browsers
  }
  return COMMON_CURRENCIES;
}

export function currencyName(code: string): string {
  try {
    return new Intl.DisplayNames(undefined, { type: 'currency' }).of(code) ?? code;
  } catch {
    return code;
  }
}

export function CurrencySelect({ value, onChange, id }: { value: string; onChange: (c: string) => void; id?: string }) {
  const options = currencyOptions();
  return (
    <select id={id} className="select" value={value} onChange={(e) => onChange(e.target.value)}>
      {!options.includes(value) && <option value={value}>{value}</option>}
      {options.map((c, i) => (
        <option key={c} value={c} style={i === COMMON_CURRENCIES.length - 1 ? { borderBottom: '1px solid' } : undefined}>
          {c} — {currencyName(c)}
        </option>
      ))}
    </select>
  );
}

export function TopBar() {
  const me = useMe();
  return (
    <header className="topbar">
      <Link to="/" className="logo" style={{ color: 'inherit', textDecoration: 'none' }}>
        <span className="logo-mark">Y</span> YNAY
      </Link>
      <span className="spacer" />
      <Link to="/settings" className="btn btn-ghost btn-sm">
        <Icon name="settings" size={16} /> Settings
      </Link>
      <span className="row small" style={{ color: 'var(--text-2)' }}>
        <span className="avatar">{initials(me.displayName ?? me.username)}</span>
        {me.displayName ?? me.username}
      </span>
      <button className="btn btn-ghost btn-sm" onClick={() => void logout()}>
        <Icon name="logout" size={16} /> Sign out
      </button>
    </header>
  );
}

export function BudgetPicker() {
  const budgets = useQuery('list_budgets', {});
  const [creating, setCreating] = useState(false);

  return (
    <>
      <TopBar />
      <main className="page-narrow stack-lg">
        <div className="stack" style={{ gap: 4 }}>
          <h1>Your budgets</h1>
          <p className="muted">Pick a budget to open, or start a new one.</p>
        </div>
        {budgets.error && <ErrorBox error={budgets.error} onRetry={budgets.reload} />}
        {!budgets.data && !budgets.error ? (
          <PageLoading />
        ) : (
          <div className="budget-grid">
            {budgets.data?.map((b) => (
              <Link key={b.id} to={`/budgets/${b.id}`} className="card budget-tile">
                <span className="name">{b.name}</span>
                <span className="row small muted">
                  <span className="badge">{b.currencyCode}</span>
                  <span className={`badge ${b.role === 'OWNER' ? 'badge-accent' : ''}`}>{b.role === 'OWNER' ? 'Owner' : 'Editor'}</span>
                </span>
                <span className="xs muted">Created {formatDate(b.createdAt.slice(0, 10))}</span>
              </Link>
            ))}
            <button className="card budget-tile budget-tile-new" onClick={() => setCreating(true)}>
              <Icon name="plus" size={22} />
              <span>New budget</span>
            </button>
          </div>
        )}
      </main>
      {creating && <CreateBudgetDialog onClose={() => setCreating(false)} />}
    </>
  );
}

function CreateBudgetDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [currency, setCurrency] = useState(guessCurrency());
  const [defaults, setDefaults] = useState(true);
  return (
    <FormModal
      title="New budget"
      submitLabel="Create budget"
      onClose={onClose}
      disabled={!name.trim()}
      onSubmit={async () => {
        const b = await mutate('create_budget', { name: name.trim(), currencyCode: currency, withDefaultCategories: defaults });
        navigate(`/budgets/${b.id}`);
      }}
    >
      <Field label="Name">
        <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Household" autoFocus />
      </Field>
      <Field label="Currency">
        <CurrencySelect value={currency} onChange={setCurrency} />
      </Field>
      <label className="check">
        <input type="checkbox" checked={defaults} onChange={(e) => setDefaults(e.target.checked)} />
        Start with suggested categories (Bills, Needs, Wants, Savings)
      </label>
    </FormModal>
  );
}

function guessCurrency(): string {
  const region = (navigator.language.split('-')[1] ?? '').toUpperCase();
  const map: Record<string, string> = { US: 'USD', GB: 'GBP', CA: 'CAD', AU: 'AUD', CH: 'CHF', JP: 'JPY', SE: 'SEK', NO: 'NOK', DK: 'DKK', PL: 'PLN', NZ: 'NZD', IN: 'INR' };
  const euro = ['DE', 'FR', 'NL', 'BE', 'AT', 'IT', 'ES', 'PT', 'FI', 'IE', 'LU', 'GR', 'SK', 'SI', 'EE', 'LV', 'LT'];
  return map[region] ?? (euro.includes(region) ? 'EUR' : 'USD');
}
