import { useState } from 'react';
import { useQuery } from '../../api/query';
import { CategorySelect } from '../../components/inputs';
import { useBudget } from '../../context';
import { formatDate } from '../../lib/dates';
import { Money } from '../../components/ui';
import { Loading, Panel, type Period } from './common';

const PAGE = 150;

export function TransactionsTab({ period }: { period: Period }) {
  const { budgetId, currency, categories } = useBudget();
  const [text, setText] = useState('');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [subsOnly, setSubsOnly] = useState(false);
  const [limit, setLimit] = useState(PAGE);
  const [y, m] = period.to.split('-').map(Number) as [number, number];
  const q = useQuery('list_transactions', {
    budgetId,
    sinceDate: `${period.from}-01`,
    untilDate: `${period.to}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`,
    search: text.trim() || undefined,
    categoryId: categoryId ?? undefined,
    subscriptionsOnly: subsOnly || undefined,
    limit,
  });
  const subs = useQuery('list_subscriptions', { budgetId });
  const subPayees = new Set((subs.data ?? []).flatMap((s) => s.payees.map((p) => p.id)));

  return (
    <Panel title="Transactions" wide aside={q.data && <span className="small muted">{q.data.total} transactions</span>}>
      <div className="row-wrap">
        <input
          className="input input-sm"
          type="search"
          placeholder="Search payee, memo, category…"
          aria-label="Search transactions"
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            setLimit(PAGE);
          }}
          style={{ maxWidth: 280 }}
        />
        <div style={{ width: 220 }}>
          <CategorySelect
            groups={categories}
            value={categoryId}
            onChange={(v) => {
              setCategoryId(v);
              setLimit(PAGE);
            }}
            emptyLabel="All categories"
            className="select input-sm"
            ariaLabel="Category"
          />
        </div>
        <label className="check">
          <input
            type="checkbox"
            checked={subsOnly}
            onChange={(e) => {
              setSubsOnly(e.target.checked);
              setLimit(PAGE);
            }}
          />{' '}
          Subscriptions only
        </label>
      </div>
      <Loading q={q}>
        {(page) => (
          <>
            <div className="table-wrap">
              <table className="rtable">
                <thead>
                  <tr>
                    <th className="col-date">Date</th>
                    <th>Payee</th>
                    <th>Category</th>
                    <th>Account</th>
                    <th className="num">Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {page.transactions.map((t) => (
                    <tr key={t.id}>
                      <td className="col-date">
                        {formatDate(t.date)}
                        {t.time && <div className="cell-sub">{t.time}</div>}
                      </td>
                      <td>
                        {t.payeeName ?? <span className="muted">–</span>}
                        {(subPayees.has(t.payeeId ?? '') || t.splits.some((x) => subPayees.has(x.payeeId ?? ''))) && <span className="badge badge-accent tag">sub</span>}
                        {t.memo && <div className="cell-sub">{t.memo}</div>}
                      </td>
                      <td className="muted">{t.splits.length ? 'Split' : (t.categoryName ?? (t.transferAccountId ? 'Transfer' : 'Uncategorized'))}</td>
                      <td className="muted">{t.accountName}</td>
                      <td className="num">
                        <Money value={t.amount} currency={currency} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {page.total > page.transactions.length && (
              <button className="btn btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => setLimit(Math.min(1000, limit + 300))} disabled={limit >= 1000}>
                Show more ({page.total - page.transactions.length} left)
              </button>
            )}
          </>
        )}
      </Loading>
    </Panel>
  );
}
