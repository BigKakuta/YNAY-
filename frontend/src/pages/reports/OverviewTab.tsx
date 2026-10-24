import { useMemo } from 'react';
import { useQuery } from '../../api/query';
import type { CashFlow, Subscription } from '../../api/types';
import { useBudget } from '../../context';
import { currentMonth, formatMonthLong, formatMonthShort } from '../../lib/dates';
import { formatMoney } from '../../lib/money';
import { BarChart, HBars, Legend, LineChart, OTHER, SERIES, Sankey, compact, money0, type SankeyLink, type SankeyNode } from './charts';
import { Loading, Panel, Tile, periodMonths, pct, useReportSettings, type Period, type ResolvedSettings } from './common';
import { activeMonths, fullMonthCount, incomeBySource, monthSpan, savingByAccount, spendingByCategory } from './stats';

export function OverviewTab({ period }: { period: Period }) {
  const { budgetId } = useBudget();
  const range = { budgetId, fromMonth: period.from, toMonth: period.to };
  const flow = useQuery('report_cash_flow', range);
  const subs = useQuery('list_subscriptions', { budgetId });
  const netWorth = useQuery('report_net_worth', range);
  const { settings } = useReportSettings();

  return (
    <Loading q={flow}>
      {(cf) => (
        <>
          <Kpis cf={cf} subs={subs.data ?? []} period={period} />
          <div className="report-grid">
            <MonthByMonth cf={cf} />
            <Categories cf={cf} subs={subs.data ?? []} />
            <IncomeAndSaving cf={cf} />
            {settings && <MoneyFlow cf={cf} period={period} settings={settings} />}
            <Panel title="Net worth" wide aside={<span className="small muted">All accounts, including tracking accounts, at the end of each month</span>}>
              <Loading q={netWorth}>{(rows) => <NetWorth rows={rows} />}</Loading>
            </Panel>
          </div>
        </>
      )}
    </Loading>
  );
}

const plans = (subs: Subscription[]) => subs.filter((s) => s.kind === 'SUBSCRIPTION');

function Kpis({ cf, subs, period }: { cf: CashFlow; subs: Subscription[]; period: Period }) {
  const { currency } = useBudget();
  const span = monthSpan(activeMonths(periodMonths(period), cf.rows));
  const t = cf.months.reduce((a, m) => ({ income: a.income + m.income, spending: a.spending + m.spending, saving: a.saving + m.saving, subs: a.subs + m.subscriptions }), {
    income: 0,
    spending: 0,
    saving: 0,
    subs: 0,
  });
  const left = t.income - t.spending;
  const running = plans(subs).filter((s) => s.status !== 'ENDED').reduce((a, s) => a + s.monthlyCost, 0);
  const f = (v: number) => money0(v, currency);
  return (
    <div className="stat-tiles">
      <Tile label="Income" value={f(t.income)} sub={`${f(t.income / span)} a month on average`} />
      <Tile label="Spending" value={f(t.spending)} sub={`${f(t.spending / span)} a month on average`} />
      <Tile label="Left over" value={f(left)} sub={t.income > 0 ? `${pct(left / t.income)} of income kept` : 'no income in this period'} />
      <Tile label="Saved & invested" value={f(t.saving)} sub="net moves to investment and asset accounts" />
      <Tile label="Subscriptions" value={f(t.subs)} sub={`paid in this period · ${f(running)}/mo running now`} accent />
    </div>
  );
}

function MonthByMonth({ cf }: { cf: CashFlow }) {
  const { currency } = useBudget();
  const now = currentMonth();
  const months = cf.months;
  return (
    <Panel
      title="Month by month"
      wide
      aside={<Legend items={[{ label: 'Income', color: 'var(--series-1)' }, { label: 'Spending', color: 'var(--series-2)' }]} />}
    >
      <BarChart
        ariaLabel="Income and spending per month"
        labels={months.map((m) => formatMonthShort(m.month))}
        series={[
          { key: 'income', label: 'Income', color: 'var(--series-1)', values: months.map((m) => m.income) },
          { key: 'spending', label: 'Spending', color: 'var(--series-2)', values: months.map((m) => m.spending) },
        ]}
        faded={(i) => months[i]!.month === now}
        yFormat={(v) => compact(v, currency)}
        tip={(i) => {
          const m = months[i]!;
          return {
            title: `${formatMonthLong(m.month)}${m.month === now ? ' (so far)' : ''}`,
            rows: [
              ['Income', formatMoney(m.income, currency)],
              ['Spending', formatMoney(m.spending, currency)],
              ['Left over', formatMoney(m.income - m.spending, currency)],
              ['Saved & invested', formatMoney(m.saving, currency)],
            ],
          };
        }}
      />
      <p className="small muted">Saving (moves to investment and asset accounts) is kept out of spending. The running month is drawn lighter.</p>
    </Panel>
  );
}

function Categories({ cf, subs }: { cf: CashFlow; subs: Subscription[] }) {
  const { currency } = useBudget();
  const ids = useMemo(() => new Set(plans(subs).map((s) => s.id)), [subs]);
  const cats = spendingByCategory(cf.rows, ids);
  const total = cats.reduce((a, c) => a + c.value, 0);
  const f = (v: number) => money0(v, currency);
  return (
    <Panel
      title="Spending by category"
      wide
      aside={<Legend items={[{ label: 'Subscriptions', color: 'var(--series-sub)' }, { label: 'Everything else', color: 'var(--series-1)' }]} />}
    >
      {cats.length === 0 ? (
        <div className="empty">No spending in this period.</div>
      ) : (
        <HBars
          format={f}
          partLabel="subscriptions"
          rows={cats.map((c) => ({
            key: c.key,
            label: c.label,
            sub: c.groupName ?? undefined,
            value: c.value,
            part: c.subscriptions,
            share: pct(c.value / total),
            details: (
              <div className="kv">
                {c.payees.slice(0, 8).map((p) => (
                  <Pair key={p.key} k={p.label} v={f(p.value)} />
                ))}
                {c.subscriptions > 0 && <Pair k="of which subscriptions" v={f(c.subscriptions)} cls="sub-ink" />}
              </div>
            ),
          }))}
        />
      )}
      <span className="small muted">Open a category to see where it went.</span>
    </Panel>
  );
}

function Pair({ k, v, cls = '' }: { k: string; v: string; cls?: string }) {
  return (
    <>
      <span className={cls}>{k}</span>
      <span className={`num ${cls}`}>{v}</span>
    </>
  );
}

function IncomeAndSaving({ cf }: { cf: CashFlow }) {
  const { currency } = useBudget();
  const f = (v: number) => money0(v, currency);
  const income = incomeBySource(cf.rows);
  const saving = savingByAccount(cf.rows);
  const incomeTotal = income.reduce((a, x) => a + x.value, 0);
  const savingTotal = saving.reduce((a, x) => a + x.value, 0);
  return (
    <>
      <Panel title="Income">
        {income.length === 0 ? (
          <div className="empty">No income in this period.</div>
        ) : (
          <div className="kv">
            {income.map((x) => (
              <Pair key={x.key} k={x.label} v={f(x.value)} />
            ))}
            <Pair k="Total" v={f(incomeTotal)} cls="tot" />
          </div>
        )}
      </Panel>
      <Panel title="Saving and investing">
        {saving.length === 0 ? (
          <div className="empty">No transfers to investment or asset accounts in this period.</div>
        ) : (
          <div className="kv">
            {saving.map((x) => (
              <Pair key={x.key} k={x.value < 0 ? `Taken out of ${x.label}` : `Into ${x.label}`} v={f(x.value)} />
            ))}
            <Pair k={savingTotal >= 0 ? 'Net put away' : 'Net taken out'} v={f(Math.abs(savingTotal))} cls="tot" />
          </div>
        )}
        <p className="small muted">Transfers to tracking accounts of type Asset or Other count as saving. Transfers between budget accounts are left out.</p>
      </Panel>
    </>
  );
}

function MoneyFlow({ cf, period, settings }: { cf: CashFlow; period: Period; settings: ResolvedSettings }) {
  const { currency } = useBudget();
  const months = activeMonths(periodMonths(period), cf.rows);
  const full = fullMonthCount(months);
  const n = full || 1;
  const fullSet = new Set(full ? months.filter((m) => m < currentMonth()) : months);
  const rows = cf.rows.filter((r) => fullSet.has(r.month));
  const per = (v: number) => v / n;

  const income = incomeBySource(rows, 3);
  const totalIncome = per(income.reduce((a, x) => a + x.value, 0));
  const salary = income.find((x) => x.key === settings.salaryPayeeId) ?? income[0];
  const gross = settings.grossSalaryPerYear ? settings.grossSalaryPerYear / 12 : 0;

  const groups = new Map<string, number>();
  for (const r of rows) if (r.kind === 'SPENDING') groups.set(r.groupName ?? 'Uncategorized', (groups.get(r.groupName ?? 'Uncategorized') ?? 0) + r.amount);
  let spend = [...groups].filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]);
  if (spend.length > 7) spend = [...spend.slice(0, 6), ['Everything else', spend.slice(6).reduce((a, [, v]) => a + v, 0)]];
  const colorOf = new Map([...groups.keys()].sort().map((g, i) => [g, SERIES[i % SERIES.length]!]));
  const saving = per(rows.filter((r) => r.kind === 'SAVING').reduce((a, r) => a + r.amount, 0));
  const outflow = per(spend.reduce((a, [, v]) => a + v, 0)) + Math.max(0, saving);
  const left = totalIncome - outflow;

  const nodes: SankeyNode[] = [];
  const links: SankeyLink[] = [];
  const net: SankeyNode = { id: 'net', label: 'Net income', value: totalIncome + Math.max(0, -left), color: 'var(--series-1)', column: 1 };
  if (gross > 0 && salary) {
    const salaryNet = per(salary.value);
    const tax = Math.max(0, gross - salaryNet);
    nodes.push({ id: 'gross', label: 'Gross salary', value: gross, color: 'var(--series-1)', column: 0 });
    nodes.push({ id: 'tax', label: 'Tax & contributions', value: tax, color: OTHER, column: 1, note: 'gross − net pay' });
    links.push({ from: 'gross', to: 'tax', value: tax, color: OTHER }, { from: 'gross', to: 'net', value: Math.min(salaryNet, gross), color: 'var(--series-1)' });
    for (const x of income.filter((i) => i !== salary)) {
      nodes.push({ id: `in-${x.key}`, label: x.label, value: per(x.value), color: 'var(--series-1)', column: 0 });
      links.push({ from: `in-${x.key}`, to: 'net', value: per(x.value), color: 'var(--series-1)' });
    }
  } else {
    for (const x of income) {
      nodes.push({ id: `in-${x.key}`, label: x.label, value: per(x.value), color: 'var(--series-1)', column: 0 });
      links.push({ from: `in-${x.key}`, to: 'net', value: per(x.value), color: 'var(--series-1)' });
    }
  }
  if (left < 0) {
    nodes.push({ id: 'short', label: 'From balances', value: -left, color: 'var(--neg-strong)', column: 0 });
    links.push({ from: 'short', to: 'net', value: -left, color: 'var(--neg-strong)' });
  }
  nodes.push(net);
  for (const [g, v] of spend) {
    nodes.push({ id: `g-${g}`, label: g, value: per(v), color: colorOf.get(g) ?? OTHER, column: 2 });
    links.push({ from: 'net', to: `g-${g}`, value: per(v), color: colorOf.get(g) ?? OTHER });
  }
  if (saving > 0) {
    nodes.push({ id: 'saving', label: 'Saved & invested', value: saving, color: 'var(--series-sub)', column: 2 });
    links.push({ from: 'net', to: 'saving', value: saving, color: 'var(--series-sub)' });
  }
  if (left > 0) {
    nodes.push({ id: 'left', label: 'Left over', value: left, color: 'var(--pos-strong)', column: 2 });
    links.push({ from: 'net', to: 'left', value: left, color: 'var(--pos-strong)' });
  }

  return (
    <Panel
      title="Money flow, per month"
      wide
      aside={<span className="small muted">Average of {full ? `${full} full month${full === 1 ? '' : 's'}` : 'the period so far'}</span>}
    >
      {totalIncome <= 0 && outflow <= 0 ? (
        <div className="empty">No money moved in this period.</div>
      ) : (
        <div className="table-wrap">
          <Sankey
            ariaLabel="Average monthly money flow"
            nodes={nodes}
            links={links}
            format={(nd) => {
              const base = nd.column === 2 ? totalIncome : nd.column === 1 && gross ? gross : 0;
              return `${money0(nd.value, currency)}${base > 0 ? ` · ${pct(nd.value / base)}` : ''}`;
            }}
          />
        </div>
      )}
      <p className="small muted">
        {gross > 0
          ? `Gross salary comes from the report settings (${money0(settings.grossSalaryPerYear!, currency)} a year); tax and contributions are gross minus the net pay from ${salary?.label ?? 'the salary payee'}.`
          : 'Set a gross salary in the Planning tab to also see tax and contributions.'}{' '}
        Spending is grouped by category group.
      </p>
    </Panel>
  );
}

function NetWorth({ rows }: { rows: { month: string; assets: number; liabilities: number; netWorth: number }[] }) {
  const { currency } = useBudget();
  if (rows.length === 0) return <div className="empty">No data.</div>;
  const every = Math.ceil(rows.length / 10);
  return (
    <LineChart
      ariaLabel="Net worth by month"
      count={rows.length}
      series={[{ key: 'nw', label: 'Net worth', color: 'var(--series-1)', values: rows.map((r) => r.netWorth), area: true }]}
      yFormat={(v) => compact(v, currency)}
      xLabel={(i) => (i % every === 0 ? formatMonthShort(rows[i]!.month) : null)}
      tip={(i) => ({
        title: formatMonthLong(rows[i]!.month),
        rows: [
          ['Assets', formatMoney(rows[i]!.assets, currency)],
          ['Liabilities', formatMoney(Math.abs(rows[i]!.liabilities), currency)],
          ['Net worth', formatMoney(rows[i]!.netWorth, currency)],
        ],
      })}
    />
  );
}
