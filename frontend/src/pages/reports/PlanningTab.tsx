import { useEffect, useMemo, useState } from 'react';
import { mutate, useQuery } from '../../api/query';
import type { CashFlow, Device } from '../../api/types';
import { MoneyInput } from '../../components/inputs';
import { ErrorBox } from '../../components/ui';
import { useBudget } from '../../context';
import { addMonths, currentMonth, formatDate, formatMonthLong, todayIso } from '../../lib/dates';
import { formatMoney, formatPlain, parseMoney } from '../../lib/money';
import { BarChart, Legend, LineChart, compact, money0 } from './charts';
import { Loading, Panel, PayeeSelect, Tile, loadLocal, saveLocal, useReportSettings, type ResolvedSettings } from './common';
import {
  FIRE_DEFAULTS,
  POINT_INCOME,
  contributionAt,
  earliestStop,
  isMidijob,
  isMinijob,
  monthlyPension,
  partTimeContributions,
  partTimeWage,
  project,
  type FireInput,
} from './plan';
import { activeMonths, lastTwelveMonths } from './stats';

/** Monthly averages over the last 12 full months, in whole currency units. */
type Baseline = { income: number; spending: number; things: number; base: number; saving: number };

function baseline(cf: CashFlow, thingsIds: string[]): Baseline {
  const n = Math.max(1, activeMonths(cf.months.map((m) => m.month), cf.rows).length);
  const income = cf.months.reduce((a, m) => a + m.income, 0) / n / 1000;
  const spending = cf.months.reduce((a, m) => a + m.spending, 0) / n / 1000;
  const saving = cf.months.reduce((a, m) => a + m.saving, 0) / n / 1000;
  const things = cf.rows.filter((r) => r.kind === 'SPENDING' && r.categoryId && thingsIds.includes(r.categoryId)).reduce((a, r) => a + r.amount, 0) / n / 1000;
  return { income, spending, things, base: spending - things, saving };
}

export function PlanningTab() {
  const { budgetId, currency } = useBudget();
  const { settings, save } = useReportSettings();
  const year = lastTwelveMonths();
  const flow = useQuery('report_cash_flow', { budgetId, fromMonth: year[0]!, toMonth: year.at(-1)! });
  if (!settings) return null;
  return (
    <Loading q={flow}>
      {(cf) => {
        const b = baseline(cf, settings.thingsCategoryIds);
        return (
          <>
            <p className="small muted">
              Starting points are your monthly averages over the last 12 full months (or since your history starts): income {money0(b.income * 1000, currency)}, spending {money0(b.spending * 1000, currency)}{' '}
              a month (of which things {money0(b.things * 1000, currency)}). Everything here is an illustration, not financial advice.
            </p>
            <Projection b={b} />
            <TechBudget />
            <EarlyRetirement b={b} settings={settings} />
            <Assumptions settings={settings} save={save} />
          </>
        );
      }}
    </Loading>
  );
}

// ------------------------------------------------------------------ what you could invest

type ProjectionState = { things: number; rate: number; buffer: number; start: number };

function Projection({ b }: { b: Baseline }) {
  const { budgetId, currency } = useBudget();
  const key = `ynay.reports.projection.${budgetId}`;
  const now = Math.round(b.things);
  const [st, setSt] = useState<ProjectionState>(() => loadLocal(key, { things: Math.round(now * 0.5), rate: 5, buffer: 0, start: 0 }));
  useEffect(() => saveLocal(key, st), [key, st]);
  const f = (v: number) => money0(v * 1000, currency);
  const cases = [
    { id: 'minor', name: 'Minor', color: 'var(--series-4)', things: Math.round(now * 0.8), desc: 'Trim things by a fifth: skip duplicates and upgrades.' },
    { id: 'mid', name: 'Substantial', color: 'var(--series-3)', things: Math.round(now * 0.5), desc: 'Halve things: one bigger purchase every couple of months.' },
    { id: 'big', name: 'Big', color: 'var(--series-1)', things: Math.round(now * 0.15), desc: 'Replacements only: buy when something wears out.' },
  ].map((c) => ({ ...c, res: project(b.income, b.base, c.things, st.rate, st.buffer, st.start) }));
  const asNow = project(b.income, b.base, now, st.rate, st.buffer, st.start);
  const mine = project(b.income, b.base, st.things, st.rate, st.buffer, st.start);
  const diff = mine.monthly - asNow.monthly;
  const startMonth = addMonths(currentMonth(), 1);
  const startYear = Number(startMonth.slice(0, 4));
  const max = Math.max(1200, Math.round(now * 2));

  return (
    <Panel title="What you could invest" wide aside={<span className="small muted">From {formatMonthLong(startMonth)} · illustration, not a forecast</span>}>
      <div className="cases">
        {cases.map((c) => (
          <button key={c.id} type="button" className="case card" aria-pressed={st.things === c.things} onClick={() => setSt({ ...st, things: c.things })}>
            <span className="case-title">
              <span className="swatch" style={{ background: c.color }} /> {c.name}
            </span>
            <span className="case-value">
              {f(c.res.monthly)}
              <span className="muted small"> /month</span>
            </span>
            <span className="muted small">{c.desc}</span>
            <span className="kv small">
              <span>Things per month</span>
              <span className="num">{f(c.things)}</span>
              <span>vs. habits as now</span>
              <span className="num">+{f(c.res.monthly - asNow.monthly)}/mo</span>
              <span>After 10 years</span>
              <span className="num">{f(c.res.points[10]!.value)}</span>
              <span>After 20 years</span>
              <span className="num">{f(c.res.points[20]!.value)}</span>
            </span>
          </button>
        ))}
      </div>
      <div className="plan-grid">
        <div className="controls">
          <div className="bignum">
            <span className="eyebrow">Into investments each month</span>
            <span className="v">{f(mine.monthly)}</span>
            <span className="muted small">
              {Math.abs(diff) < 1 ? 'same as your habits now' : diff > 0 ? `${f(diff)} more than habits as now` : `${f(-diff)} less than habits as now`}
              {st.buffer > 0 && mine.monthly > 0 ? ` · buffer refilled after ${mine.bufferMonths} month${mine.bufferMonths === 1 ? '' : 's'}` : ''}
            </span>
          </div>
          <Slider label="Things per month" value={st.things} min={0} max={max} step={10} display={f(st.things)} onChange={(v) => setSt({ ...st, things: v })} hint={`Now about ${f(now)}.`} />
          <Slider
            label="Growth per year"
            value={st.rate}
            min={0}
            max={8}
            step={0.5}
            display={`${st.rate.toFixed(1)}%`}
            onChange={(v) => setSt({ ...st, rate: v })}
            hint="Broad world index funds have averaged roughly 5–7% a year over long periods, with bad years in between."
          />
          <Slider label="Refill the cash buffer first" value={st.buffer} min={0} max={5000} step={250} display={f(st.buffer)} onChange={(v) => setSt({ ...st, buffer: v })} />
          <Slider label="Already invested today" value={st.start} min={0} max={100000} step={1000} display={f(st.start)} onChange={(v) => setSt({ ...st, start: v })} />
        </div>
        <div className="stack" style={{ minWidth: 0 }}>
          <Legend
            items={[
              { label: 'Habits as now', color: 'var(--series-2)' },
              ...cases.map((c) => ({ label: c.name, color: c.color })),
              { label: 'Your setting', color: 'var(--text)', dashed: true },
            ]}
          />
          <LineChart
            ariaLabel="Projected investment value over 20 years"
            count={21}
            series={[
              { key: 'now', label: 'Habits as now', color: 'var(--series-2)', values: asNow.points.map((p) => p.value * 1000) },
              ...cases.map((c) => ({ key: c.id, label: c.name, color: c.color, values: c.res.points.map((p) => p.value * 1000) })),
              { key: 'mine', label: 'Your setting', color: 'var(--text)', values: mine.points.map((p) => p.value * 1000), dashed: true },
            ]}
            yFormat={(v) => compact(v, currency)}
            xLabel={(i) => (i % 5 === 0 ? (i === 0 ? formatMonthLong(startMonth).split(' ')[0]! + ` ${startYear}` : String(startYear + i)) : null)}
            tip={(i) => ({
              title: i === 0 ? 'Start' : `After ${i} year${i > 1 ? 's' : ''}`,
              rows: [
                ...cases
                  .slice()
                  .reverse()
                  .map((c) => [c.name, f(c.res.points[i]!.value)] as [string, string]),
                ['Habits as now', f(asNow.points[i]!.value)],
                ['Your setting', f(mine.points[i]!.value)],
              ],
            })}
          />
          <div className="table-wrap">
            <table className="dtable">
              <thead>
                <tr>
                  <th>After</th>
                  <th className="right">As now</th>
                  {cases.map((c) => (
                    <th key={c.id} className="right">
                      {c.name}
                    </th>
                  ))}
                  <th className="right">Your setting</th>
                </tr>
              </thead>
              <tbody>
                {[5, 10, 15, 20].map((k) => (
                  <tr key={k}>
                    <td>
                      {k} years <span className="muted">({startYear + k})</span>
                    </td>
                    <td className="right num">{f(asNow.points[k]!.value)}</td>
                    {cases.map((c) => (
                      <td key={c.id} className="right num">
                        {f(c.res.points[k]!.value)}
                      </td>
                    ))}
                    <td className="right num">
                      <b>{f(mine.points[k]!.value)}</b>
                      <div className="cell-sub">paid in {f(mine.points[k]!.paidIn)}</div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <p className="small muted">
        How it works: average income {f(b.income)} a month minus {f(b.base)} for everything except things, minus the things slider. Growth is compounded monthly and before tax.
      </p>
    </Panel>
  );
}

function Slider({
  label,
  value,
  min,
  max,
  step,
  display,
  onChange,
  hint,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  display: string;
  onChange: (v: number) => void;
  hint?: string;
}) {
  return (
    <label className="field slider">
      <span>
        {label} <b>{display}</b>
      </span>
      <input type="range" min={min} max={max} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} />
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

// ------------------------------------------------------------------ tech replacement budget

function TechBudget() {
  const { budgetId, currency } = useBudget();
  const devices = useQuery('list_devices', { budgetId });
  const [error, setError] = useState<Error | null>(null);
  const run = (p: Promise<unknown>) => p.then(() => setError(null), (e: Error) => setError(e));
  const f = (v: number) => money0(v, currency);
  const thisYear = Number(todayIso().slice(0, 4));
  const years = Array.from({ length: 11 }, (_, i) => thisYear + i);

  return (
    <Panel title="Tech replacement budget" wide aside={<span className="small muted">What your devices cost per month if you replace each one only when it's worn out</span>}>
      <Loading q={devices}>
        {(list) => {
          const on = list.filter((d) => d.active);
          const monthly = on.reduce((a, d) => a + d.monthlyCost, 0);
          const boughtRecently = list.filter((d) => d.purchasedOn >= `${addMonths(currentMonth(), -12)}-01`).reduce((a, d) => a + d.price, 0);
          const byYear = years.map((y) => {
            const due = on.filter((d) => {
              const first = Number(d.replaceOn.slice(0, 4));
              return y >= first && (y - first) % d.lifespanYears === 0;
            });
            return { year: y, total: due.reduce((a, d) => a + d.price, 0), names: due.map((d) => d.name) };
          });
          return (
            <>
              <div className="stat-tiles">
                <Tile label="Set aside per month" value={f(monthly)} sub={`${on.length} devices, ${f(on.reduce((a, d) => a + d.price, 0))} of tech`} accent />
                <Tile label="Per year" value={f(monthly * 12)} sub="if each device lasts its full lifespan" />
                <Tile label="Bought in the last 12 months" value={f(boughtRecently)} sub="devices in this list" />
              </div>
              {on.length > 0 && (
                <BarChart
                  ariaLabel="Expected replacement spending by year"
                  labels={years.map(String)}
                  xLabel={(i) => String(years[i])}
                  series={[{ key: 'r', label: 'Replacements', color: 'var(--series-1)', values: byYear.map((y) => y.total) }]}
                  yFormat={(v) => compact(v, currency)}
                  height={210}
                  maxBar={30}
                  tip={(i) => ({ title: `${years[i]}: ${f(byYear[i]!.total)}`, rows: byYear[i]!.names.length ? byYear[i]!.names.map((n) => [n, ''] as [string, string]) : [['nothing due', '']] })}
                />
              )}
              <div className="table-wrap">
                <table className="dtable tech-table">
                  <thead>
                    <tr>
                      <th />
                      <th>Device</th>
                      <th className="right">Paid</th>
                      <th>Bought</th>
                      <th className="right">Lifespan (yrs)</th>
                      <th className="right">Per month</th>
                      <th>Replace around</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {list.map((d) => (
                      <DeviceRow key={d.id} d={d} run={run} />
                    ))}
                    <NewDevice run={run} />
                    <tr className="total-row">
                      <td />
                      <td>Total</td>
                      <td className="right num">{f(on.reduce((a, d) => a + d.price, 0))}</td>
                      <td />
                      <td />
                      <td className="right num">{f(monthly)}</td>
                      <td colSpan={2} />
                    </tr>
                  </tbody>
                </table>
              </div>
              {error && <ErrorBox error={error} />}
              <p className="small muted">Untick what you no longer own or won't replace. Devices are stored with the budget; an assistant can add them from your purchases through MCP.</p>
            </>
          );
        }}
      </Loading>
    </Panel>
  );
}

function DeviceRow({ d, run }: { d: Device; run: (p: Promise<unknown>) => void }) {
  const { budgetId, currency } = useBudget();
  const [price, setPrice] = useState(formatPlain(d.price, currency));
  const [years, setYears] = useState(String(d.lifespanYears));
  useEffect(() => setPrice(formatPlain(d.price, currency)), [d.price, currency]);
  useEffect(() => setYears(String(d.lifespanYears)), [d.lifespanYears]);
  const update = (patch: Partial<Device>) => run(mutate('update_device', { budgetId, deviceId: d.id, ...patch }));
  return (
    <tr className={d.active ? '' : 'off'}>
      <td>
        <input type="checkbox" checked={d.active} aria-label={`Include ${d.name}`} onChange={(e) => update({ active: e.target.checked })} />
      </td>
      <td>{d.name}</td>
      <td className="right">
        <MoneyInput
          value={price}
          onChange={setPrice}
          aria-label={`Price of ${d.name}`}
          className="input-sm"
          style={{ width: 100 }}
          onBlur={() => {
            const v = parseMoney(price);
            if (v !== null && v !== d.price) update({ price: v });
          }}
        />
      </td>
      <td className="muted nowrap">{formatDate(d.purchasedOn)}</td>
      <td className="right">
        <input
          className="input input-sm"
          type="number"
          min={1}
          max={50}
          value={years}
          aria-label={`Lifespan of ${d.name} in years`}
          style={{ width: 64 }}
          onChange={(e) => setYears(e.target.value)}
          onBlur={() => {
            const v = Number(years);
            if (v >= 1 && v <= 50 && v !== d.lifespanYears) update({ lifespanYears: v });
          }}
        />
      </td>
      <td className="right num">{formatMoney(d.monthlyCost, currency)}</td>
      <td className="nowrap">{formatMonthLong(d.replaceOn.slice(0, 7))}</td>
      <td className="right">
        <button className="btn btn-sm btn-ghost" aria-label={`Remove ${d.name}`} onClick={() => run(mutate('delete_device', { budgetId, deviceId: d.id }))}>
          Remove
        </button>
      </td>
    </tr>
  );
}

function NewDevice({ run }: { run: (p: Promise<unknown>) => void }) {
  const { budgetId } = useBudget();
  const [name, setName] = useState('');
  const [price, setPrice] = useState('');
  const [date, setDate] = useState(todayIso());
  const [years, setYears] = useState('5');
  const valid = name.trim() && parseMoney(price) && date && Number(years) >= 1;
  return (
    <tr className="new-row">
      <td />
      <td>
        <input className="input input-sm" placeholder="New device" value={name} onChange={(e) => setName(e.target.value)} aria-label="New device name" />
      </td>
      <td className="right">
        <MoneyInput value={price} onChange={setPrice} aria-label="New device price" className="input-sm" style={{ width: 100 }} placeholder="Price" />
      </td>
      <td>
        <input className="input input-sm" type="date" value={date} onChange={(e) => setDate(e.target.value)} aria-label="Bought on" />
      </td>
      <td className="right">
        <input className="input input-sm" type="number" min={1} max={50} value={years} onChange={(e) => setYears(e.target.value)} aria-label="New device lifespan" style={{ width: 64 }} />
      </td>
      <td colSpan={3} className="right">
        <button
          className="btn btn-sm"
          disabled={!valid}
          onClick={() => {
            run(
              mutate('create_device', { budgetId, name: name.trim(), price: parseMoney(price)!, purchasedOn: date, lifespanYears: Number(years) }).then(() => {
                setName('');
                setPrice('');
              }),
            );
          }}
        >
          Add device
        </button>
      </td>
    </tr>
  );
}

// ------------------------------------------------------------------ early retirement (Germany)

const NUM_FIELDS: { k: keyof FireInput; label: string; step: number; min?: number; max?: number }[] = [
  { k: 'age', label: 'Age now', step: 1, min: 18, max: 66 },
  { k: 'start', label: 'Invested today', step: 1000, min: 0 },
  { k: 'gross', label: 'Gross salary / year', step: 1000, min: 0 },
  { k: 'points', label: 'Pension points so far', step: 0.5, min: 0 },
  { k: 'endAge', label: 'Money must last until age', step: 1, min: 70, max: 105 },
  { k: 'dragPct', label: 'Tax drag while saving (%/yr)', step: 0.05, min: 0, max: 1 },
];

function EarlyRetirement({ b, settings }: { b: Baseline; settings: ResolvedSettings }) {
  const { budgetId, currency } = useBudget();
  const key = `ynay.reports.fire.${budgetId}`;
  const defaults = useMemo<FireInput>(
    () => ({
      ...FIRE_DEFAULTS,
      invest: Math.max(0, Math.round(b.saving / 5) * 5),
      spend: Math.max(1200, Math.round(b.base / 50) * 50),
      gross: settings.grossSalaryPerYear ? Math.round(settings.grossSalaryPerYear / 1000) : FIRE_DEFAULTS.gross,
      netPay: Math.round(b.income),
    }),
    [b, settings.grossSalaryPerYear],
  );
  const [o, setO] = useState<FireInput>(() => ({ ...loadLocal(key, defaults), netPay: defaults.netPay }));
  useEffect(() => saveLocal(key, o), [key, o]);
  const set = (patch: Partial<FireInput>) => setO({ ...o, ...patch });
  const f = (v: number) => money0(v * 1000, currency);

  const { stopAge, run, found } = useMemo(() => earliestStop(o), [o]);
  const firstYear = run.first ?? { withdrawal: 0, health: 0, tax: 0, pension: 0, wage: 0, value: 0 };
  const at67 = run.at67 ?? { withdrawal: 0, health: 0, tax: 0, pension: 0, wage: 0, value: 0 };
  const needed = run.points.find((p) => p.age === stopAge - 1)?.value ?? o.start;
  const pension = monthlyPension(o, stopAge);
  const wageY = partTimeWage(o, stopAge);
  const thisYear = Number(todayIso().slice(0, 4));
  const presets = [
    { label: 'As now', v: Math.max(0, Math.round(b.saving)) },
    { label: 'Halve things', v: Math.max(0, Math.round(b.income - b.base - b.things * 0.5)) },
    { label: 'Replacements only', v: Math.max(0, Math.round(b.income - b.base - b.things * 0.15)) },
  ];
  const span = o.endAge - o.age;

  return (
    <Panel title="When could you stop working?" wide aside={<span className="small muted">Ballpark early-retirement calculator for Germany · today's money</span>}>
      <div className="stat-tiles">
        <Tile label="Earliest stop age" value={found ? String(stopAge) : '67+'} sub={found ? `in ${stopAge - o.age} years (${thisYear + stopAge - o.age})` : `money runs out before ${o.endAge} even at 67`} accent />
        <Tile
          label="Portfolio needed"
          value={f(needed)}
          sub={`at the stop age${o.wageGrowthPct > 0 && o.raiseSharePct > 0 ? ` · investing ${f(contributionAt(o, Math.max(o.age, stopAge - 1)))}/mo by then` : ''}`}
        />
        <Tile label="First-year withdrawal" value={needed > 0 ? `${((firstYear.withdrawal / needed) * 100).toFixed(1)}%` : '–'} sub={`${f(firstYear.withdrawal / 12)}/month incl. tax and health insurance`} />
        <Tile label="Pension at 67" value={f(pension)} sub="gross per month (statutory)" />
      </div>
      <div className="plan-grid">
        <div className="controls">
          <Slider label="Invested per month" value={o.invest} min={0} max={Math.max(2500, presets[2]!.v)} step={5} display={f(o.invest)} onChange={(v) => set({ invest: v })} />
          <div className="row-wrap">
            {presets.map((p) => (
              <button key={p.label} type="button" className="btn btn-sm" aria-pressed={o.invest === p.v} onClick={() => set({ invest: p.v })}>
                {p.label} {f(p.v)}
              </button>
            ))}
          </div>
          <Slider
            label="Spending in retirement, per month"
            value={o.spend}
            min={800}
            max={6000}
            step={50}
            display={f(o.spend)}
            onChange={(v) => set({ spend: v })}
            hint={`Before health insurance and tax, which are added on top. Today you spend about ${f(b.base)} a month without things.`}
          />
          <Slider label="Return per year (before inflation)" value={o.returnPct} min={2} max={9} step={0.5} display={`${o.returnPct.toFixed(1)}%`} onChange={(v) => set({ returnPct: v })} />
          <Slider label="Inflation" value={o.inflationPct} min={0} max={4} step={0.25} display={`${o.inflationPct.toFixed(2)}%`} onChange={(v) => set({ inflationPct: v })} />
          <Slider
            label="Wage growth above inflation"
            value={o.wageGrowthPct}
            min={0}
            max={4}
            step={0.25}
            display={`${o.wageGrowthPct.toFixed(2)}%`}
            onChange={(v) => set({ wageGrowthPct: v })}
            hint="German wages have grown roughly 0.5–1.5% a year above inflation in normal years."
          />
          <Slider label="Share of each raise you invest" value={o.raiseSharePct} min={0} max={100} step={5} display={`${o.raiseSharePct}%`} onChange={(v) => set({ raiseSharePct: v })} />
          <div className="field-row">
            {NUM_FIELDS.map((x) => (
              <label key={x.k} className="field">
                <span>{x.label}</span>
                <input
                  className="input input-sm"
                  type="number"
                  step={x.step}
                  min={x.min}
                  max={x.max}
                  value={o[x.k] as number}
                  onChange={(e) => e.target.value !== '' && set({ [x.k]: Number(e.target.value) } as Partial<FireInput>)}
                />
              </label>
            ))}
          </div>
          <label className="check">
            <input type="checkbox" checked={o.partTime} onChange={(e) => set({ partTime: e.target.checked })} /> Work part-time from the stop age until 67
          </label>
          {o.partTime && (
            <>
              <div className="field-row">
                <label className="field">
                  <span>Hours per week</span>
                  <input className="input input-sm" type="number" min={1} max={40} value={o.hours} onChange={(e) => set({ hours: Number(e.target.value) || 1 })} />
                </label>
                <label className="field">
                  <span>Hourly wage</span>
                  <input className="input input-sm" type="number" min={5} max={80} step={0.1} value={o.wage} onChange={(e) => set({ wage: Number(e.target.value) || 0 })} />
                </label>
              </div>
              <span className="field-hint">
                {f(wageY / 12)} gross a month at the stop age.{' '}
                {isMinijob(wageY)
                  ? 'That is a minijob (up to €603): no health insurance through the job, so voluntary insurance still applies.'
                  : isMidijob(wageY)
                    ? `Reduced employee contributions (${f(partTimeContributions(wageY) / 12)}/month) cover health insurance. Earns ${(wageY / POINT_INCOME).toFixed(2)} pension points a year.`
                    : `Regular job: ${f(partTimeContributions(wageY) / 12)}/month contributions, earns ${(wageY / POINT_INCOME).toFixed(2)} pension points a year.`}
              </span>
            </>
          )}
          <label className="check">
            <input type="checkbox" checked={o.guenstiger} onChange={(e) => set({ guenstiger: e.target.checked })} /> Use the lower personal tax rate on gains when it's below 25% (Günstigerprüfung)
          </label>
          <button type="button" className="btn btn-sm btn-ghost" style={{ alignSelf: 'flex-start' }} onClick={() => setO(defaults)}>
            Reset to my averages
          </button>
        </div>
        <div className="stack" style={{ minWidth: 0 }}>
          <Legend items={[{ label: "Portfolio in today's money", color: 'var(--series-1)' }]} />
          <LineChart
            ariaLabel="Portfolio value by age"
            count={run.points.length}
            series={[{ key: 'v', label: 'Portfolio', color: 'var(--series-1)', values: run.points.map((p) => p.value * 1000), area: true }]}
            yFormat={(v) => compact(v, currency)}
            xLabel={(i) => {
              const age = run.points[i]!.age;
              return age % 10 === 0 ? String(age) : null;
            }}
            markers={[{ at: stopAge - o.age, label: `stop ${stopAge}` }, ...(stopAge !== 67 && 67 - o.age <= span ? [{ at: 67 - o.age, label: 'pension 67' }] : [])]}
            tip={(i) => {
              const p = run.points[i]!;
              return {
                title: `Age ${p.age} (${thisYear + p.age - o.age})`,
                rows: [['Portfolio', f(p.value)], ...(p.pension ? ([['Pension', `${f(p.pension / 12)}/mo`]] as [string, string][]) : [])],
                note: p.age < stopAge ? 'still working' : 'living off savings',
              };
            }}
          />
          <div className="table-wrap">
            <table className="dtable">
              <thead>
                <tr>
                  <th>Per month, today's money</th>
                  <th className="right">First year (age {stopAge})</th>
                  <th className="right">From 67</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>Spending</td>
                  <td className="right num">{f(o.spend)}</td>
                  <td className="right num">{f(o.spend)}</td>
                </tr>
                <tr>
                  <td>{firstYear.wage > 603 * 12 ? 'Health insurance / social contributions' : 'Health and care insurance'}</td>
                  <td className="right num">{f(firstYear.health / 12)}</td>
                  <td className="right num">{f(at67.health / 12)}</td>
                </tr>
                <tr>
                  <td>Income tax</td>
                  <td className="right num">{f(firstYear.tax / 12)}</td>
                  <td className="right num">{f(at67.tax / 12)}</td>
                </tr>
                {o.partTime && (
                  <tr>
                    <td>Part-time wage (gross)</td>
                    <td className="right num">{stopAge < 67 ? f(firstYear.wage / 12) : '–'}</td>
                    <td className="right num">–</td>
                  </tr>
                )}
                <tr>
                  <td>Statutory pension (gross)</td>
                  <td className="right num">{stopAge >= 67 ? f(firstYear.pension / 12) : '–'}</td>
                  <td className="right num">{f(at67.pension / 12)}</td>
                </tr>
                <tr className="total-row">
                  <td>Taken from the portfolio</td>
                  <td className="right num">{f(firstYear.withdrawal / 12)}</td>
                  <td className="right num">{f(at67.withdrawal / 12)}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <details className="notes">
        <summary>How it works</summary>
        <p>
          Savings grow at the return minus inflation minus a small tax drag (the yearly Vorabpauschale on accumulating funds). From the stop age, each year's withdrawal covers
          spending, health insurance and tax. Fund gains are taxed at 26.375% (25% plus solidarity surcharge); 30% of gains are tax-free for equity funds (Teilfreistellung) and
          the first €1,000 of gains a year is tax-free (Sparerpauschbetrag, not inflation-adjusted). With the box ticked, gains are taxed at your personal rate when that's lower.
        </p>
        <p>
          Health insurance before 67: voluntary public insurance at 21.1% (incl. care insurance for the childless) on taxable gains, with a minimum of about €278 a month. From 67:
          12.95% on the pension plus 21.1% on gains. Pension: points × €42.52 (from July 2026), held flat in today's money; income of €51,944 a year earns one point, capped at
          €101,400. 2026 income tax brackets throughout.
        </p>
        <p>
          Not modelled: bad market years early in retirement, career jumps, Riester or company pensions, church tax and pension reductions for retiring before 67. Your
          Renteninformation from the DRV has your real pension points. A rough range, not a plan or financial advice.
        </p>
      </details>
    </Panel>
  );
}

// ------------------------------------------------------------------ report settings

function Assumptions({ settings, save }: { settings: ResolvedSettings; save: ReturnType<typeof useReportSettings>['save'] }) {
  const { currency } = useBudget();
  const [gross, setGross] = useState(settings.grossSalaryPerYear ? formatPlain(settings.grossSalaryPerYear, currency) : '');
  const [min, setMin] = useState(formatPlain(settings.reviewMinAmount, currency));
  const [error, setError] = useState<Error | null>(null);
  const grossValue = parseMoney(gross);
  const minValue = parseMoney(min);
  return (
    <Panel title="Report settings" wide aside={<span className="small muted">Shared by everyone on this budget</span>}>
      <div className="field-row">
        <label className="field">
          <span>Gross salary per year</span>
          <MoneyInput value={gross} onChange={setGross} placeholder="e.g. 74000" />
          <span className="field-hint">Used by the money-flow chart to show tax and contributions.</span>
        </label>
        <label className="field">
          <span>Net salary comes from</span>
          <PayeeSelect label="Salary payee" value={settings.salaryPayeeId} onChange={(id) => void save({ salaryPayeeId: id }).catch(setError)} />
          <span className="field-hint">Defaults to your largest income source.</span>
        </label>
        <label className="field">
          <span>Review purchases from</span>
          <MoneyInput value={min} onChange={setMin} />
          <span className="field-hint">Purchases of at least this much show up in the purchase review.</span>
        </label>
      </div>
      <div className="row">
        <button
          className="btn btn-primary btn-sm"
          disabled={grossValue === null || minValue === null}
          onClick={() =>
            void save({ grossSalaryPerYear: grossValue || null, reviewMinAmount: minValue || null })
              .then(() => setError(null))
              .catch(setError)
          }
        >
          Save settings
        </button>
        <span className="small muted">Things categories, the deep-dive categories and the item-by-item payee are chosen on their tabs.</span>
      </div>
      {error && <ErrorBox error={error} />}
    </Panel>
  );
}
