import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useBudget } from '../context';
import { addMonths, currentMonth, formatMonthShort, monthRange } from '../lib/dates';
import { PRESETS, describePeriod, presetRange, type Period, type Preset } from './reports/common';
import { OverviewTab } from './reports/OverviewTab';
import { PlanningTab } from './reports/PlanningTab';
import { PurchasesTab } from './reports/PurchasesTab';
import { SpendingTab } from './reports/SpendingTab';
import { SubscriptionsTab } from './reports/SubscriptionsTab';
import { TransactionsTab } from './reports/TransactionsTab';

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'subscriptions', label: 'Subscriptions' },
  { id: 'spending', label: 'Spending deep dive' },
  { id: 'purchases', label: 'Purchases' },
  { id: 'planning', label: 'Planning' },
  { id: 'transactions', label: 'Transactions' },
] as const;
type TabId = (typeof TABS)[number]['id'];

/** Tabs that don't use the period selector. */
const NO_PERIOD: TabId[] = ['subscriptions', 'planning'];

export function ReportsPage() {
  const { budgetId } = useBudget();
  const { tab: tabParam } = useParams();
  const navigate = useNavigate();
  const tab: TabId = TABS.some((t) => t.id === tabParam) ? (tabParam as TabId) : 'overview';
  const [preset, setPreset] = useState<Preset | null>('l12');
  const [period, setPeriod] = useState<Period>(presetRange('l12'));
  const months = useMemo(() => monthRange(addMonths(currentMonth(), -59), addMonths(currentMonth(), 12)).reverse(), []);

  return (
    <>
      <header className="page-header report-header">
        <div className="stack-tight">
          <h1>Reports</h1>
          {!NO_PERIOD.includes(tab) && <span className="small muted">{describePeriod(period)}</span>}
        </div>
        <span className="spacer" />
        {!NO_PERIOD.includes(tab) && (
          <div className="row-wrap">
            <div className="segmented" role="group" aria-label="Date range presets">
              {PRESETS.map((p) => (
                <button
                  key={p.value}
                  aria-pressed={preset === p.value}
                  onClick={() => {
                    setPreset(p.value);
                    setPeriod(presetRange(p.value));
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
                value={period.from}
                onChange={(e) => {
                  setPreset(null);
                  setPeriod({ from: e.target.value, to: e.target.value > period.to ? e.target.value : period.to });
                }}
                style={{ width: 120 }}
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
                value={period.to}
                onChange={(e) => {
                  setPreset(null);
                  setPeriod({ from: e.target.value < period.from ? e.target.value : period.from, to: e.target.value });
                }}
                style={{ width: 120 }}
              >
                {months.map((m) => (
                  <option key={m} value={m}>
                    {formatMonthShort(m)}
                  </option>
                ))}
              </select>
            </div>
          </div>
        )}
      </header>
      <div className="report-tabs tabs" role="tablist" aria-label="Reports">
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            aria-selected={tab === t.id}
            onClick={() => navigate(`/budgets/${budgetId}/reports${t.id === 'overview' ? '' : `/${t.id}`}`)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div className="page-body page-scroll" role="tabpanel" aria-label={TABS.find((t) => t.id === tab)!.label}>
        {tab === 'overview' && <OverviewTab period={period} />}
        {tab === 'subscriptions' && <SubscriptionsTab />}
        {tab === 'spending' && <SpendingTab period={period} />}
        {tab === 'purchases' && <PurchasesTab period={period} />}
        {tab === 'planning' && <PlanningTab />}
        {tab === 'transactions' && <TransactionsTab period={period} />}
      </div>
    </>
  );
}
