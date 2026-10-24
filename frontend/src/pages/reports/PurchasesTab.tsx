import { useMemo, useState } from 'react';
import { mutate, useQuery } from '../../api/query';
import type { CashFlow, Payment } from '../../api/types';
import { MoneyInput } from '../../components/inputs';
import { useBudget } from '../../context';
import { currentMonth, formatDate, formatMonthLong, formatMonthShort } from '../../lib/dates';
import { formatMoney, formatPlain, parseMoney } from '../../lib/money';
import { BarChart, HBars, Legend, SERIES, compact, money0 } from './charts';
import { CategoryPicker, Loading, Panel, PayeeSelect, Tile, describePeriod, periodMonths, useReportSettings, type Period, type ResolvedSettings } from './common';
import { thingsByMonth } from './stats';

export function PurchasesTab({ period }: { period: Period }) {
  const { budgetId } = useBudget();
  const { settings, save } = useReportSettings();
  const flow = useQuery('report_cash_flow', { budgetId, fromMonth: period.from, toMonth: period.to });
  if (!settings) return null;
  return (
    <Loading q={flow}>
      {(cf) => (
        <>
          <Things cf={cf} period={period} settings={settings} save={save} />
          <ItemByItem cf={cf} period={period} settings={settings} save={save} />
          <ItemSearch />
          <Review period={period} settings={settings} />
        </>
      )}
    </Loading>
  );
}

type Save = ReturnType<typeof useReportSettings>['save'];

const itemName = (p: Payment) => p.memo || p.payeeName || '(no payee)';

// ------------------------------------------------------------------ things by month

function Things({ cf, period, settings, save }: { cf: CashFlow; period: Period; settings: ResolvedSettings; save: Save }) {
  const { currency, categories } = useBudget();
  const ids = settings.thingsCategoryIds;
  const months = periodMonths(period);
  const now = currentMonth();
  const data = thingsByMonth(cf.rows, months, ids);
  const names = ids.map((id) => categories.flatMap((g) => g.categories).find((c) => c.id === id)?.name ?? 'Deleted category');
  const series = names.map((n, i) => ({ key: ids[i]!, label: n, color: SERIES[i % SERIES.length]!, values: data.map((m) => m.byCategory[i]!) }));
  const full = data.filter((m) => m.month < now);
  const avg = full.length ? full.reduce((a, m) => a + m.total, 0) / full.length : 0;
  const biggest = full.reduce<(typeof full)[number] | null>((b, m) => (!b || m.total > b.total ? m : b), null);
  const target = settings.thingsMonthlyTarget;
  const running = data.find((m) => m.month === now);
  const f = (v: number) => money0(v, currency);
  const [goal, setGoal] = useState(target ? formatPlain(target, currency) : '');
  const goalValue = parseMoney(goal); // '' → 0 = no goal, null = not a number

  return (
    <Panel
      title="Things, month by month"
      wide
      aside={<CategoryPicker label="Things categories" value={ids} onChange={(v) => void save({ thingsCategoryIds: v })} />}
    >
      {ids.length === 0 ? (
        <div className="empty">Choose the categories that count as "things" (shopping, electronics, furniture…).</div>
      ) : (
        <>
          <div className="stat-tiles">
            <Tile label="Average per month" value={f(avg)} sub={`${full.length} full months`} accent />
            {target ? (
              <Tile label={`Months at ${f(target)} or less`} value={`${full.filter((m) => m.total <= target).length} of ${full.length}`} sub="your monthly goal" />
            ) : null}
            <Tile label="Biggest month" value={biggest ? f(biggest.total) : '–'} sub={biggest ? formatMonthLong(biggest.month) : ''} />
            {running ? (
              <Tile label={`${formatMonthLong(now)} so far`} value={f(running.total)} sub={target ? `${f(Math.max(0, target - running.total))} left of the goal` : ''} />
            ) : (
              <Tile label="Whole period" value={f(data.reduce((a, m) => a + m.total, 0))} sub={describePeriod(period)} />
            )}
          </div>
          <Legend items={[...series.map((s) => ({ label: s.label, color: s.color })), ...(target ? [{ label: 'Monthly goal', color: 'var(--pos-strong)', dashed: true }] : [])]} />
          <BarChart
            ariaLabel="Things spending per month"
            stacked
            labels={months.map(formatMonthShort)}
            series={series}
            faded={(i) => months[i] === now}
            refLines={target ? [{ value: target, color: 'var(--pos-strong)', label: `goal ${f(target)}` }] : []}
            yFormat={(v) => compact(v, currency)}
            height={270}
            maxBar={40}
            tip={(i) => ({
              title: `${formatMonthLong(months[i]!)}${months[i] === now ? ' (so far)' : ''}: ${f(data[i]!.total)}`,
              rows: series.filter((s) => s.values[i]! > 0).map((s) => [s.label, formatMoney(s.values[i]!, currency)] as [string, string]),
              note: data[i]!.topPayees.length ? `Biggest: ${data[i]!.topPayees.map((p) => `${p.label} ${f(p.value)}`).join(', ')}` : undefined,
            })}
          />
          <div className="row-wrap small">
            <span className="muted">Monthly goal</span>
            <MoneyInput value={goal} onChange={setGoal} aria-label="Monthly goal for things" style={{ width: 110 }} className="input-sm" />
            <button className="btn btn-sm" disabled={goalValue === null || (goalValue || null) === target} onClick={() => void save({ thingsMonthlyTarget: goalValue || null })}>
              Save goal
            </button>
            <span className="muted">Card purchases count in the month they are booked; the running month is drawn lighter.</span>
          </div>
        </>
      )}
    </Panel>
  );
}

// ------------------------------------------------------------------ one payee, item by item

function ItemByItem({ cf, period, settings, save }: { cf: CashFlow; period: Period; settings: ResolvedSettings; save: Save }) {
  const { budgetId, currency } = useBudget();
  const fallback = useMemo(() => {
    const by = new Map<string, number>();
    for (const r of cf.rows) if (r.kind === 'SPENDING' && r.payeeId && r.categoryId && settings.thingsCategoryIds.includes(r.categoryId)) by.set(r.payeeId, (by.get(r.payeeId) ?? 0) + r.amount);
    return [...by].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;
  }, [cf.rows, settings.thingsCategoryIds]);
  const payeeId = settings.focusPayeeId ?? fallback;
  const inPeriod = useQuery('report_payments', payeeId ? { budgetId, fromMonth: period.from, toMonth: period.to, payeeIds: [payeeId], limit: 10000 } : null);
  const allTime = useQuery('report_payments', payeeId ? { budgetId, payeeIds: [payeeId], limit: 10000 } : null);
  const f = (v: number) => money0(v, currency);
  const picker = <PayeeSelect label="Payee for item by item" value={payeeId} onChange={(id) => void save({ focusPayeeId: id })} />;
  const name = inPeriod.data?.payments[0]?.payeeName ?? allTime.data?.payments[0]?.payeeName ?? 'One payee';

  return (
    <Panel title={`${name}, item by item`} wide aside={picker}>
      {!payeeId ? (
        <div className="empty">Choose a payee (for example an online shop) to see every purchase, grouped by category.</div>
      ) : (
        <Loading q={inPeriod}>
          {({ payments }) => {
            const orders = new Set(payments.map((p) => p.transactionId)).size;
            const total = payments.reduce((a, p) => a + p.amount, 0);
            const span = periodMonths(period).length;
            const years = new Map<string, number>();
            for (const p of allTime.data?.payments ?? []) years.set(p.date.slice(0, 4), (years.get(p.date.slice(0, 4)) ?? 0) + p.amount);
            const cats = new Map<string, { value: number; items: Payment[] }>();
            for (const p of payments) {
              const c = cats.get(p.categoryName ?? 'Uncategorized') ?? { value: 0, items: [] };
              c.value += p.amount;
              c.items.push(p);
              cats.set(p.categoryName ?? 'Uncategorized', c);
            }
            const top = [...payments].sort((a, b) => b.amount - a.amount).slice(0, 10);
            return (
              <>
                <div className="stat-tiles">
                  <Tile label="In this period" value={f(total)} sub={`${orders} orders, ${payments.length} items · ${describePeriod(period)}`} accent />
                  <Tile label="Per month" value={f(total / span)} sub={`over ${span} month${span === 1 ? '' : 's'}`} />
                  <Tile
                    label="By year"
                    value={
                      <span className="year-list">
                        {[...years]
                          .sort()
                          .slice(-4)
                          .map(([y, v]) => (
                            <span key={y}>
                              {y}: {f(v)}
                            </span>
                          ))}
                      </span>
                    }
                    sub="all time, whole calendar years"
                  />
                </div>
                {payments.length === 0 ? (
                  <div className="empty">No purchases from this payee in the period.</div>
                ) : (
                  <div className="report-grid">
                    <div className="stack">
                      <h4>By category</h4>
                      <HBars
                        format={f}
                        rows={[...cats]
                          .sort((a, b) => b[1].value - a[1].value)
                          .map(([c, x]) => ({
                            key: c,
                            label: c,
                            value: x.value,
                            share: `${x.items.length}×`,
                            details: (
                              <div className="kv kv-3">
                                {x.items.map((p) => (
                                  <ItemLine key={p.transactionId + (p.splitId ?? '')} p={p} />
                                ))}
                              </div>
                            ),
                          }))}
                      />
                      <span className="small muted">Open a category to see every item in it. Items are split lines or memos.</span>
                    </div>
                    <div className="stack">
                      <h4>Biggest purchases</h4>
                      <div className="kv kv-3">
                        {top.map((p) => (
                          <ItemLine key={p.transactionId + (p.splitId ?? '')} p={p} />
                        ))}
                      </div>
                    </div>
                  </div>
                )}
              </>
            );
          }}
        </Loading>
      )}
    </Panel>
  );
}

function ItemLine({ p }: { p: Payment }) {
  const { currency } = useBudget();
  return (
    <>
      <span className="muted num nowrap">{formatDate(p.date)}</span>
      <span>{itemName(p)}</span>
      <span className="num right">{formatMoney(p.amount, currency)}</span>
    </>
  );
}

// ------------------------------------------------------------------ search across all purchases

function ItemSearch() {
  const { budgetId, currency } = useBudget();
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const result = useQuery('report_payments', q ? { budgetId, search: q, limit: 500 } : null);
  const list = result.data?.payments ?? [];
  return (
    <Panel title="Find purchases" aside={<span className="small muted">All time · matches item, payee and category</span>}>
      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          setQ(text.trim());
        }}
      >
        <input className="input input-sm" placeholder="e.g. headphones" value={text} onChange={(e) => setText(e.target.value)} aria-label="Search purchases" />
        <button className="btn btn-sm" type="submit">
          Search
        </button>
      </form>
      {q && (
        <Loading q={result}>
          {() =>
            list.length ? (
              <div className="kv kv-3">
                {list.map((p) => (
                  <ItemLine key={p.transactionId + (p.splitId ?? '')} p={p} />
                ))}
                <span className="tot">{list.length} found</span>
                <span className="tot" />
                <span className="tot num right">{formatMoney(list.reduce((a, p) => a + p.amount, 0), currency)}</span>
              </div>
            ) : (
              <div className="empty">Nothing found for “{q}”.</div>
            )
          }
        </Loading>
      )}
    </Panel>
  );
}

// ------------------------------------------------------------------ purchase review

const REASONS = ['Consumable', 'Household basics', 'Health', 'Basic clothing', 'Replacement of something worn out', 'Gift'];

function Review({ period, settings }: { period: Period; settings: ResolvedSettings }) {
  const { budgetId, currency } = useBudget();
  const ids = settings.thingsCategoryIds;
  const q = useQuery('report_payments', {
    budgetId,
    fromMonth: period.from,
    toMonth: period.to,
    minAmount: settings.reviewMinAmount,
    ...(ids.length ? { categoryIds: ids } : {}),
    limit: 5000,
  });
  const [onlyOpen, setOnlyOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const f = (v: number) => money0(v, currency);

  const review = async (p: Payment, input: { rating?: number | null; sensibleReason?: string | null; clear?: boolean }) => {
    setBusy(p.transactionId + (p.splitId ?? ''));
    try {
      await mutate('review_purchase', { budgetId, transactionId: p.transactionId, splitId: p.splitId, ...input });
    } finally {
      setBusy(null);
    }
  };

  return (
    <Panel
      title="Purchase review, in hindsight"
      wide
      aside={
        <span className="small muted">
          {formatMoney(settings.reviewMinAmount, currency)} or more · {ids.length ? 'things categories' : 'all spending'} · {describePeriod(period)}
        </span>
      }
    >
      <p className="small muted">
        Rate each purchase: 1 = shouldn't have bought it, 5 = glad I did. Consumables, basics and replacements can be filed as sensible instead. Reviews are saved with the budget,
        so an assistant can read them through MCP (report_payments). Subscription charges are left out.
      </p>
      <Loading q={q}>
        {({ payments }) => {
          const items = payments.filter((p) => !p.subscriptionId);
          const sane = items.filter((p) => p.review?.sensibleReason);
          const rate = items.filter((p) => !p.review?.sensibleReason).sort((a, b) => b.amount - a.amount);
          const rated = rate.filter((p) => p.review?.rating);
          const low = rated.filter((p) => p.review!.rating! <= 2);
          const high = rated.filter((p) => p.review!.rating! >= 4);
          const total = (xs: Payment[]) => xs.reduce((a, p) => a + p.amount, 0);
          const avg = rated.length ? rated.reduce((a, p) => a + p.review!.rating!, 0) / rated.length : 0;
          const shown = onlyOpen ? rate.filter((p) => !p.review?.rating) : rate;
          return (
            <>
              <div className="stat-tiles">
                <Tile label="To rate" value={String(rate.length)} sub={`${f(total(rate))} · ${rated.length} rated so far`} accent />
                <Tile label="Average rating" value={rated.length ? avg.toFixed(1) : '–'} sub="of the rated ones" />
                <Tile label="Rated 1–2" value={f(total(low))} sub={`${low.length} purchases you'd skip now`} />
                <Tile label="Rated 4–5" value={f(total(high))} sub={`${high.length} purchases worth it`} />
              </div>
              {sane.length > 0 && (
                <details className="card disclosure">
                  <summary>
                    Filed as sensible: {sane.length} purchases, {f(total(sane))}
                  </summary>
                  <div className="table-wrap">
                    <table className="dtable">
                      <tbody>
                        {sane.map((p) => (
                          <tr key={p.transactionId + (p.splitId ?? '')}>
                            <td className="nowrap">{formatDate(p.date)}</td>
                            <td>{itemName(p)}</td>
                            <td className="muted">{p.review!.sensibleReason}</td>
                            <td className="right num">{formatMoney(p.amount, currency)}</td>
                            <td className="right">
                              <button className="btn btn-sm btn-ghost" onClick={() => void review(p, { clear: true })}>
                                Undo
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </details>
              )}
              <label className="check">
                <input type="checkbox" checked={onlyOpen} onChange={(e) => setOnlyOpen(e.target.checked)} /> Show only unrated
              </label>
              {shown.length === 0 ? (
                <div className="empty">{rate.length ? 'Everything is rated.' : 'No purchases to review in this period.'}</div>
              ) : (
                <div className="table-wrap">
                  <table className="dtable review-table">
                    <thead>
                      <tr>
                        <th>Date</th>
                        <th>Item</th>
                        <th className="right">Amount</th>
                        <th>Rating</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {shown.map((p) => {
                        const key = p.transactionId + (p.splitId ?? '');
                        const current = p.review?.rating ?? null;
                        return (
                          <tr key={key} aria-busy={busy === key}>
                            <td className="nowrap">{formatDate(p.date)}</td>
                            <td>
                              {itemName(p)}
                              <div className="cell-sub">
                                {[p.memo ? p.payeeName : null, p.categoryName].filter(Boolean).join(' · ')}
                              </div>
                            </td>
                            <td className="right num">{formatMoney(p.amount, currency)}</td>
                            <td>
                              <span className="rate" role="group" aria-label={`Rate ${itemName(p)}`}>
                                {[1, 2, 3, 4, 5].map((k) => (
                                  <button
                                    key={k}
                                    type="button"
                                    aria-pressed={current === k}
                                    aria-label={`Rate ${k}`}
                                    disabled={busy === key}
                                    onClick={() => void review(p, current === k ? { clear: true } : { rating: k })}
                                  >
                                    {k}
                                  </button>
                                ))}
                              </span>
                            </td>
                            <td>
                              <select
                                className="select input-sm"
                                aria-label={`File ${itemName(p)} as sensible`}
                                value=""
                                onChange={(e) => {
                                  let reason: string | null = e.target.value;
                                  if (reason === '__other') reason = window.prompt('Why was this a sensible purchase?')?.trim() || null;
                                  if (reason) void review(p, { sensibleReason: reason });
                                }}
                                style={{ width: 150 }}
                              >
                                <option value="">Sensible because…</option>
                                {REASONS.map((r) => (
                                  <option key={r} value={r}>
                                    {r}
                                  </option>
                                ))}
                                <option value="__other">Other…</option>
                              </select>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          );
        }}
      </Loading>
    </Panel>
  );
}
