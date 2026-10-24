// Shared pieces of the Reports tabs: the period, report settings with defaults, pickers and tiles.
import { useMemo, type ReactNode } from 'react';
import { mutate, useQuery } from '../../api/query';
import type { CategoryGroup, ReportSettings } from '../../api/types';
import { ErrorBox, Spinner } from '../../components/ui';
import { useBudget } from '../../context';
import { addMonths, currentMonth, formatMonthLong, monthRange } from '../../lib/dates';

export type Period = { from: string; to: string };
export type Preset = 'l12' | '1' | '3' | '6' | '12' | 'ytd' | 'ly';

export const PRESETS: { value: Preset; label: string }[] = [
  { value: 'l12', label: 'Last 12 full months' },
  { value: '1', label: 'This month' },
  { value: '3', label: '3 months' },
  { value: '6', label: '6 months' },
  { value: 'ytd', label: 'Year to date' },
  { value: 'ly', label: 'Last year' },
];

export function presetRange(p: Preset, now = currentMonth()): Period {
  if (p === 'l12') return { from: addMonths(now, -12), to: addMonths(now, -1) };
  if (p === 'ytd') return { from: `${now.slice(0, 4)}-01`, to: now };
  if (p === 'ly') {
    const y = Number(now.slice(0, 4)) - 1;
    return { from: `${y}-01`, to: `${y}-12` };
  }
  return { from: addMonths(now, -(Number(p) - 1)), to: now };
}

export const periodMonths = (p: Period) => monthRange(p.from, p.to);
export const describePeriod = (p: Period) => (p.from === p.to ? formatMonthLong(p.from) : `${formatMonthLong(p.from)} – ${formatMonthLong(p.to)}`);

/** Spending categories (no Ready to Assign, no credit card payments), grouped for pickers. */
export function spendingGroups(groups: CategoryGroup[]) {
  return groups
    .filter((g) => g.systemKind === null)
    .map((g) => ({ ...g, categories: g.categories.filter((c) => c.systemKind === null) }))
    .filter((g) => g.categories.length > 0);
}

const pick = (groups: CategoryGroup[], re: RegExp) =>
  spendingGroups(groups)
    .flatMap((g) => g.categories)
    .filter((c) => re.test(c.name))
    .map((c) => c.id);

/** The stored report settings with defaults filled in, plus a saver that merges a patch. */
export function useReportSettings() {
  const { budgetId, categories } = useBudget();
  const q = useQuery('get_report_settings', { budgetId });
  const value = useMemo(() => {
    const s = q.data;
    if (!s) return null;
    return {
      raw: s,
      thingsCategoryIds: s.thingsCategoryIds ?? pick(categories, /shop|cloth|electr|furnit|gadget|household|home goods|tech/i),
      thingsMonthlyTarget: s.thingsMonthlyTarget,
      focusCategoryIds: s.focusCategoryIds ?? pick(categories, /grocer|supermarket|food/i).slice(0, 2),
      focusPayeeId: s.focusPayeeId,
      reviewMinAmount: s.reviewMinAmount ?? 20_000,
      grossSalaryPerYear: s.grossSalaryPerYear,
      salaryPayeeId: s.salaryPayeeId,
    };
  }, [q.data, categories]);
  const save = (patch: Partial<ReportSettings>) =>
    mutate('update_report_settings', { budgetId, settings: { ...(q.data as ReportSettings), ...patch } });
  return { settings: value, error: q.error, reload: q.reload, save };
}
export type ResolvedSettings = NonNullable<ReturnType<typeof useReportSettings>['settings']>;

export function Loading<T>({ q, children }: { q: { data: T | undefined; error: Error | undefined; reload: () => void }; children: (d: T) => ReactNode }) {
  if (q.error && !q.data) return <ErrorBox error={q.error} onRetry={q.reload} />;
  if (!q.data) return <div className="empty"><Spinner /></div>;
  return <>{children(q.data)}</>;
}

export function Tile({ label, value, sub, accent }: { label: string; value: ReactNode; sub?: ReactNode; accent?: boolean }) {
  return (
    <div className={`card stat-tile${accent ? ' stat-accent' : ''}`}>
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  );
}

export function Panel({ title, aside, children, wide, id }: { title: ReactNode; aside?: ReactNode; children: ReactNode; wide?: boolean; id?: string }) {
  return (
    <section className={`card${wide ? ' wide' : ''}`} aria-labelledby={id}>
      <div className="card-header">
        <h3 className="grow" id={id}>
          {title}
        </h3>
        {aside}
      </div>
      <div className="card-body stack">{children}</div>
    </section>
  );
}

/** Multi-select of spending categories in a dropdown. */
export function CategoryPicker({ value, onChange, label }: { value: string[]; onChange: (ids: string[]) => void; label: string }) {
  const { categories } = useBudget();
  const groups = spendingGroups(categories);
  const names = groups.flatMap((g) => g.categories).filter((c) => value.includes(c.id)).map((c) => c.name);
  return (
    <details className="picker">
      <summary className="btn btn-sm" aria-label={label}>
        {names.length ? (names.length > 3 ? `${names.slice(0, 3).join(', ')} +${names.length - 3}` : names.join(', ')) : 'Choose categories'} ▾
      </summary>
      <div className="picker-menu card" role="group" aria-label={label}>
        {groups.map((g) => (
          <div key={g.id} className="picker-group">
            <div className="picker-group-name">{g.name}</div>
            {g.categories.map((c) => (
              <label key={c.id} className="check">
                <input
                  type="checkbox"
                  checked={value.includes(c.id)}
                  onChange={(e) => onChange(e.target.checked ? [...value, c.id] : value.filter((x) => x !== c.id))}
                />
                {c.name}
              </label>
            ))}
          </div>
        ))}
      </div>
    </details>
  );
}

export function PayeeSelect({ value, onChange, label }: { value: string | null; onChange: (id: string | null) => void; label: string }) {
  const { budgetId } = useBudget();
  const payees = useQuery('list_payees', { budgetId });
  return (
    <select className="select input-sm" aria-label={label} value={value ?? ''} onChange={(e) => onChange(e.target.value || null)} style={{ width: 220 }}>
      <option value="">Choose a payee…</option>
      {(payees.data ?? [])
        .filter((p) => !p.transferAccountId && p.name !== 'Starting Balance')
        .map((p) => (
          <option key={p.id} value={p.id}>
            {p.name}
          </option>
        ))}
    </select>
  );
}

export const pct = (x: number) => `${Math.round(x * 100)}%`;

/** Read/write a JSON value in localStorage, ignoring storage failures. */
export function loadLocal<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? { ...fallback, ...(JSON.parse(raw) as T) } : fallback;
  } catch {
    return fallback;
  }
}
export function saveLocal(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // private mode or quota: the setting just isn't remembered
  }
}
