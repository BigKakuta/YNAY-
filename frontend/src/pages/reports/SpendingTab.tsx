import { useQuery } from '../../api/query';
import { useBudget } from '../../context';
import { currentMonth, formatDate, formatMonthLong, formatMonthShort } from '../../lib/dates';
import { formatMoney } from '../../lib/money';
import { BarChart, Legend, OTHER, SERIES, compact, money0 } from './charts';
import { CategoryPicker, Loading, Panel, Tile, describePeriod, pct, periodMonths, useReportSettings, type Period } from './common';
import { bucketLabels, deepDive } from './stats';

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function SpendingTab({ period }: { period: Period }) {
  const { budgetId, currency } = useBudget();
  const { settings, save } = useReportSettings();
  const ids = settings?.focusCategoryIds ?? [];
  const payments = useQuery(
    'report_payments',
    settings && ids.length ? { budgetId, fromMonth: period.from, toMonth: period.to, categoryIds: ids, limit: 10000 } : null,
  );
  const picker = <CategoryPicker label="Deep-dive categories" value={ids} onChange={(v) => void save({ focusCategoryIds: v })} />;
  if (!settings) return null;
  if (!ids.length)
    return (
      <Panel title="Spending deep dive" aside={picker}>
        <div className="empty">Choose one or more categories (for example groceries) to see shop by shop where the money goes.</div>
      </Panel>
    );

  return (
    <Loading q={payments}>
      {({ payments: list, truncated }) => {
        const months = periodMonths(period);
        const d = deepDive(list, months);
        const now = currentMonth();
        const f = (v: number) => money0(v, currency);
        const colors = d.byPayee.map((s, i) => (s.label === 'Other' ? OTHER : SERIES[i % SERIES.length]!));
        const series = d.byPayee.map((s, i) => ({ key: s.label, label: s.label, color: colors[i]!, values: s.values }));
        const typeColor = (i: number, label: string) => (label === 'Other' ? OTHER : SERIES[i % SERIES.length]!);
        const buckets = bucketLabels((v) => String(v / 1000));
        return (
          <>
            <div className="row-wrap">
              <span className="small muted grow">
                {d.count} payments · {describePeriod(period)}
                {truncated && ' · showing the newest 10,000 only'}
              </span>
              {picker}
            </div>
            <div className="stat-tiles">
              <Tile label="Per month" value={f(d.perMonth)} sub={`${d.count} payments, ${f(d.total)} in total`} accent />
              <Tile label="Payments per week" value={d.paymentsPerWeek.toFixed(1)} sub={`on ${pct(d.dayShare)} of all days · ${d.multiDays} days with two or more`} />
              <Tile label="Longest daily run" value={`${d.longestRun} days`} sub="a payment every day in a row (Sundays don't break it)" />
              <Tile
                label="Typical payment"
                value={formatMoney(d.medianPayment, currency)}
                sub={`Saturday ${f(d.saturdayAvg)}, weekdays ${f(d.weekdayAvg)} on average`}
              />
            </div>

            <Panel title="Per month, by payee" wide aside={<Legend items={series.map((s) => ({ label: s.label, color: s.color }))} />}>
              <BarChart
                ariaLabel="Spending per month by payee"
                stacked
                labels={months.map(formatMonthShort)}
                series={series}
                faded={(i) => months[i] === now}
                yFormat={(v) => compact(v, currency)}
                height={260}
                tip={(i) => ({
                  title: `${formatMonthLong(months[i]!)}${months[i] === now ? ' (so far)' : ''}: ${f(series.reduce((a, s) => a + Math.max(0, s.values[i]!), 0))}`,
                  rows: series.filter((s) => s.values[i]! > 0).map((s) => [s.label, formatMoney(s.values[i]!, currency)] as [string, string]),
                  note: `${d.monthCount[i]} payments`,
                })}
              />
            </Panel>

            <Panel
              title="Payments per week"
              wide
              aside={<Legend items={[{ label: 'Payments that week', color: 'var(--series-1)' }, { label: '4-week average', color: 'var(--series-2)' }]} />}
            >
              <BarChart
                ariaLabel="Payments per week with a 4-week average"
                labels={d.weeks.map((w) => w.start)}
                series={[{ key: 'n', label: 'Payments', color: 'var(--series-1)', values: d.weeks.map((w) => w.count) }]}
                line={{ values: d.weeks.map((w) => w.avg4), color: 'var(--series-2)' }}
                faded={(i) => d.lastWeekPartial && i === d.weeks.length - 1}
                yFormat={(v) => String(v)}
                xLabel={(i) => {
                  const w = d.weeks[i]!.start;
                  const prev = d.weeks[i - 1]?.start;
                  return !prev || prev.slice(5, 7) !== w.slice(5, 7) ? (d.weeks.length > 30 && Number(w.slice(5, 7)) % 3 !== 1 ? null : formatMonthShort(w.slice(0, 7))) : null;
                }}
                tip={(i) => {
                  const w = d.weeks[i]!;
                  return {
                    title: `Week of ${formatDate(w.start)}${d.lastWeekPartial && i === d.weeks.length - 1 ? ' (so far)' : ''}`,
                    rows: [
                      ['Payments', String(w.count)],
                      ['Spent', formatMoney(w.amount, currency)],
                      ...(w.avg4 !== null ? ([['4-week average', w.avg4.toFixed(1)]] as [string, string][]) : []),
                    ],
                  };
                }}
              />
            </Panel>

            <Panel title="What time you pay" aside={<span className="small muted">{pct(d.timeCoverage)} of payments have a time</span>}>
              {d.hours.length ? (
                <BarChart
                  ariaLabel="Payments by hour of day"
                  labels={d.hours.map((h) => `${h.hour}:00`)}
                  series={[{ key: 'h', label: 'Payments', color: 'var(--series-1)', values: d.hours.map((h) => h.count) }]}
                  yFormat={(v) => String(v)}
                  xLabel={(i) => (d.hours[i]!.hour % 3 === 0 ? `${d.hours[i]!.hour}:00` : null)}
                  height={200}
                  tip={(i) => ({ title: `${d.hours[i]!.hour}:00–${d.hours[i]!.hour + 1}:00`, rows: [['Payments', String(d.hours[i]!.count)]] })}
                />
              ) : (
                <div className="empty">No payment times recorded. Imports with a time of day (card statements) fill this in.</div>
              )}
            </Panel>

            <Panel title="Which day, and how much per payment">
              <BarChart
                ariaLabel="Payments by weekday"
                labels={DAYS}
                series={[{ key: 'd', label: 'Payments', color: 'var(--series-1)', values: d.weekdays.map((w) => w.count) }]}
                yFormat={(v) => String(v)}
                xLabel={(i) => DAYS[i]!}
                height={200}
                tip={(i) => ({
                  title: DAYS[i]!,
                  rows: [
                    ['Payments', String(d.weekdays[i]!.count)],
                    ['Average', formatMoney(d.weekdays[i]!.avg, currency)],
                  ],
                })}
              />
            </Panel>

            <Panel title="Payment sizes" wide aside={<Legend items={d.sizeTypes.map((t, i) => ({ label: t, color: typeColor(i, t) }))} />}>
              <p className="small muted">
                {pct(d.smallShareCount)} of payments are under {formatMoney(10_000, currency)}, but they add up to only {pct(d.smallShareSum)} of the money. The{' '}
                {pct(d.bigShareCount)} of payments of {formatMoney(20_000, currency)} or more make up {pct(d.bigShareSum)}. The middle payment is{' '}
                {formatMoney(d.medianPayment, currency)}.
              </p>
              <div className="report-grid">
                {(['count', 'sum'] as const).map((key) => {
                  const data = key === 'count' ? d.sizeCount : d.sizeSum;
                  const fmt = key === 'count' ? (v: number) => `${v}` : (v: number) => compact(v, currency);
                  return (
                    <div key={key} className="stack-tight">
                      <span className="eyebrow">{key === 'count' ? 'Number of payments' : 'Money spent'}</span>
                      <BarChart
                        ariaLabel={key === 'count' ? 'Payments by size' : 'Money by payment size'}
                        stacked
                        labels={buckets}
                        xLabel={(i) => buckets[i]!}
                        series={d.sizeTypes.map((t, i) => ({ key: t, label: t, color: typeColor(i, t), values: data[i]! }))}
                        yFormat={fmt}
                        height={210}
                        tip={(i) => ({
                          title: `${buckets[i]} ${currency}`,
                          rows: d.sizeTypes
                            .map((t, ti) => [t, key === 'count' ? String(data[ti]![i]) : formatMoney(data[ti]![i]!, currency)] as [string, string])
                            .filter((_, ti) => data[ti]![i]! > 0),
                        })}
                      />
                    </div>
                  );
                })}
              </div>
            </Panel>

            <details className="card disclosure">
              <summary>Table: every month by payee</summary>
              <div className="table-wrap">
                <table className="dtable">
                  <thead>
                    <tr>
                      <th>Month</th>
                      {series.map((s) => (
                        <th key={s.key} className="right">
                          {s.label}
                        </th>
                      ))}
                      <th className="right">Total</th>
                      <th className="right">Payments</th>
                      <th className="right">Average payment</th>
                    </tr>
                  </thead>
                  <tbody>
                    {months
                      .map((m, i) => ({ m, i }))
                      .reverse()
                      .map(({ m, i }) => (
                        <tr key={m}>
                          <td>
                            {formatMonthLong(m)}
                            {m === now && <span className="muted"> (so far)</span>}
                          </td>
                          {series.map((s) => (
                            <td key={s.key} className="right num">
                              {s.values[i] ? f(s.values[i]!) : <span className="muted">–</span>}
                            </td>
                          ))}
                          <td className="right num">
                            <b>{f(series.reduce((a, s) => a + s.values[i]!, 0))}</b>
                          </td>
                          <td className="right num">{d.monthCount[i]}</td>
                          <td className="right num">{formatMoney(d.monthAvg[i]!, currency)}</td>
                        </tr>
                      ))}
                    <tr className="total-row">
                      <td>Total</td>
                      {series.map((s) => (
                        <td key={s.key} className="right num">
                          {f(s.values.reduce((a, b) => a + b, 0))}
                        </td>
                      ))}
                      <td className="right num">{f(d.total)}</td>
                      <td className="right num">{d.count}</td>
                      <td />
                    </tr>
                  </tbody>
                </table>
              </div>
            </details>
          </>
        );
      }}
    </Loading>
  );
}
