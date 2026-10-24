import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { mutate, useQuery } from '../api/query';
import type { CategoryMonth, GroupMonth, MonthBudget, Target, TargetType } from '../api/types';
import { AssignInput, CategorySelect, MoneyInput } from '../components/inputs';
import { useToast } from '../components/toast';
import { ErrorBox, Field, FormModal, Icon, Money, PageLoading } from '../components/ui';
import { useBudget } from '../context';
import { addMonths, currentMonth, formatMonthLong, formatMonthShort, isMonth } from '../lib/dates';
import { formatMoney, formatPlain, parseMoney } from '../lib/money';

function useLocalSet(key: string) {
  const [set, setSet] = useState<Set<string>>(() => {
    try {
      return new Set(JSON.parse(localStorage.getItem(key) ?? '[]') as string[]);
    } catch {
      return new Set();
    }
  });
  const toggle = (id: string) =>
    setSet((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      try {
        localStorage.setItem(key, JSON.stringify([...next]));
      } catch {
        // ignore
      }
      return next;
    });
  return [set, toggle] as const;
}

export function availableClass(c: Pick<CategoryMonth, 'available' | 'targetNeeded'>): string {
  if (c.available < 0) return 'pill-neg';
  if (c.targetNeeded > 0) return 'pill-warn';
  if (c.available > 0) return 'pill-pos';
  return '';
}

export function BudgetMonthPage() {
  const { month = '' } = useParams();
  if (!isMonth(month)) return <Navigate to={`../budget/${currentMonth()}`} replace />;
  return <MonthView month={month} />;
}

function MonthView({ month }: { month: string }) {
  const { budgetId, currency } = useBudget();
  const navigate = useNavigate();
  const toast = useToast();
  const mb = useQuery('get_month_budget', { budgetId, month });
  const [showHidden, setShowHidden] = useState(false);
  const [collapsed, toggleCollapsed] = useLocalSet(`ynay.collapsed.${budgetId}`);
  const [selected, setSelected] = useState<string | null>(null);
  const [moving, setMoving] = useState<{ from?: string | null; to?: string | null } | null>(null);
  const [addingGroup, setAddingGroup] = useState(false);
  const [addingIn, setAddingIn] = useState<string | null>(null);
  const [editingGroup, setEditingGroup] = useState<GroupMonth | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement || e.target instanceof HTMLSelectElement) return;
      if (e.key === 'ArrowLeft' && e.altKey) navigate(`/budgets/${budgetId}/budget/${addMonths(month, -1)}`);
      if (e.key === 'ArrowRight' && e.altKey) navigate(`/budgets/${budgetId}/budget/${addMonths(month, 1)}`);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [month, navigate, budgetId]);

  const data = mb.data;
  const underfunded = useMemo(
    () => data?.groups.flatMap((g) => g.categories).reduce((a, c) => a + (c.hidden ? 0 : c.targetNeeded), 0) ?? 0,
    [data],
  );
  const selectedCat = data?.groups.flatMap((g) => g.categories).find((c) => c.categoryId === selected) ?? null;

  if (mb.error && !data) return <div className="page-body"><ErrorBox error={mb.error} onRetry={mb.reload} /></div>;
  if (!data) return <PageLoading />;

  const assign = (categoryId: string, assigned: number) =>
    mutate('assign_to_category', { budgetId, month, categoryId, assigned }).catch((e) => {
      toast.error(e);
      throw e;
    });

  const rtaClass = data.readyToAssign > 0 ? 'pos' : data.readyToAssign < 0 ? 'neg' : 'zero';
  const monthLink = (m: string) => `/budgets/${budgetId}/budget/${m}`;

  return (
    <>
      <header className="page-header">
        <div className="month-switch">
          <Link className="btn btn-ghost btn-icon" to={monthLink(addMonths(month, -1))} aria-label="Previous month" title="Previous month (Alt+←)">
            <Icon name="left" />
          </Link>
          <span className="label">{formatMonthLong(month)}</span>
          <Link className="btn btn-ghost btn-icon" to={monthLink(addMonths(month, 1))} aria-label="Next month" title="Next month (Alt+→)">
            <Icon name="right" />
          </Link>
          {month !== currentMonth() && (
            <Link className="btn btn-sm" to={monthLink(currentMonth())}>
              Today
            </Link>
          )}
        </div>
        <span className="spacer" />
        <div className={`rta ${rtaClass}`} data-testid="rta">
          <div>
            <div className="rta-amount">{formatMoney(data.readyToAssign, currency)}</div>
            <div className="rta-label">
              {data.readyToAssign < 0 ? 'Assigned more than you have' : data.readyToAssign === 0 ? 'All money assigned' : 'Ready to Assign'}
            </div>
          </div>
          <div className="stack" style={{ gap: 6 }}>
            <button
              className="btn btn-sm"
              disabled={underfunded === 0}
              onClick={() =>
                mutate('assign_underfunded', { budgetId, month }).then(
                  () => toast.info('Assigned underfunded targets'),
                  toast.error,
                )
              }
              title="Assign what every target still needs this month"
            >
              <Icon name="target" size={14} />
              Assign underfunded{underfunded > 0 && `: ${formatMoney(underfunded, currency)}`}
            </button>
            <button className="btn btn-sm" onClick={() => setMoving({})}>
              <Icon name="swap" size={14} /> Move money
            </button>
          </div>
        </div>
      </header>

      <div className="toolbar">
        {addingGroup ? (
          <InlineAdd
            placeholder="New group name"
            onCancel={() => setAddingGroup(false)}
            onSubmit={(name) => mutate('create_category_group', { budgetId, name }).then(() => setAddingGroup(false))}
          />
        ) : (
          <button className="btn btn-sm" onClick={() => setAddingGroup(true)}>
            <Icon name="plus" size={14} /> Category group
          </button>
        )}
        <span className="spacer" />
        <MonthFacts data={data} currency={currency} />
        <label className="check">
          <input type="checkbox" checked={showHidden} onChange={(e) => setShowHidden(e.target.checked)} /> Show hidden
        </label>
      </div>

      <div className={`month-layout ${selectedCat ? 'with-panel' : ''}`}>
        <div className="table-wrap">
          <table className="btable">
            <thead>
              <tr>
                <th>Category</th>
                <th className="num">Assigned</th>
                <th className="num">Activity</th>
                <th className="num">Available</th>
              </tr>
            </thead>
            <tbody>
              {data.groups
                .filter((g) => showHidden || !g.hidden)
                .map((g) => {
                  const isCollapsed = collapsed.has(g.groupId);
                  const cats = g.categories.filter((c) => showHidden || !c.hidden);
                  const system = g.systemKind === 'CREDIT_CARD_PAYMENTS';
                  if (system && cats.length === 0) return null;
                  return (
                    <GroupRows
                      key={g.groupId}
                      group={g}
                      cats={cats}
                      collapsed={isCollapsed}
                      currency={currency}
                      selected={selected}
                      adding={addingIn === g.groupId}
                      onToggle={() => toggleCollapsed(g.groupId)}
                      onAdd={system ? undefined : () => setAddingIn(g.groupId)}
                      onEdit={system ? undefined : () => setEditingGroup(g)}
                      onCancelAdd={() => setAddingIn(null)}
                      onCreate={(name) =>
                        mutate('create_category', { budgetId, groupId: g.groupId, name }).then(() => setAddingIn(null))
                      }
                      onSelect={(id) => setSelected((s) => (s === id ? null : id))}
                      onAssign={assign}
                    />
                  );
                })}
            </tbody>
            <tfoot>
              <tr className="group-row">
                <td>Total</td>
                <td className="num"><Money value={data.assigned} currency={currency} /></td>
                <td className="num"><Money value={data.activity} currency={currency} /></td>
                <td className="num"><Money value={data.available} currency={currency} /></td>
              </tr>
            </tfoot>
          </table>
        </div>
        {selectedCat && (
          <CategoryPanel
            key={selectedCat.categoryId}
            cat={selectedCat}
            month={month}
            onClose={() => setSelected(null)}
            onMove={(from) => setMoving(from)}
          />
        )}
      </div>

      {moving && <MoveMoneyDialog month={month} data={data} initial={moving} onClose={() => setMoving(null)} />}
      {editingGroup && <GroupDialog group={editingGroup} onClose={() => setEditingGroup(null)} />}
    </>
  );
}

function MonthFacts({ data, currency }: { data: MonthBudget; currency: string }) {
  return (
    <span className="row small muted" style={{ gap: 16, marginRight: 8 }}>
      <span>
        Income <Money value={data.income} currency={currency} signColor={false} />
      </span>
      {data.overspentLastMonth > 0 && (
        <span style={{ color: 'var(--neg)' }}>
          Overspent in {formatMonthShort(addMonths(data.month, -1))}: {formatMoney(data.overspentLastMonth, currency)}
        </span>
      )}
      {data.uncategorized !== 0 && (
        <span style={{ color: 'var(--warn)' }}>Uncategorized: {formatMoney(data.uncategorized, currency)}</span>
      )}
    </span>
  );
}

function GroupRows(props: {
  group: GroupMonth;
  cats: CategoryMonth[];
  collapsed: boolean;
  currency: string;
  selected: string | null;
  adding: boolean;
  onToggle: () => void;
  onAdd?: () => void;
  onEdit?: () => void;
  onCancelAdd: () => void;
  onCreate: (name: string) => Promise<unknown>;
  onSelect: (id: string) => void;
  onAssign: (categoryId: string, assigned: number) => Promise<unknown>;
}) {
  const { group: g, cats, collapsed, currency } = props;
  return (
    <>
      <tr className={`group-row ${g.hidden ? 'hidden-row' : ''}`}>
        <td>
          <div className="row" style={{ gap: 2 }}>
            <button
              className={`group-toggle ${collapsed ? 'collapsed' : ''}`}
              onClick={props.onToggle}
              aria-label={collapsed ? `Expand ${g.name}` : `Collapse ${g.name}`}
              aria-expanded={!collapsed}
            >
              <Icon name="down" size={14} />
            </button>
            <span>{g.name}</span>
            {g.hidden && <span className="badge">hidden</span>}
            {props.onAdd && (
              <button className="btn btn-ghost btn-icon" style={{ width: 24, height: 24 }} onClick={props.onAdd} aria-label={`Add category to ${g.name}`} title="Add category">
                <Icon name="plus" size={14} />
              </button>
            )}
            {props.onEdit && (
              <button className="btn btn-ghost btn-icon" style={{ width: 24, height: 24 }} onClick={props.onEdit} aria-label={`Edit group ${g.name}`} title="Edit group">
                <Icon name="edit" size={13} />
              </button>
            )}
          </div>
        </td>
        <td className="num"><Money value={g.assigned} currency={currency} signColor={false} /></td>
        <td className="num"><Money value={g.activity} currency={currency} signColor={false} /></td>
        <td className="num"><Money value={g.available} currency={currency} signColor={false} /></td>
      </tr>
      {props.adding && (
        <tr>
          <td colSpan={4} style={{ paddingLeft: 36 }}>
            <InlineAdd placeholder="New category name" onCancel={props.onCancelAdd} onSubmit={props.onCreate} />
          </td>
        </tr>
      )}
      {!collapsed &&
        cats.map((c) => (
          <tr key={c.categoryId} className={`cat-row ${props.selected === c.categoryId ? 'selected' : ''} ${c.hidden ? 'hidden-row' : ''}`}>
            <td>
              <div className="cat-name">
                <button onClick={() => props.onSelect(c.categoryId)} title="Show details">
                  {c.name}
                </button>
                {c.target && <TargetHint c={c} currency={currency} />}
              </div>
            </td>
            <td className="num">
              <AssignInput value={c.assigned} currency={currency} label={`Assigned to ${c.name}`} onCommit={(v) => props.onAssign(c.categoryId, v)} />
            </td>
            <td className="num">
              <Money value={c.activity} currency={currency} signColor={false} className={c.activity === 0 ? 'zero' : ''} />
            </td>
            <td className="num">
              <span className={`pill ${availableClass(c)}`} data-testid={`available-${c.categoryId}`}>
                {formatMoney(c.available, currency)}
              </span>
            </td>
          </tr>
        ))}
    </>
  );
}

export function describeTarget(t: Target, currency: string): string {
  const amt = formatMoney(t.amount, currency);
  switch (t.type) {
    case 'MONTHLY_SAVINGS':
      return `Assign ${amt} every month`;
    case 'MONTHLY_SPENDING':
      return `Have ${amt} available each month`;
    case 'TARGET_BALANCE':
      return `Save up to ${amt}`;
    case 'TARGET_BALANCE_BY_DATE':
      return `Save ${amt} by ${t.date ? formatMonthLong(t.date.slice(0, 7)) : '—'}`;
  }
}

function targetProgress(c: CategoryMonth): number {
  const t = c.target;
  if (!t || t.amount <= 0) return 0;
  if (c.targetNeeded === 0) return 1;
  if (t.type === 'MONTHLY_SAVINGS' || t.type === 'TARGET_BALANCE_BY_DATE') {
    const need = c.assigned + c.targetNeeded;
    return need > 0 ? Math.max(0, c.assigned / need) : 1;
  }
  return Math.max(0, Math.min(1, (c.carryover + c.assigned) / t.amount));
}

function TargetHint({ c, currency }: { c: CategoryMonth; currency: string }) {
  const p = targetProgress(c);
  return (
    <span className="target-hint">
      <span className={`progress ${c.targetNeeded > 0 ? 'warn' : ''}`} aria-hidden="true">
        <span style={{ width: `${Math.round(p * 100)}%` }} />
      </span>
      {c.targetNeeded > 0 ? `${formatMoney(c.targetNeeded, currency)} more needed` : 'Funded'}
    </span>
  );
}

function InlineAdd({ placeholder, onSubmit, onCancel }: { placeholder: string; onSubmit: (name: string) => Promise<unknown>; onCancel: () => void }) {
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const submit = async () => {
    if (!name.trim()) return onCancel();
    setBusy(true);
    try {
      await onSubmit(name.trim());
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <form
      className="inline-add"
      onSubmit={(e) => {
        e.preventDefault();
        void submit();
      }}
    >
      <input
        className="input"
        autoFocus
        placeholder={placeholder}
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => e.key === 'Escape' && onCancel()}
        aria-label={placeholder}
      />
      <button className="btn btn-primary btn-sm" disabled={busy}>
        Add
      </button>
      <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>
        Cancel
      </button>
    </form>
  );
}

const TARGET_TYPES: { value: TargetType; label: string }[] = [
  { value: 'MONTHLY_SPENDING', label: 'Monthly spending — have X available each month' },
  { value: 'MONTHLY_SAVINGS', label: 'Monthly savings — assign X every month' },
  { value: 'TARGET_BALANCE', label: 'Target balance — save up to X' },
  { value: 'TARGET_BALANCE_BY_DATE', label: 'Target balance by date — X by a month' },
];

function CategoryPanel({
  cat,
  month,
  onClose,
  onMove,
}: {
  cat: CategoryMonth;
  month: string;
  onClose: () => void;
  onMove: (init: { from?: string | null; to?: string | null }) => void;
}) {
  const { budgetId, currency, categories } = useBudget();
  const toast = useToast();
  const meta = categories.flatMap((g) => g.categories).find((c) => c.id === cat.categoryId);
  const system = cat.systemKind !== null;
  const [name, setName] = useState(cat.name);
  const [note, setNote] = useState(meta?.note ?? '');
  const [deleting, setDeleting] = useState(false);
  const [editingTarget, setEditingTarget] = useState(false);

  const dirty = name.trim() !== cat.name || (note || '') !== (meta?.note ?? '');

  return (
    <aside className="side-panel" aria-label={`${cat.name} details`}>
      <div className="row">
        <h3 className="grow">{cat.name}</h3>
        <button className="btn btn-ghost btn-icon" onClick={onClose} aria-label="Close details">
          <Icon name="x" />
        </button>
      </div>
      <dl className="stat-list">
        <dt>Carried over from last month</dt>
        <dd><Money value={cat.carryover} currency={currency} /></dd>
        <dt>Assigned this month</dt>
        <dd><Money value={cat.assigned} currency={currency} /></dd>
        <dt>Activity</dt>
        <dd><Money value={cat.activity} currency={currency} /></dd>
        <dt>Available</dt>
        <dd><span className={`pill ${availableClass(cat)}`}>{formatMoney(cat.available, currency)}</span></dd>
      </dl>
      <div className="row-wrap">
        <button className="btn btn-sm" onClick={() => onMove(cat.available > 0 ? { from: cat.categoryId } : { to: cat.categoryId })}>
          <Icon name="swap" size={14} /> Move money
        </button>
        <Link className="btn btn-sm" to={`/budgets/${budgetId}/accounts?category=${cat.categoryId}`}>
          View transactions
        </Link>
      </div>
      <hr className="divider" />

      <div className="stack">
        <div className="row">
          <h4 className="grow">Target</h4>
          {cat.target && !editingTarget && (
            <button className="btn btn-ghost btn-sm" onClick={() => setEditingTarget(true)}>
              Edit
            </button>
          )}
        </div>
        {editingTarget || !cat.target ? (
          <TargetEditor cat={cat} month={month} onDone={() => setEditingTarget(false)} />
        ) : (
          <div className="stack" style={{ gap: 6 }}>
            <span className="small">{describeTarget(cat.target, currency)}</span>
            <TargetHint c={cat} currency={currency} />
          </div>
        )}
      </div>
      <hr className="divider" />

      {!system && (
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            // null would mean "unchanged" to the backend; "" clears the note.
            mutate('update_category', { budgetId, categoryId: cat.categoryId, name: name.trim(), note: note.trim() }).then(
              () => toast.info('Category saved'),
              toast.error,
            );
          }}
        >
          <Field label="Name">
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Note">
            <textarea className="textarea" rows={3} value={note} onChange={(e) => setNote(e.target.value)} placeholder="Anything to remember about this category" />
          </Field>
          <div className="row">
            <button className="btn btn-primary btn-sm" disabled={!dirty || !name.trim()}>
              Save
            </button>
            <span className="spacer" />
            <button
              type="button"
              className="btn btn-sm"
              onClick={() =>
                mutate('update_category', { budgetId, categoryId: cat.categoryId, hidden: !cat.hidden }).then(
                  () => toast.info(cat.hidden ? 'Category unhidden' : 'Category hidden'),
                  toast.error,
                )
              }
            >
              <Icon name="eye" size={14} /> {cat.hidden ? 'Unhide' : 'Hide'}
            </button>
            <button type="button" className="btn btn-danger btn-sm" onClick={() => setDeleting(true)}>
              <Icon name="trash" size={14} /> Delete
            </button>
          </div>
        </form>
      )}
      {system && <p className="small muted">This is a system category for credit card payments; it follows its account.</p>}
      {deleting && <DeleteCategoryDialog categoryId={cat.categoryId} name={cat.name} onClose={() => setDeleting(false)} onDeleted={onClose} />}
    </aside>
  );
}

function TargetEditor({ cat, month, onDone }: { cat: CategoryMonth; month: string; onDone: () => void }) {
  const { budgetId, currency } = useBudget();
  const toast = useToast();
  const [type, setType] = useState<TargetType>(cat.target?.type ?? 'MONTHLY_SPENDING');
  const [amount, setAmount] = useState(cat.target ? formatPlain(cat.target.amount, currency) : '');
  const [date, setDate] = useState(cat.target?.date?.slice(0, 7) ?? addMonths(month, 6));
  const parsed = parseMoney(amount);
  const valid = parsed !== null && parsed > 0;
  return (
    <form
      className="stack"
      onSubmit={(e) => {
        e.preventDefault();
        if (!valid) return;
        mutate('set_category_target', {
          budgetId,
          categoryId: cat.categoryId,
          type,
          amount: parsed,
          targetDate: type === 'TARGET_BALANCE_BY_DATE' ? `${date}-01` : null,
        }).then(() => {
          toast.info('Target saved');
          onDone();
        }, toast.error);
      }}
    >
      <Field label="Type">
        <select className="select" value={type} onChange={(e) => setType(e.target.value as TargetType)}>
          {TARGET_TYPES.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
      </Field>
      <div className="field-row">
        <Field label="Amount">
          <MoneyInput value={amount} onChange={setAmount} placeholder="0.00" />
        </Field>
        {type === 'TARGET_BALANCE_BY_DATE' && (
          <Field label="By month">
            <input className="input" type="month" value={date} onChange={(e) => setDate(e.target.value)} required />
          </Field>
        )}
      </div>
      <div className="row">
        <button className="btn btn-primary btn-sm" disabled={!valid}>
          {cat.target ? 'Update target' : 'Set target'}
        </button>
        {cat.target && (
          <>
            <button type="button" className="btn btn-ghost btn-sm" onClick={onDone}>
              Cancel
            </button>
            <span className="spacer" />
            <button
              type="button"
              className="btn btn-danger btn-sm"
              onClick={() =>
                mutate('clear_category_target', { budgetId, categoryId: cat.categoryId }).then(() => {
                  toast.info('Target removed');
                  onDone();
                }, toast.error)
              }
            >
              Remove target
            </button>
          </>
        )}
      </div>
    </form>
  );
}

function DeleteCategoryDialog({ categoryId, name, onClose, onDeleted }: { categoryId: string; name: string; onClose: () => void; onDeleted: () => void }) {
  const { budgetId, categories } = useBudget();
  const [to, setTo] = useState<string | null>(null);
  return (
    <FormModal
      title={`Delete “${name}”?`}
      submitLabel="Delete category"
      danger
      onClose={onClose}
      onSubmit={async () => {
        await mutate('delete_category', { budgetId, categoryId, reassignToCategoryId: to });
        onClose();
        onDeleted();
      }}
    >
      <p className="small">Transactions and assigned money in this category can be moved to another category. Otherwise they become uncategorized.</p>
      <Field label="Reassign to">
        <CategorySelect groups={categories} value={to} onChange={setTo} includeRta={false} emptyLabel="Nothing (leave uncategorized)" excludeId={categoryId} />
      </Field>
    </FormModal>
  );
}

function GroupDialog({ group, onClose }: { group: GroupMonth; onClose: () => void }) {
  const { budgetId, categories } = useBudget();
  const [name, setName] = useState(group.name);
  const [hidden, setHidden] = useState(group.hidden);
  const [deleting, setDeleting] = useState(false);
  const [to, setTo] = useState<string | null>(null);
  const ownIds = new Set(group.categories.map((c) => c.categoryId));
  const others = categories.map((g) => ({ ...g, categories: g.categories.filter((c) => !ownIds.has(c.id)) }));

  if (deleting) {
    return (
      <FormModal
        title={`Delete group “${group.name}”?`}
        submitLabel="Delete group"
        danger
        onClose={onClose}
        onSubmit={async () => {
          await mutate('delete_category_group', { budgetId, groupId: group.groupId, reassignToCategoryId: to });
          onClose();
        }}
      >
        <p className="small">
          This deletes the group and its {group.categories.length} categor{group.categories.length === 1 ? 'y' : 'ies'}.
        </p>
        <Field label="Move their transactions and assigned money to">
          <CategorySelect groups={others} value={to} onChange={setTo} includeRta={false} emptyLabel="Nothing (leave uncategorized)" />
        </Field>
      </FormModal>
    );
  }
  return (
    <FormModal
      title="Edit category group"
      onClose={onClose}
      disabled={!name.trim()}
      onSubmit={async () => {
        await mutate('update_category_group', { budgetId, groupId: group.groupId, name: name.trim(), hidden });
        onClose();
      }}
    >
      <Field label="Name">
        <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
      </Field>
      <label className="check">
        <input type="checkbox" checked={hidden} onChange={(e) => setHidden(e.target.checked)} /> Hide this group
      </label>
      <div>
        <button type="button" className="btn btn-danger btn-sm" onClick={() => setDeleting(true)}>
          <Icon name="trash" size={14} /> Delete group…
        </button>
      </div>
    </FormModal>
  );
}

function MoveMoneyDialog({
  month,
  data,
  initial,
  onClose,
}: {
  month: string;
  data: MonthBudget;
  initial: { from?: string | null; to?: string | null };
  onClose: () => void;
}) {
  const { budgetId, currency, categories } = useBudget();
  const rtaId = categories.find((g) => g.systemKind === 'INTERNAL')?.categories.find((c) => c.systemKind === 'READY_TO_ASSIGN')?.id ?? null;
  const [from, setFrom] = useState<string | null>(initial.from ?? rtaId);
  const [to, setTo] = useState<string | null>(initial.to ?? null);
  const fromCat = data.groups.flatMap((g) => g.categories).find((c) => c.categoryId === from);
  const toCat = data.groups.flatMap((g) => g.categories).find((c) => c.categoryId === to);
  const defaultAmount = fromCat ? Math.max(fromCat.available, 0) : toCat && toCat.available < 0 ? -toCat.available : 0;
  const [amount, setAmount] = useState(defaultAmount ? formatPlain(defaultAmount, currency) : '');
  const parsed = parseMoney(amount);
  const available = (id: string | null) => {
    if (id === rtaId) return data.readyToAssign;
    return data.groups.flatMap((g) => g.categories).find((c) => c.categoryId === id)?.available ?? 0;
  };
  const toApi = (id: string | null) => (id === rtaId ? null : id);
  const valid = parsed !== null && parsed > 0 && from !== to && from !== null && to !== null;

  return (
    <FormModal
      title={`Move money · ${formatMonthLong(month)}`}
      submitLabel="Move"
      onClose={onClose}
      disabled={!valid}
      onSubmit={async () => {
        await mutate('move_money', { budgetId, month, amount: parsed!, fromCategoryId: toApi(from), toCategoryId: toApi(to) });
        onClose();
      }}
    >
      <Field label="Amount">
        <MoneyInput value={amount} onChange={setAmount} placeholder="0.00" autoFocus />
      </Field>
      <Field label="From" hint={from ? `Available: ${formatMoney(available(from), currency)}` : undefined}>
        <CategorySelect groups={categories} value={from} onChange={setFrom} allowEmpty={false} />
      </Field>
      <Field label="To" hint={to ? `Available: ${formatMoney(available(to), currency)}` : undefined}>
        <CategorySelect groups={categories} value={to} onChange={setTo} emptyLabel="Choose a category…" />
      </Field>
    </FormModal>
  );
}
