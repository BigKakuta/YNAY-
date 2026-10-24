import { useEffect, useMemo, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { mutate, useQuery } from '../api/query';
import type { Account, CategoryGroup, Cleared, Payee, SplitInput, Transaction } from '../api/types';
import { CategorySelect, MoneyInput, PayeeInput } from '../components/inputs';
import { useToast } from '../components/toast';
import { ErrorBox, Field, FormModal, Icon, Modal, Money, PageLoading } from '../components/ui';
import { accountTypeLabel, useBudget } from '../context';
import { formatDate, todayIso } from '../lib/dates';
import { formatMoney, formatPlain, parseMoney } from '../lib/money';

const PAGE = 200;

export function RegisterPage() {
  const { accountId } = useParams();
  const { budgetId, accounts, currency, categories } = useBudget();
  const [params, setParams] = useSearchParams();
  const categoryFilter = params.get('category');
  const [search, setSearch] = useState('');
  const debounced = useDebounced(search, 250);
  const [limit, setLimit] = useState(PAGE);
  const [editing, setEditing] = useState<string | null>(null); // txn id or 'new'
  const [dialog, setDialog] = useState<'reconcile' | 'settings' | null>(null);
  const toast = useToast();

  useEffect(() => {
    setEditing(null);
    setLimit(PAGE);
  }, [accountId]);

  const account = accountId ? accounts.find((a) => a.id === accountId) : undefined;
  const txns = useQuery('list_transactions', {
    budgetId,
    accountId,
    categoryId: categoryFilter ?? undefined,
    search: debounced.trim() || undefined,
    limit,
  });
  const unapproved = useQuery('list_transactions', { budgetId, accountId, unapprovedOnly: true, limit: 500 });
  const payees = useQuery('list_payees', { budgetId });

  if (accountId && !account) {
    return (
      <div className="page-body">
        <ErrorBox error={new Error('Account not found')} />
      </div>
    );
  }

  const scope = account ? [account] : accounts.filter((a) => !a.closed);
  const cleared = scope.reduce((a, x) => a + x.clearedBalance, 0);
  const uncleared = scope.reduce((a, x) => a + x.unclearedBalance, 0);
  const working = scope.reduce((a, x) => a + x.balance, 0);
  const unapprovedIds = unapproved.data?.transactions.map((t) => t.id) ?? [];
  const catName = categoryFilter ? categories.flatMap((g) => g.categories).find((c) => c.id === categoryFilter)?.name : null;
  const showAccount = !account;

  return (
    <>
      <header className="page-header">
        <div className="stack" style={{ gap: 2 }}>
          <div className="row">
            <h1>{account ? account.name : 'All accounts'}</h1>
            {account && <span className="badge">{accountTypeLabel(account.type)}</span>}
            {account && !account.onBudget && <span className="badge badge-warn">Tracking</span>}
            {account?.closed && <span className="badge">Closed</span>}
          </div>
          {account?.note && <span className="small muted">{account.note}</span>}
        </div>
        <span className="spacer" />
        <div className="acct-header">
          <div className="balance-box">
            <span className="label">Cleared</span>
            <span className="value"><Money value={cleared} currency={currency} /></span>
          </div>
          <span className="balance-op">+</span>
          <div className="balance-box">
            <span className="label">Uncleared</span>
            <span className="value"><Money value={uncleared} currency={currency} /></span>
          </div>
          <span className="balance-op">=</span>
          <div className="balance-box">
            <span className="label">Working balance</span>
            <span className="value"><Money value={working} currency={currency} /></span>
          </div>
        </div>
        {account && (
          <div className="row">
            <button className="btn" onClick={() => setDialog('reconcile')}>
              <Icon name="check" size={16} /> Reconcile
            </button>
            <button className="btn btn-ghost btn-icon" onClick={() => setDialog('settings')} aria-label="Account settings" title="Account settings">
              <Icon name="settings" />
            </button>
          </div>
        )}
      </header>

      <div className="toolbar">
        <button className="btn btn-primary btn-sm" onClick={() => setEditing('new')} disabled={editing === 'new' || accounts.every((a) => a.closed)}>
          <Icon name="plus" size={14} /> Add transaction
        </button>
        {unapprovedIds.length > 0 && (
          <button
            className="btn btn-success btn-sm"
            onClick={() =>
              mutate('approve_transactions', { budgetId, transactionIds: unapprovedIds }).then(
                (r) => toast.info(`Approved ${r.approved} transaction${r.approved === 1 ? '' : 's'}`),
                toast.error,
              )
            }
          >
            <Icon name="check" size={14} /> Approve all ({unapprovedIds.length})
          </button>
        )}
        {catName && (
          <span className="badge badge-accent" style={{ height: 26 }}>
            Category: {catName}
            <button
              className="btn-link"
              style={{ color: 'inherit', marginLeft: 4 }}
              onClick={() => {
                params.delete('category');
                setParams(params);
              }}
              aria-label="Clear category filter"
            >
              ×
            </button>
          </span>
        )}
        <span className="spacer" />
        <div className="row" style={{ position: 'relative' }}>
          <span style={{ position: 'absolute', left: 9, top: 7, color: 'var(--text-3)' }}>
            <Icon name="search" size={16} />
          </span>
          <input
            className="input input-sm"
            style={{ paddingLeft: 30, width: 240 }}
            type="search"
            placeholder="Search payee, memo, category"
            aria-label="Search transactions"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>

      <div className="table-wrap page-scroll">
        <table className="rtable">
          <thead>
            <tr>
              {showAccount && <th>Account</th>}
              <th className="col-date">Date</th>
              <th>Payee</th>
              <th>Category</th>
              <th>Memo</th>
              <th className="num">Outflow</th>
              <th className="num">Inflow</th>
              <th className="col-clear" title="Cleared">C</th>
              <th className="col-actions"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {editing === 'new' && (
              <TxnEditor
                key="new"
                accountId={account?.id ?? null}
                showAccount={showAccount}
                payees={payees.data ?? []}
                recent={txns.data?.transactions ?? []}
                onDone={() => setEditing(null)}
              />
            )}
            {txns.data?.transactions.map((t) =>
              editing === t.id ? (
                <TxnEditor
                  key={t.id}
                  txn={t}
                  accountId={t.accountId}
                  showAccount={showAccount}
                  payees={payees.data ?? []}
                  recent={txns.data?.transactions ?? []}
                  onDone={() => setEditing(null)}
                />
              ) : (
                <TxnRow key={t.id} t={t} showAccount={showAccount} onEdit={() => setEditing(t.id)} />
              ),
            )}
          </tbody>
        </table>
        {txns.error && <div style={{ padding: 16 }}><ErrorBox error={txns.error} onRetry={txns.reload} /></div>}
        {!txns.data && !txns.error && <PageLoading />}
        {txns.data && txns.data.transactions.length === 0 && editing !== 'new' && (
          <div className="empty">
            <span>{debounced || categoryFilter ? 'No transactions match.' : 'No transactions yet.'}</span>
            {!debounced && !categoryFilter && (
              <div className="row">
                <button className="btn btn-primary btn-sm" onClick={() => setEditing('new')}>
                  Add a transaction
                </button>
                <Link className="btn btn-sm" to={`/budgets/${budgetId}/import`}>
                  Import a statement
                </Link>
              </div>
            )}
          </div>
        )}
        {txns.data && txns.data.total > txns.data.transactions.length && (
          <div className="empty" style={{ padding: 16 }}>
            <button className="btn btn-sm" onClick={() => setLimit((l) => l + PAGE)}>
              Load more ({txns.data.total - txns.data.transactions.length} older)
            </button>
          </div>
        )}
      </div>

      {dialog === 'reconcile' && account && <ReconcileDialog account={account} onClose={() => setDialog(null)} />}
      {dialog === 'settings' && account && <AccountSettingsDialog account={account} onClose={() => setDialog(null)} />}
    </>
  );
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

function TxnRow({ t, showAccount, onEdit }: { t: Transaction; showAccount: boolean; onEdit: () => void }) {
  const { budgetId, currency } = useBudget();
  const toast = useToast();
  const isSplit = t.splits.length > 0;
  const category = isSplit
    ? `Split (${t.splits.length})`
    : t.transferAccountId && !t.categoryId
      ? null
      : (t.categoryName === 'Inflow: Ready to Assign' ? 'Ready to Assign' : t.categoryName);

  const toggleCleared = (e: MouseEvent) => {
    e.stopPropagation();
    if (t.cleared === 'RECONCILED') return;
    const next: Cleared = t.cleared === 'CLEARED' ? 'UNCLEARED' : 'CLEARED';
    mutate('set_transactions_cleared', { budgetId, transactionIds: [t.id], cleared: next }).catch(toast.error);
  };

  return (
    <>
      <tr className={`txn ${t.approved ? '' : 'unapproved'}`} onClick={onEdit} onKeyDown={(e) => e.key === 'Enter' && onEdit()} tabIndex={0} aria-label={`Transaction ${t.payeeName ?? ''} ${formatMoney(t.amount, currency)}`}>
        {showAccount && <td>{t.accountName}</td>}
        <td className="col-date">{formatDate(t.date)}</td>
        <td>
          {t.payeeName ?? <span className="muted">—</span>}
          {!t.approved && (
            <span className="badge badge-warn" style={{ marginLeft: 8 }}>
              New
            </span>
          )}
        </td>
        <td>
          {category ?? (t.transferAccountId ? <span className="muted">Transfer</span> : <span className="badge badge-warn">Uncategorized</span>)}
        </td>
        <td className="muted">{t.memo}</td>
        <td className="num">{t.amount < 0 ? <Money value={-t.amount} currency={currency} signColor={false} /> : null}</td>
        <td className="num">{t.amount > 0 ? <Money value={t.amount} currency={currency} signColor={false} /> : null}</td>
        <td className="col-clear">
          <button
            className={`clear-toggle ${t.cleared === 'CLEARED' ? 'cleared' : t.cleared === 'RECONCILED' ? 'reconciled' : ''}`}
            onClick={toggleCleared}
            title={t.cleared === 'RECONCILED' ? 'Reconciled' : t.cleared === 'CLEARED' ? 'Cleared — click to unclear' : 'Uncleared — click to clear'}
            aria-label={t.cleared === 'RECONCILED' ? 'Reconciled' : t.cleared === 'CLEARED' ? 'Mark uncleared' : 'Mark cleared'}
            aria-pressed={t.cleared !== 'UNCLEARED'}
          >
            {t.cleared === 'RECONCILED' ? 'R' : 'C'}
          </button>
        </td>
        <td className="col-actions">
          {!t.approved && (
            <button
              className="btn btn-success btn-sm"
              onClick={(e) => {
                e.stopPropagation();
                mutate('approve_transactions', { budgetId, transactionIds: [t.id] }).catch(toast.error);
              }}
            >
              Approve
            </button>
          )}
        </td>
      </tr>
      {isSplit &&
        t.splits.map((s) => (
          <tr key={s.id} className={`split-line ${t.approved ? '' : 'unapproved'}`} onClick={onEdit}>
            {showAccount && <td />}
            <td />
            <td className="small">{s.payeeName ? `↳ ${s.payeeName}` : '↳'}</td>
            <td className="small">{s.categoryName ?? 'Uncategorized'}</td>
            <td className="small">{s.memo}</td>
            <td className="num small">{s.amount < 0 ? formatMoney(-s.amount, currency) : ''}</td>
            <td className="num small">{s.amount > 0 ? formatMoney(s.amount, currency) : ''}</td>
            <td />
            <td />
          </tr>
        ))}
    </>
  );
}

type SplitDraft = { key: number; categoryId: string | null; memo: string; outflow: string; inflow: string };
let splitKey = 1;

function amountOf(outflow: string, inflow: string): number | null {
  const o = parseMoney(outflow);
  const i = parseMoney(inflow);
  if (o === null || i === null) return null;
  return Math.abs(i) - Math.abs(o);
}

function splitAmountText(amount: number, currency: string) {
  return amount < 0
    ? { outflow: formatPlain(-amount, currency), inflow: '' }
    : { outflow: '', inflow: amount ? formatPlain(amount, currency) : '' };
}

function TxnEditor({
  txn,
  accountId: fixedAccount,
  showAccount,
  payees,
  recent,
  onDone,
}: {
  txn?: Transaction;
  accountId: string | null;
  showAccount: boolean;
  payees: Payee[];
  recent: Transaction[];
  onDone: () => void;
}) {
  const { budgetId, accounts, categories, currency } = useBudget();
  const toast = useToast();
  const navigate = useNavigate();
  const openAccounts = accounts.filter((a) => !a.closed || a.id === txn?.accountId);
  const [accountId, setAccountId] = useState(fixedAccount ?? openAccounts.find((a) => a.onBudget)?.id ?? openAccounts[0]?.id ?? '');
  const [date, setDate] = useState(txn?.date ?? todayIso());
  const [payee, setPayee] = useState({
    name: txn?.payeeName ?? '',
    payeeId: txn?.payeeId ?? null,
    transferAccountId: txn?.transferAccountId ?? null,
  });
  const [categoryId, setCategoryId] = useState<string | null>(txn?.categoryId ?? null);
  const [memo, setMemo] = useState(txn?.memo ?? '');
  const initial = txn ? splitAmountText(txn.amount, currency) : { outflow: '', inflow: '' };
  const [outflow, setOutflow] = useState(initial.outflow);
  const [inflow, setInflow] = useState(initial.inflow);
  const [cleared, setCleared] = useState<Cleared>(txn?.cleared ?? 'UNCLEARED');
  const [splits, setSplits] = useState<SplitDraft[] | null>(() =>
    txn && txn.splits.length
      ? txn.splits.map((s) => ({ key: splitKey++, categoryId: s.categoryId, memo: s.memo ?? '', ...splitAmountText(s.amount, currency) }))
      : null,
  );
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const account = accounts.find((a) => a.id === accountId);
  const transferTo = payee.transferAccountId ? accounts.find((a) => a.id === payee.transferAccountId) : undefined;
  const transferNoCategory = !!transferTo && !(account?.onBudget && !transferTo.onBudget);
  const trackingAccount = account ? !account.onBudget : false;
  const amount = amountOf(outflow, inflow);
  const splitTotal = splits?.reduce((a, s) => a + (amountOf(s.outflow, s.inflow) ?? 0), 0) ?? 0;
  const splitRemaining = amount === null ? 0 : amount - splitTotal;
  const splitInvalid = !!splits && (splitRemaining !== 0 || splits.some((s) => amountOf(s.outflow, s.inflow) === null));
  const valid = !!accountId && !!date && amount !== null && !splitInvalid;

  // Memorized category: picking a known payee pre-fills its most recent category.
  const choosePayee = (v: { name: string; payeeId: string | null; transferAccountId: string | null }) => {
    setPayee(v);
    if (v.payeeId && !categoryId && !v.transferAccountId && !splits) {
      const last = recent.find((t) => t.payeeId === v.payeeId && t.categoryId);
      if (last) setCategoryId(last.categoryId);
    }
  };

  async function save() {
    if (!valid || busy) return;
    setBusy(true);
    const splitInputs: SplitInput[] | null = splits
      ? splits.map((s) => ({ amount: amountOf(s.outflow, s.inflow)!, categoryId: s.categoryId, memo: s.memo.trim() || null }))
      : null;
    const newCategoryId = splits || transferNoCategory || trackingAccount ? null : categoryId;
    const noPayee = !payee.transferAccountId && !payee.payeeId && !payee.name.trim();
    // On update the backend treats null as "unchanged": clearing a value needs "" or clearCategory.
    const fields = {
      accountId,
      date,
      amount: amount!,
      payeeId: payee.transferAccountId ? undefined : (payee.payeeId ?? undefined),
      payeeName: payee.transferAccountId || payee.payeeId ? undefined : noPayee ? (txn?.payeeId ? '' : undefined) : payee.name.trim(),
      transferAccountId: payee.transferAccountId ?? null,
      categoryId: newCategoryId,
      clearCategory: txn && !splits && newCategoryId === null && txn.categoryId !== null ? true : undefined,
      memo: txn ? memo.trim() : memo.trim() || null,
      cleared,
      approved: true,
      splits: splitInputs ?? (txn?.splits.length ? [] : undefined),
    };
    try {
      if (txn) await mutate('update_transaction', { budgetId, transactionId: txn.id, ...fields });
      else await mutate('create_transaction', { budgetId, ...fields });
      onDone();
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  }

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.defaultPrevented) return;
    if (e.key === 'Enter' && !(e.target instanceof HTMLButtonElement)) {
      e.preventDefault();
      void save();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      onDone();
    }
  };

  const categoryCell = splits ? (
    <span className="badge badge-accent">Split</span>
  ) : trackingAccount ? (
    <span className="small muted">Tracking account — no category</span>
  ) : transferNoCategory ? (
    <span className="small muted">Transfer — no category needed</span>
  ) : (
    <CategorySelect
      groups={categories}
      value={categoryId}
      onChange={setCategoryId}
      ariaLabel="Category"
      emptyLabel={transferTo ? 'Choose a category…' : 'Uncategorized'}
    />
  );

  return (
    <>
      <tr className="editing" onKeyDown={onKeyDown}>
        {showAccount && (
          <td>
            <select className="select" aria-label="Account" value={accountId} onChange={(e) => setAccountId(e.target.value)} disabled={!!txn?.transferAccountId}>
              {openAccounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </td>
        )}
        <td className="col-date">
          <input className="input" type="date" aria-label="Date" value={date} onChange={(e) => setDate(e.target.value)} required />
        </td>
        <td style={{ minWidth: 180 }}>
          <PayeeInput payees={payees} value={payee} onChange={choosePayee} currentAccountId={accountId} autoFocus={!txn} />
        </td>
        <td style={{ minWidth: 180 }}>{categoryCell}</td>
        <td>
          <input className="input" aria-label="Memo" placeholder="Memo" value={memo} onChange={(e) => setMemo(e.target.value)} />
        </td>
        <td className="num">
          <MoneyInput aria-label="Outflow" placeholder="Outflow" value={outflow} onChange={(v) => { setOutflow(v); if (v) setInflow(''); }} />
        </td>
        <td className="num">
          <MoneyInput aria-label="Inflow" placeholder="Inflow" value={inflow} onChange={(v) => { setInflow(v); if (v) setOutflow(''); }} />
        </td>
        <td className="col-clear">
          <button
            type="button"
            className={`clear-toggle ${cleared === 'CLEARED' ? 'cleared' : cleared === 'RECONCILED' ? 'reconciled' : ''}`}
            onClick={() => cleared !== 'RECONCILED' && setCleared(cleared === 'CLEARED' ? 'UNCLEARED' : 'CLEARED')}
            aria-label="Cleared"
            aria-pressed={cleared !== 'UNCLEARED'}
          >
            {cleared === 'RECONCILED' ? 'R' : 'C'}
          </button>
        </td>
        <td className="col-actions" />
      </tr>
      {splits && (
        <tr className="editing" onKeyDown={onKeyDown}>
          <td colSpan={showAccount ? 9 : 8}>
            <SplitEditor splits={splits} setSplits={setSplits} categories={categories} remaining={splitRemaining} currency={currency} />
          </td>
        </tr>
      )}
      <tr className="editing">
        <td colSpan={showAccount ? 9 : 8} style={{ paddingTop: 0 }}>
          <div className="edit-actions">
            {txn && (
              <button type="button" className="btn btn-danger btn-sm" onClick={() => setConfirmDelete(true)}>
                <Icon name="trash" size={14} /> Delete
              </button>
            )}
            {txn?.transferAccountId && (
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate(`/budgets/${budgetId}/accounts/${txn.transferAccountId}`)}>
                Go to other side
              </button>
            )}
            {!payee.transferAccountId && !trackingAccount && (
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() =>
                  setSplits((s) =>
                    s
                      ? null
                      : [
                          { key: splitKey++, categoryId, memo: '', ...splitAmountText(amount ?? 0, currency) },
                          { key: splitKey++, categoryId: null, memo: '', outflow: '', inflow: '' },
                        ],
                  )
                }
              >
                <Icon name="split" size={14} /> {splits ? 'Remove split' : 'Split'}
              </button>
            )}
            <span className="spacer" />
            <span className="xs muted" style={{ alignSelf: 'center' }}>
              <kbd>Enter</kbd> save · <kbd>Esc</kbd> cancel
            </span>
            <button type="button" className="btn btn-sm" onClick={onDone}>
              Cancel
            </button>
            <button type="button" className="btn btn-primary btn-sm" onClick={() => void save()} disabled={!valid || busy}>
              {busy ? 'Saving…' : txn && !txn.approved ? 'Save & approve' : 'Save'}
            </button>
          </div>
        </td>
      </tr>
      {confirmDelete && txn && (
        <Modal
          title="Delete transaction?"
          onClose={() => setConfirmDelete(false)}
          footer={
            <>
              <button className="btn" onClick={() => setConfirmDelete(false)}>
                Cancel
              </button>
              <button
                className="btn btn-danger-solid"
                onClick={() =>
                  mutate('delete_transaction', { budgetId, transactionId: txn.id }).then(() => {
                    setConfirmDelete(false);
                    onDone();
                  }, toast.error)
                }
              >
                Delete
              </button>
            </>
          }
        >
          <p>
            {txn.payeeName ?? 'This transaction'} · {formatDate(txn.date)} · {formatMoney(txn.amount, currency)}
            {txn.transferAccountId && ' — the other side of this transfer is deleted too.'}
          </p>
        </Modal>
      )}
    </>
  );
}

function SplitEditor({
  splits,
  setSplits,
  categories,
  remaining,
  currency,
}: {
  splits: SplitDraft[];
  setSplits: (f: (s: SplitDraft[] | null) => SplitDraft[] | null) => void;
  categories: CategoryGroup[];
  remaining: number;
  currency: string;
}) {
  const update = (key: number, patch: Partial<SplitDraft>) =>
    setSplits((s) => s?.map((x) => (x.key === key ? { ...x, ...patch } : x)) ?? null);
  return (
    <div className="split-editor">
      {splits.map((s, i) => (
        <div className="split-line-edit" key={s.key}>
          <span className="small muted">Split line {i + 1}</span>
          <CategorySelect groups={categories} value={s.categoryId} onChange={(v) => update(s.key, { categoryId: v })} ariaLabel={`Split ${i + 1} category`} />
          <input className="input" placeholder="Memo" aria-label={`Split ${i + 1} memo`} value={s.memo} onChange={(e) => update(s.key, { memo: e.target.value })} />
          <MoneyInput placeholder="Outflow" aria-label={`Split ${i + 1} outflow`} value={s.outflow} onChange={(v) => update(s.key, { outflow: v, inflow: v ? '' : s.inflow })} />
          <MoneyInput placeholder="Inflow" aria-label={`Split ${i + 1} inflow`} value={s.inflow} onChange={(v) => update(s.key, { inflow: v, outflow: v ? '' : s.outflow })} />
          <button
            type="button"
            className="btn btn-ghost btn-icon"
            aria-label={`Remove split ${i + 1}`}
            disabled={splits.length <= 2}
            onClick={() => setSplits((x) => x?.filter((y) => y.key !== s.key) ?? null)}
          >
            <Icon name="x" size={14} />
          </button>
        </div>
      ))}
      <div className="split-summary">
        <button
          type="button"
          className="btn btn-sm"
          onClick={() =>
            setSplits((x) => [...(x ?? []), { key: splitKey++, categoryId: null, memo: '', ...splitAmountText(remaining, currency) }])
          }
        >
          <Icon name="plus" size={14} /> Add split line
        </button>
        {remaining === 0 ? (
          <span style={{ color: 'var(--pos)' }}>Splits add up ✓</span>
        ) : (
          <span style={{ color: 'var(--neg)' }}>
            {formatMoney(Math.abs(remaining), currency)} {remaining < 0 ? 'more outflow' : 'more inflow'} to assign to a split line
          </span>
        )}
      </div>
    </div>
  );
}

function ReconcileDialog({ account, onClose }: { account: Account; onClose: () => void }) {
  const { budgetId, currency } = useBudget();
  const toast = useToast();
  const [balance, setBalance] = useState(formatPlain(account.clearedBalance, currency));
  const [date, setDate] = useState(todayIso());
  const parsed = parseMoney(balance);
  const diff = parsed === null ? 0 : parsed - account.clearedBalance;
  return (
    <FormModal
      title={`Reconcile ${account.name}`}
      submitLabel={diff === 0 ? 'Reconcile' : 'Create adjustment & reconcile'}
      onClose={onClose}
      disabled={parsed === null}
      onSubmit={async () => {
        const r = await mutate('reconcile_account', { budgetId, accountId: account.id, statementBalance: parsed!, statementDate: date });
        toast.info(`Reconciled ${r.reconciledCount} transaction${r.reconciledCount === 1 ? '' : 's'}${r.adjustmentTransactionId ? ' and added a balance adjustment' : ''}`);
        onClose();
      }}
    >
      <p className="small">
        Your cleared balance is <strong><Money value={account.clearedBalance} currency={currency} /></strong>. Enter the balance on your statement; cleared
        transactions up to the statement date will be locked as reconciled.
      </p>
      <div className="field-row">
        <Field label="Statement balance">
          <MoneyInput value={balance} onChange={setBalance} autoFocus />
        </Field>
        <Field label="Statement date">
          <input className="input" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </Field>
      </div>
      {diff !== 0 && parsed !== null && (
        <div className="alert alert-warn">
          The statement differs from your cleared balance by {formatMoney(diff, currency)}. An adjustment transaction will be created.
        </div>
      )}
    </FormModal>
  );
}

function AccountSettingsDialog({ account, onClose }: { account: Account; onClose: () => void }) {
  const { budgetId } = useBudget();
  const navigate = useNavigate();
  const toast = useToast();
  const [name, setName] = useState(account.name);
  const [note, setNote] = useState(account.note ?? '');
  const [closed, setClosed] = useState(account.closed);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const deleteable = useMemo(() => account.balance === 0, [account.balance]);

  if (confirmDelete) {
    return (
      <FormModal
        title={`Delete ${account.name}?`}
        submitLabel="Delete account"
        danger
        onClose={onClose}
        onSubmit={async () => {
          await mutate('delete_account', { budgetId, accountId: account.id });
          toast.info('Account deleted');
          navigate(`/budgets/${budgetId}/accounts`);
        }}
      >
        <p className="small">This permanently deletes the account and all of its transactions. Closing it keeps the history instead.</p>
      </FormModal>
    );
  }
  return (
    <FormModal
      title="Account settings"
      onClose={onClose}
      disabled={!name.trim()}
      onSubmit={async () => {
        await mutate('update_account', {
          budgetId,
          accountId: account.id,
          name: name.trim(),
          note: note.trim(), // "" clears the note; null would mean "unchanged"
          closed: closed !== account.closed ? closed : undefined,
        });
        onClose();
      }}
    >
      <Field label="Name">
        <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <Field label="Note">
        <textarea className="textarea" rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <label className="check">
        <input type="checkbox" checked={closed} onChange={(e) => setClosed(e.target.checked)} />
        Account is closed {!deleteable && !account.closed && <span className="muted">(balance must be zero)</span>}
      </label>
      <div>
        <button type="button" className="btn btn-danger btn-sm" onClick={() => setConfirmDelete(true)}>
          <Icon name="trash" size={14} /> Delete account…
        </button>
      </div>
      <span className="xs muted">
        Account id <code>{account.id}</code> — handy when telling an MCP client which account to import into.
      </span>
    </FormModal>
  );
}

