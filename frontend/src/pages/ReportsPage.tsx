import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery } from '../api/query';
import type { IncomeExpenseRow, NetWorthRow, SpendingRow } from '../api/types';
import { ErrorBox, Money, Spinner } from '../components/ui';
import { useBudget } from '../context';
import { addMonths, currentMonth, formatMonthLong, formatMonthShort, monthRange } from '../lib/dates';
import { formatMoney } from '../lib/money';

type Preset = '1' | '3' | '6' | '12' | 'ytd';
const PRESETS: { value: Preset; label: string }[] = [
  { value: '1', label: 'This month' },
  { value: '3', label: '3 months' },
  { value: '6', label: '6 months' },
  { value: '12', label: '12 months' },
  { value: 'ytd', label: 'Year to date' },
];

function presetRange(p: Preset): [string, string] {
  const to = currentMonth();
  if (p === 'ytd') return [`${to.slice(0, 4)}-01`, to];
  return [addMonths(to, -(Number(p) - 1)), to];
}

export function ReportsPage() {
  const { budgetId, currency } = useBudget();
  const [preset, setPreset] = useState<Preset | null>('6');
  const [range, setRange] = useState<[string, string]>(presetRange('6'));
  const [from, to] = range;
  const months = useMemo(() => monthRange(addMonths(currentMonth(), -35), addMonths(currentMonth(), 12)).reverse(), []);

  const spending = useQuery('report_spending_by_category', { budgetId, fromMonth: from, toMonth: to });
  const incomeExpense = useQuery('report_income_vs_expense', { budgetId, fromMonth: from, toMonth: to });
  const netWorth = useQuery('report_net_worth', { budgetId, fromMonth: from, toMonth: to });

  const totals = useMemo(() => {
    const rows = incomeExpense.data ?? [];
    return {
      income: rows.reduce((a, r) => a + r.income, 0),
      expense: rows.reduce((a, r) => a + Math.abs(r.expense), 0),
      net: rows.reduce((a, r) => a + r.net, 0),
    };
  }, [incomeExpense.data]);
  const lastNetWorth = netWorth.data?.at(-1);

  return (
    <>
      <header className="page-header">
        <h1>Reports</h1>
        <span className="spacer" />
        <div className="segmented" role="group" aria-label="Date range presets">
          {PRESETS.map((p) => (
            <button
              key={p.value}
              aria-pressed={preset === p.value}
              onClick={() => {
                setPreset(p.value);
                setRange(presetRange(p.value));
              }}
            >
              {p.label}
            </button>
          ))}
        </div>
        <div className="row small">
          <select
            className="select input-sm"
            aria-label="From month"
            value={from}
            onChange={(e) => {
              setPreset(null);
              setRange([e.target.value, e.target.value > to ? e.target.value : to]);
            }}
            style={{ width: 130 }}
          >
            {months.map((m) => (
              <option key={m} value={m}>
                {formatMonthShort(m)}
              </option>
            ))}
          </select>
          <span className="muted">to</span>
          <select
            className="select input-sm"
            aria-label="To month"
            value={to}
            onChange={(e) => {
              setPreset(null);
              setRange([e.target.value < from ? e.target.value : from, e.target.value]);
            }}
            style={{ width: 130 }}
          >
            {months.map((m) => (
              <option key={m} value={m}>
                {formatMonthShort(m)}
              </option>
            ))}
          </select>
        </div>
      </header>
      <div className="page-body page-scroll">
        <div className="stat-tiles">
          <StatTile label="Income" value={totals.income} currency={currency} />
          <StatTile label="Spending" value={totals.expense} currency={currency} />
          <StatTile label="Net income" value={totals.net} currency={currency} signed />
          <StatTile label={`Net worth${lastNetWorth ? ` · ${formatMonthShort(lastNetWorth.month)}` : ''}`} value={lastNetWorth?.netWorth ?? 0} currency={currency} signed />
        </div>
        <div className="report-grid">
          <section className="card">
            <div className="card-header">
              <h3 className="grow">Spending by category</h3>
              {spending.data && <span className="small muted">Total {formatMoney(Math.abs(spending.data.total), currency)}</span>}
            </div>
            <div className="card-body">
              <ReportState q={spending}>{(d) => <SpendingBars rows={d.rows} currency={currency} />}</ReportState>
            </div>
          </section>
          <section className="card">
            <div className="card-header">
              <h3 className="grow">Income vs. expense</h3>
              <div className="legend">
                <span className="key"><span className="swatch" style={{ background: 'var(--series-1)' }} /> Income</span>
                <span className="key"><span className="swatch" style={{ background: 'var(--series-2)' }} /> Expense</span>
              </div>
            </div>
            <div className="card-body">
              <ReportState q={incomeExpense}>{(d) => <IncomeExpenseChart rows={d} currency={currency} />}</ReportState>
            </div>
          </section>
          <section className="card wide">
            <div className="card-header">
              <h3 className="grow">Net worth</h3>
              <span className="small muted">All accounts, including tracking accounts, at the end of each month</span>
            </div>
            <div className="card-body">
              <ReportState q={netWorth}>{(d) => <NetWorthChart rows={d} currency={currency} />}</ReportState>
            </div>
          </section>
        </div>
      </div>
    </>
  );
}

function StatTile({ label, value, currency, signed }: { label: string; value: number; currency: string; signed?: boolean }) {
  return (
    <div className="card stat-tile">
      <div className="label">{label}</div>
      <div className="value">
        <Money value={value} currency={currency} signColor={!!signed} />
      </div>
    </div>
  );
}

function ReportState<T>({ q, children }: { q: { data: T | undefined; error: Error | undefined; reload: () => void }; children: (d: T) => ReactNode }) {
  if (q.error && !q.data) return <ErrorBox error={q.error} onRetry={q.reload} />;
  if (!q.data) return <div className="empty"><Spinner /></div>;
  return <>{children(q.data)}</>;
}

function SpendingBars({ rows, currency }: { rows: SpendingRow[]; currency: string }) {
  const spend = rows.filter((r) => r.total < 0).map((r) => ({ ...r, value: -r.total })).sort((a, b) => b.value - a.value);
  if (spend.length === 0) return <div className="empty">No spending in this period.</div>;
  const max = spend[0]!.value;
  return (
    <div className="hbar-list" role="list">
      {spend.map((r) => (
        <div className="hbar" key={r.categoryId} role="listitem" title={`${r.groupName} › ${r.categoryName}: ${formatMoney(r.value, currency)}`}>
          <span className="label">
            {r.categoryName}
            <small>{r.groupName}</small>
          </span>
          <span className="track">
            <span className="fill" style={{ width: `${(r.value / max) * 100}%` }} />
          </span>
          <span className="value">{formatMoney(r.value, currency)}</span>
        </div>
      ))}
    </div>
  );
}

function useWidth<T extends HTMLElement>(fallback = 640) {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(fallback);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    setWidth(el.clientWidth || fallback);
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(([e]) => e && setWidth(Math.max(240, e.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, [fallback]);
  return [ref, width] as const;
}

/** "Nice" axis ticks from 0 (or a negative minimum) to max. */
function ticks(min: number, max: number, count = 4): number[] {
  const span = max - min || 1;
  const raw = span / count;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? raw;
  const out: number[] = [];
  for (let v = Math.floor(min / step) * step; v <= max + step * 0.001; v += step) out.push(v);
  if (out[out.length - 1]! < max) out.push(out[out.length - 1]! + step);
  return out;
}

function compact(milliunits: number, currency: string) {
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 }).format(milliunits / 1000);
  } catch {
    return String(Math.round(milliunits / 1000));
  }
}

type Tip = { x: number; y: number; title: string; rows: [string, string][] } | null;

function Tooltip({ tip }: { tip: Tip }) {
  if (!tip) return null;
  return (
    <div className="tooltip" style={{ left: tip.x, top: tip.y }}>
      <div className="tt-title">{tip.title}</div>
      {tip.rows.map(([k, v]) => (
        <div className="tt-row" key={k}>
          <span>{k}</span>
          <b>{v}</b>
        </div>
      ))}
    </div>
  );
}

const PAD = { top: 12, right: 12, bottom: 26, left: 56 };

function IncomeExpenseChart({ rows, currency }: { rows: IncomeExpenseRow[]; currency: string }) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [tip, setTip] = useState<Tip>(null);
  const height = 240;
  const data = rows.map((r) => ({ ...r, exp: Math.abs(r.expense) }));
  const max = Math.max(1, ...data.map((r) => Math.max(r.income, r.exp)));
  const yt = ticks(0, max);
  const top = yt[yt.length - 1]!;
  const innerW = width - PAD.left - PAD.right;
  const innerH = height - PAD.top - PAD.bottom;
  const band = innerW / Math.max(data.length, 1);
  const barW = Math.min(24, (band - 10) / 2);
  const y = (v: number) => PAD.top + innerH - (v / top) * innerH;
  const labelEvery = Math.ceil(data.length / Math.max(1, Math.floor(innerW / 56)));

  return (
    <div className="chart-wrap" ref={ref} onMouseLeave={() => setTip(null)}>
      <svg className="chart" width={width} height={height} role="img" aria-label="Income and expense per month">
        {yt.map((v) => (
          <g key={v}>
            <line className="gridline" x1={PAD.left} x2={width - PAD.right} y1={y(v)} y2={y(v)} />
            <text x={PAD.left - 8} y={y(v) + 4} textAnchor="end">
              {compact(v, currency)}
            </text>
          </g>
        ))}
        {data.map((r, i) => {
          const cx = PAD.left + band * i + band / 2;
          return (
            <g key={r.month}>
              <rect
                x={cx - band / 2}
                y={PAD.top}
                width={band}
                height={innerH}
                fill="transparent"
                onMouseEnter={() =>
                  setTip({
                    x: cx,
                    y: y(Math.max(r.income, r.exp)),
                    title: formatMonthLong(r.month),
                    rows: [
                      ['Income', formatMoney(r.income, currency)],
                      ['Expense', formatMoney(r.exp, currency)],
                      ['Net', formatMoney(r.net, currency)],
                    ],
                  })
                }
              />
              <Column x={cx - barW - 1} w={barW} y0={y(0)} y1={y(r.income)} fill="var(--series-1)" />
              <Column x={cx + 1} w={barW} y0={y(0)} y1={y(r.exp)} fill="var(--series-2)" />
              {i % labelEvery === 0 && (
                <text x={cx} y={height - 8} textAnchor="middle">
                  {formatMonthShort(r.month)}
                </text>
              )}
            </g>
          );
        })}
        <line className="axis" x1={PAD.left} x2={width - PAD.right} y1={y(0)} y2={y(0)} />
      </svg>
      <Tooltip tip={tip} />
    </div>
  );
}

/** A column with a 4px rounded data-end and a square baseline. */
function Column({ x, w, y0, y1, fill }: { x: number; w: number; y0: number; y1: number; fill: string }) {
  const h = y0 - y1;
  if (h <= 0) return null;
  const r = Math.min(4, h, w / 2);
  const d = `M${x},${y0} V${y1 + r} Q${x},${y1} ${x + r},${y1} H${x + w - r} Q${x + w},${y1} ${x + w},${y1 + r} V${y0} Z`;
  return <path d={d} fill={fill} pointerEvents="none" />;
}

function NetWorthChart({ rows, currency }: { rows: NetWorthRow[]; currency: string }) {
  const [ref, width] = useWidth<HTMLDivElement>(900);
  const [hover, setHover] = useState<number | null>(null);
  const height = 260;
  if (rows.length === 0) return <div className="empty">No data.</div>;
  const values = rows.map((r) => r.netWorth);
  const yt = ticks(Math.min(0, ...values), Math.max(1, ...values));
  const lo = yt[0]!;
  const hi = yt[yt.length - 1]!;
  const innerW = width - PAD.left - PAD.right - 70;
  const innerH = height - PAD.top - PAD.bottom;
  const x = (i: number) => PAD.left + (rows.length === 1 ? innerW / 2 : (innerW * i) / (rows.length - 1));
  const y = (v: number) => PAD.top + innerH - ((v - lo) / (hi - lo || 1)) * innerH;
  const line = rows.map((r, i) => `${i ? 'L' : 'M'}${x(i)},${y(r.netWorth)}`).join(' ');
  const area = `${line} L${x(rows.length - 1)},${y(Math.max(lo, 0))} L${x(0)},${y(Math.max(lo, 0))} Z`;
  const last = rows[rows.length - 1]!;
  const labelEvery = Math.ceil(rows.length / Math.max(1, Math.floor(innerW / 64)));
  const h = hover !== null ? rows[hover] : undefined;

  return (
    <div
      className="chart-wrap"
      ref={ref}
      onMouseLeave={() => setHover(null)}
      onMouseMove={(e) => {
        const rect = e.currentTarget.getBoundingClientRect();
        const px = e.clientX - rect.left;
        const i = rows.length === 1 ? 0 : Math.round(((px - PAD.left) / innerW) * (rows.length - 1));
        setHover(Math.max(0, Math.min(rows.length - 1, i)));
      }}
    >
      <svg className="chart" width={width} height={height} role="img" aria-label="Net worth by month">
        {yt.map((v) => (
          <g key={v}>
            <line className="gridline" x1={PAD.left} x2={PAD.left + innerW} y1={y(v)} y2={y(v)} />
            <text x={PAD.left - 8} y={y(v) + 4} textAnchor="end">
              {compact(v, currency)}
            </text>
          </g>
        ))}
        {rows.map((r, i) =>
          i % labelEvery === 0 ? (
            <text key={r.month} x={x(i)} y={height - 8} textAnchor="middle">
              {formatMonthShort(r.month)}
            </text>
          ) : null,
        )}
        <path d={area} fill="var(--series-1)" opacity={0.1} />
        <path d={line} fill="none" stroke="var(--series-1)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        {hover !== null && <line className="axis" x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + innerH} />}
        {(hover !== null ? [hover] : [rows.length - 1]).map((i) => (
          <circle key={i} cx={x(i)} cy={y(rows[i]!.netWorth)} r={5} fill="var(--series-1)" stroke="var(--surface)" strokeWidth={2} />
        ))}
        <text x={x(rows.length - 1) + 10} y={y(last.netWorth) + 4} style={{ fill: 'var(--text)', fontWeight: 600 }}>
          {compact(last.netWorth, currency)}
        </text>
      </svg>
      {h && (
        <Tooltip
          tip={{
            x: x(hover!),
            y: y(h.netWorth),
            title: formatMonthLong(h.month),
            rows: [
              ['Assets', formatMoney(h.assets, currency)],
              ['Liabilities', formatMoney(Math.abs(h.liabilities), currency)],
              ['Net worth', formatMoney(h.netWorth, currency)],
            ],
          }}
        />
      )}
    </div>
  );
}
