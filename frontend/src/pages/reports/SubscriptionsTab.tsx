import { useMemo, useState } from 'react';
import { mutate, useQuery } from '../../api/query';
import type { Cadence, Subscription, SubscriptionKind } from '../../api/types';
import { Field, FormModal } from '../../components/ui';
import { useBudget } from '../../context';
import { addMonths, currentMonth, formatDate, todayIso } from '../../lib/dates';
import { formatMoney } from '../../lib/money';
import { money0 } from './charts';
import { Loading, Panel, Tile, describePeriod } from './common';
import { detectRecurring, lastTwelveMonths, type RecurringSuggestion } from './stats';

const CADENCE: Record<Cadence, string> = { WEEKLY: 'weekly', MONTHLY: 'monthly', QUARTERLY: 'quarterly', YEARLY: 'yearly' };

function addDays(date: string, n: number) {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number];
  return todayIso(new Date(y, m - 1, d + n));
}

export function SubscriptionsTab() {
  const { budgetId, currency } = useBudget();
  const subs = useQuery('list_subscriptions', { budgetId });
  const year = lastTwelveMonths();
  const recent = useQuery('report_payments', { budgetId, fromMonth: year[0], toMonth: currentMonth(), limit: 10000 });
  const [editing, setEditing] = useState<Subscription | 'new' | RecurringSuggestion | null>(null);
  const f = (v: number) => money0(v, currency);

  return (
    <Loading q={subs}>
      {(all) => {
        const plans = all.filter((s) => s.kind === 'SUBSCRIPTION');
        const live = plans.filter((s) => s.status !== 'ENDED');
        const ended = plans.filter((s) => s.status === 'ENDED' && (s.lastCharge ?? s.cancelledOn ?? '') >= `${addMonths(currentMonth(), -13)}-01`);
        const fixed = all.filter((s) => s.kind === 'FIXED' && s.status !== 'ENDED');
        const perMonth = live.reduce((a, s) => a + s.monthlyCost, 0);
        const paid12 = plans.reduce((a, s) => a + s.last12Months, 0);
        const groups = new Map<string, { total: number; names: string[] }>();
        for (const s of live) {
          const g = groups.get(s.groupName ?? 'Other') ?? { total: 0, names: [] };
          g.total += s.monthlyCost;
          g.names.push(s.name);
          groups.set(s.groupName ?? 'Other', g);
        }
        const topGroup = [...groups].sort((a, b) => b[1].total - a[1].total)[0];
        const soon = all
          .filter((s) => s.status !== 'ENDED' && s.nextCharge && s.nextCharge <= addDays(todayIso(), 14))
          .sort((a, b) => a.nextCharge!.localeCompare(b.nextCharge!));
        const suggestions = recent.data ? detectRecurring(recent.data.payments, all) : [];

        return (
          <>
            <div className="row-wrap">
              <p className="small muted grow">
                Charges are the spending of each subscription's payees. “Last 12 months” covers {describePeriod({ from: year[0]!, to: year.at(-1)! })}.
              </p>
              <button className="btn btn-primary btn-sm" onClick={() => setEditing('new')}>
                Add subscription
              </button>
            </div>
            <div className="stat-tiles">
              <Tile label="Running now" value={`${f(perMonth)}/mo`} sub={`${live.length} subscriptions · about ${f(perMonth * 12)} a year at this rate`} accent />
              {topGroup && (
                <Tile label={`Of which ${topGroup[0]}`} value={`${f(topGroup[1].total)}/mo`} sub={topGroup[1].names.slice(0, 4).join(', ')} />
              )}
              <Tile label="Paid in last 12 months" value={f(paid12)} sub="all subscriptions, incl. ones since ended" />
              <Tile label="Fixed contracts" value={`${f(fixed.reduce((a, s) => a + s.monthlyCost, 0))}/mo`} sub="rent, utilities, phone, insurance" />
            </div>

            {soon.length > 0 && (
              <div className="callout">
                <h3>Coming up in the next two weeks</h3>
                <p>
                  {soon.map((s, i) => (
                    <span key={s.id}>
                      {i > 0 && ' · '}
                      <b>{s.name}</b> around {formatDate(s.nextCharge!)} ({formatMoney(s.lastAmount, currency)})
                    </span>
                  ))}
                </p>
              </div>
            )}

            <Panel title="Subscriptions running now" wide>
              {live.length === 0 ? (
                <div className="empty">No subscriptions tracked yet. Add one, or track a recurring payment found below.</div>
              ) : (
                <div className="table-wrap">
                  <table className="dtable">
                    <thead>
                      <tr>
                        <th>Service</th>
                        <th>Billed</th>
                        <th className="right">Last charge</th>
                        <th className="right">Per month</th>
                        <th className="right">Last 12 mo</th>
                        <th>Next / note</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {live.map((s) => (
                        <SubRow key={s.id} s={s} onEdit={() => setEditing(s)} />
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>

            {ended.length > 0 && (
              <details className="card disclosure">
                <summary>
                  Ended in the last year: {ended.length} subscription{ended.length === 1 ? '' : 's'}, {f(ended.reduce((a, s) => a + s.monthlyCost, 0))}/mo no longer paid
                </summary>
                <div className="table-wrap">
                  <table className="dtable">
                    <thead>
                      <tr>
                        <th>Service</th>
                        <th className="right">Was per month</th>
                        <th className="right">Last charge</th>
                        <th className="right">Paid in last 12 mo</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {ended.map((s) => (
                        <tr key={s.id}>
                          <td>
                            {s.name} <GroupChip s={s} />
                          </td>
                          <td className="right num">{formatMoney(s.monthlyCost, currency)}</td>
                          <td className="right num">{s.lastCharge ? formatDate(s.lastCharge) : '–'}</td>
                          <td className="right num">{f(s.last12Months)}</td>
                          <td className="right">
                            <button className="btn btn-sm btn-ghost" onClick={() => setEditing(s)}>
                              Edit
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            )}

            <details className="card disclosure">
              <summary>Fixed contracts: rent, utilities, phone, insurance ({fixed.length})</summary>
              <div className="table-wrap">
                <table className="dtable">
                  <thead>
                    <tr>
                      <th>Contract</th>
                      <th>Billed</th>
                      <th className="right">Last charge</th>
                      <th className="right">Per month</th>
                      <th className="right">Last 12 mo</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {fixed.map((s) => (
                      <tr key={s.id}>
                        <td>{s.name}</td>
                        <td>{CADENCE[s.cadence]}</td>
                        <td className="right num">
                          {formatMoney(s.lastAmount, currency)}
                          <div className="cell-sub">{s.lastCharge ? formatDate(s.lastCharge) : 'no charge yet'}</div>
                        </td>
                        <td className="right num">{formatMoney(s.monthlyCost, currency)}</td>
                        <td className="right num">{f(s.last12Months)}</td>
                        <td className="right">
                          <button className="btn btn-sm btn-ghost" onClick={() => setEditing(s)}>
                            Edit
                          </button>
                        </td>
                      </tr>
                    ))}
                    <tr className="total-row">
                      <td>Total</td>
                      <td />
                      <td />
                      <td className="right num">{f(fixed.reduce((a, s) => a + s.monthlyCost, 0))}</td>
                      <td className="right num">{f(fixed.reduce((a, s) => a + s.last12Months, 0))}</td>
                      <td />
                    </tr>
                  </tbody>
                </table>
              </div>
            </details>

            {suggestions.length > 0 && (
              <Panel title="Recurring payments not tracked yet" wide aside={<span className="small muted">Similar amount about once a month, three times or more</span>}>
                <div className="table-wrap">
                  <table className="dtable">
                    <tbody>
                      {suggestions.map((x) => (
                        <tr key={x.payeeId}>
                          <td>{x.payeeName}</td>
                          <td className="right num">{formatMoney(x.amount, currency)}/mo</td>
                          <td className="muted">
                            {x.count} charges, last {formatDate(x.lastDate)}
                          </td>
                          <td className="right">
                            <button className="btn btn-sm" onClick={() => setEditing(x)}>
                              Track…
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Panel>
            )}

            {editing && <SubscriptionModal value={editing} groups={[...new Set(all.map((s) => s.groupName).filter(Boolean) as string[])]} onClose={() => setEditing(null)} />}
          </>
        );
      }}
    </Loading>
  );
}

function GroupChip({ s }: { s: Subscription }) {
  return s.groupName ? <span className="badge">{s.groupName}</span> : null;
}

function SubRow({ s, onEdit }: { s: Subscription; onEdit: () => void }) {
  const { currency } = useBudget();
  const changed = !s.variable && s.previousAmount !== null && s.previousAmount !== s.lastAmount;
  let note = null;
  if (s.status === 'OVERDUE') note = <span className="badge badge-warn">No charge since {s.lastCharge ? formatDate(s.lastCharge) : '–'}, may have ended</span>;
  else if (s.note) note = <span className="badge badge-accent">{s.note}</span>;
  else if (changed)
    note = (
      <span className="badge badge-warn">
        {s.lastAmount > s.previousAmount! ? '↑' : '↓'} from {formatMoney(s.previousAmount!, currency)} to {formatMoney(s.lastAmount, currency)}
      </span>
    );
  else if (s.nextCharge && s.nextCharge <= addDays(todayIso(), 7))
    note = <span className="badge badge-accent">Renews ~{formatDate(s.nextCharge)}</span>;
  else if (s.nextCharge) note = <span className="muted">~{formatDate(s.nextCharge)}</span>;
  return (
    <tr>
      <td>
        {s.name} <GroupChip s={s} />
      </td>
      <td>
        {CADENCE[s.cadence]}
        {s.variable && <span className="muted"> + purchases</span>}
      </td>
      <td className="right num">
        {s.lastCharge ? formatMoney(s.lastAmount, currency) : '–'}
        <div className="cell-sub">{s.lastCharge ? formatDate(s.lastCharge) : 'no charge yet'}</div>
      </td>
      <td className="right num">
        <b>{formatMoney(s.monthlyCost, currency)}</b>
      </td>
      <td className="right num">{money0(s.last12Months, currency)}</td>
      <td>{note}</td>
      <td className="right">
        <button className="btn btn-sm btn-ghost" onClick={onEdit} aria-label={`Edit ${s.name}`}>
          Edit
        </button>
      </td>
    </tr>
  );
}

function SubscriptionModal({ value, groups, onClose }: { value: Subscription | 'new' | RecurringSuggestion; groups: string[]; onClose: () => void }) {
  const { budgetId } = useBudget();
  const payees = useQuery('list_payees', { budgetId });
  const existing = typeof value === 'object' && 'id' in value ? value : null;
  const suggestion = typeof value === 'object' && 'payeeId' in value ? value : null;
  const [name, setName] = useState(existing?.name ?? suggestion?.payeeName ?? '');
  const [kind, setKind] = useState<SubscriptionKind>(existing?.kind ?? 'SUBSCRIPTION');
  const [cadence, setCadence] = useState<Cadence>(existing?.cadence ?? suggestion?.cadence ?? 'MONTHLY');
  const [groupName, setGroupName] = useState(existing?.groupName ?? '');
  const [note, setNote] = useState(existing?.note ?? '');
  const [variable, setVariable] = useState(existing?.variable ?? false);
  const [payeeIds, setPayeeIds] = useState<string[]>(existing?.payees.map((p) => p.id) ?? (suggestion ? [suggestion.payeeId] : []));
  const [cancelledOn, setCancelledOn] = useState(existing?.cancelledOn ?? '');
  const [filter, setFilter] = useState('');
  const options = useMemo(
    () =>
      (payees.data ?? [])
        .filter((p) => !p.transferAccountId && p.name !== 'Starting Balance')
        .filter((p) => payeeIds.includes(p.id) || !filter || p.name.toLowerCase().includes(filter.toLowerCase())),
    [payees.data, filter, payeeIds],
  );

  const submit = async () => {
    const fields = { name, kind, cadence, groupName, note, variable, payeeIds };
    if (existing) {
      await mutate('update_subscription', {
        budgetId,
        subscriptionId: existing.id,
        ...fields,
        ...(cancelledOn ? { cancelledOn } : existing.cancelledOn ? { reactivate: true } : {}),
      });
    } else {
      await mutate('create_subscription', { budgetId, ...fields, cancelledOn: cancelledOn || null });
    }
    onClose();
  };

  return (
    <FormModal title={existing ? `Edit ${existing.name}` : 'Track a subscription'} onClose={onClose} onSubmit={submit} submitLabel={existing ? 'Save' : 'Add'} wide>
      <div className="field-row">
        <Field label="Name">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} required />
        </Field>
        <Field label="Group" hint="e.g. Media, AI, Home">
          <input className="input" value={groupName} onChange={(e) => setGroupName(e.target.value)} list="sub-groups" />
          <datalist id="sub-groups">
            {groups.map((g) => (
              <option key={g} value={g} />
            ))}
          </datalist>
        </Field>
      </div>
      <div className="field-row">
        <Field label="Kind">
          <select className="select" value={kind} onChange={(e) => setKind(e.target.value as SubscriptionKind)}>
            <option value="SUBSCRIPTION">Subscription (could cancel)</option>
            <option value="FIXED">Fixed contract (rent, utilities, insurance)</option>
          </select>
        </Field>
        <Field label="Billed">
          <select className="select" value={cadence} onChange={(e) => setCadence(e.target.value as Cadence)}>
            {Object.entries(CADENCE).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <Field label="Note" hint="Shown next to it, e.g. a promo that ends">
        <input className="input" value={note} onChange={(e) => setNote(e.target.value)} />
      </Field>
      <label className="check">
        <input type="checkbox" checked={variable} onChange={(e) => setVariable(e.target.checked)} /> Plan plus one-off purchases (monthly cost = average of the last 3 months)
      </label>
      <Field label="Charged under these payees">
        <input className="input input-sm" placeholder="Filter payees…" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter payees" />
      </Field>
      <div className="payee-checks">
        {options.map((p) => (
          <label key={p.id} className="check">
            <input
              type="checkbox"
              checked={payeeIds.includes(p.id)}
              onChange={(e) => setPayeeIds(e.target.checked ? [...payeeIds, p.id] : payeeIds.filter((x) => x !== p.id))}
            />
            {p.name}
          </label>
        ))}
      </div>
      <Field label="Cancelled on" hint="Leave empty while it's running">
        <input className="input" type="date" value={cancelledOn} onChange={(e) => setCancelledOn(e.target.value)} />
      </Field>
      {existing && (
        <button
          type="button"
          className="btn btn-danger btn-sm"
          style={{ alignSelf: 'flex-start' }}
          onClick={async () => {
            await mutate('delete_subscription', { budgetId, subscriptionId: existing.id });
            onClose();
          }}
        >
          Stop tracking
        </button>
      )}
    </FormModal>
  );
}
