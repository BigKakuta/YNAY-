import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { call } from '../api/client';
import { invalidateAll, mutate, useQuery } from '../api/query';
import type { CreatedApiToken, Role } from '../api/types';
import { useToast } from '../components/toast';
import { CopyButton, ErrorBox, Field, FormModal, Icon, Spinner, initials } from '../components/ui';
import { useBudget, useMe } from '../context';
import { formatDateTime } from '../lib/dates';
import { CurrencySelect, TopBar } from './BudgetPicker';

/** `/settings`: account-level settings, reachable from the budget picker. */
export function UserSettingsPage() {
  return (
    <>
      <TopBar />
      <main className="page-narrow stack-lg">
        <h1>Settings</h1>
        <AccountSections />
      </main>
    </>
  );
}

/** `/budgets/:id/settings`: budget settings plus the account-level sections. */
export function BudgetSettingsPage() {
  useEffect(() => {
    if (window.location.hash) document.getElementById(window.location.hash.slice(1))?.scrollIntoView();
  }, []);
  return (
    <>
      <header className="page-header">
        <h1>Settings</h1>
      </header>
      <div className="page-scroll">
        <div className="page-body settings stack-lg">
          <BudgetSection />
          <MembersSection />
          <AccountSections />
          <DangerZone />
        </div>
      </div>
    </>
  );
}

function AccountSections() {
  return (
    <>
      <ProfileSection />
      <McpSection />
    </>
  );
}

function Section({ id, title, description, children, actions }: { id?: string; title: string; description?: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="card" id={id}>
      <div className="card-header">
        <div className="grow stack" style={{ gap: 2 }}>
          <h3>{title}</h3>
          {description && <span className="small muted">{description}</span>}
        </div>
        {actions}
      </div>
      <div className="card-body settings-section">{children}</div>
    </section>
  );
}

function ProfileSection() {
  const me = useQuery('whoami', {});
  const fallback = useMe();
  const u = me.data ?? fallback;
  return (
    <Section title="Profile" description="Managed by your Kanidm account.">
      <div className="row" style={{ gap: 14 }}>
        <span className="avatar" style={{ width: 44, height: 44, fontSize: 16 }}>
          {initials(u.displayName ?? u.username)}
        </span>
        <div className="stack" style={{ gap: 2 }}>
          <strong>{u.displayName ?? u.username}</strong>
          <span className="small muted">
            @{u.username}
            {u.email && ` · ${u.email}`}
          </span>
        </div>
      </div>
    </Section>
  );
}

function McpSection() {
  const tokens = useQuery('list_api_tokens', {});
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<CreatedApiToken | null>(null);
  const [revoking, setRevoking] = useState<string | null>(null);
  const url = `${window.location.origin}/mcp`;
  const token = created?.token ?? '<token>';
  const cli = `claude mcp add --transport http ynay ${url} \\\n  --header "Authorization: Bearer ${token}"`;
  const json = JSON.stringify({ mcpServers: { ynay: { type: 'http', url, headers: { Authorization: `Bearer ${token}` } } } }, null, 2);
  const [tab, setTab] = useState<'cli' | 'json'>('cli');

  return (
    <Section
      id="mcp"
      title="MCP access tokens"
      description="Let MCP clients (Claude Code, Claude Desktop, …) use your budgets. A token acts as you, so treat it like a password."
      actions={
        <button className="btn btn-primary btn-sm" onClick={() => setCreating(true)}>
          <Icon name="plus" size={14} /> New token
        </button>
      }
    >
      {created && (
        <div className="alert alert-info stack" style={{ display: 'flex', gap: 8 }}>
          <strong>Token “{created.name}” created. Copy it now — it won’t be shown again.</strong>
          <div className="token-reveal">
            <code data-testid="new-token">{created.token}</code>
            <CopyButton text={created.token} />
          </div>
        </div>
      )}
      {tokens.error && <ErrorBox error={tokens.error} onRetry={tokens.reload} />}
      {!tokens.data && !tokens.error && <Spinner />}
      {tokens.data && tokens.data.length === 0 && <p className="small muted">No tokens yet.</p>}
      {tokens.data && tokens.data.length > 0 && (
        <table className="dtable">
          <thead>
            <tr>
              <th>Name</th>
              <th>Token</th>
              <th>Created</th>
              <th>Last used</th>
              <th>Expires</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {tokens.data.map((t) => (
              <tr key={t.id}>
                <td><strong>{t.name}</strong></td>
                <td><code>{t.tokenPrefix}…</code></td>
                <td>{formatDateTime(t.createdAt)}</td>
                <td>{t.lastUsedAt ? formatDateTime(t.lastUsedAt) : <span className="muted">Never</span>}</td>
                <td>{t.expiresAt ? formatDateTime(t.expiresAt) : <span className="muted">Never</span>}</td>
                <td className="right">
                  {revoking === t.id ? (
                    <span className="row" style={{ justifyContent: 'flex-end' }}>
                      <button
                        className="btn btn-danger-solid btn-sm"
                        onClick={() =>
                          mutate('revoke_api_token', { tokenId: t.id }).then(() => {
                            setRevoking(null);
                            if (created?.id === t.id) setCreated(null);
                            toast.info('Token revoked');
                          }, toast.error)
                        }
                      >
                        Revoke
                      </button>
                      <button className="btn btn-sm" onClick={() => setRevoking(null)}>
                        Keep
                      </button>
                    </span>
                  ) : (
                    <button className="btn btn-danger btn-sm" onClick={() => setRevoking(t.id)}>
                      Revoke…
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <hr className="divider" />
      <div className="stack">
        <h4>Connect a client</h4>
        <p className="small muted">
          Server URL <code>{url}</code>, authenticated with <code>Authorization: Bearer &lt;token&gt;</code>.
          {!created && ' Create a token above to fill it in below.'}
        </p>
        <div className="tabs" role="tablist">
          <button role="tab" aria-selected={tab === 'cli'} onClick={() => setTab('cli')}>
            Claude Code
          </button>
          <button role="tab" aria-selected={tab === 'json'} onClick={() => setTab('json')}>
            .mcp.json
          </button>
        </div>
        <div className="code-wrap">
          <pre className="codeblock" data-testid="mcp-snippet">
            {tab === 'cli' ? cli : json}
          </pre>
          <span className="copy">
            <CopyButton text={tab === 'cli' ? cli : json} />
          </span>
        </div>
      </div>
      {creating && (
        <CreateTokenDialog
          onClose={() => setCreating(false)}
          onCreated={(t) => {
            setCreated(t);
            setCreating(false);
          }}
        />
      )}
    </Section>
  );
}

function CreateTokenDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (t: CreatedApiToken) => void }) {
  const [name, setName] = useState('');
  const [days, setDays] = useState('90');
  return (
    <FormModal
      title="New MCP access token"
      submitLabel="Create token"
      onClose={onClose}
      disabled={!name.trim()}
      onSubmit={async () => {
        const t = await mutate('create_api_token', { name: name.trim(), expiresInDays: days ? Number(days) : null });
        onCreated(t);
      }}
    >
      <Field label="Name" hint="Where you'll use it, e.g. “Claude Code on laptop”.">
        <input className="input" value={name} onChange={(e) => setName(e.target.value)} autoFocus />
      </Field>
      <Field label="Expires">
        <select className="select" value={days} onChange={(e) => setDays(e.target.value)}>
          <option value="7">In 7 days</option>
          <option value="30">In 30 days</option>
          <option value="90">In 90 days</option>
          <option value="365">In 1 year</option>
          <option value="">Never</option>
        </select>
      </Field>
    </FormModal>
  );
}

function BudgetSection() {
  const { budget, budgetId } = useBudget();
  const toast = useToast();
  const [name, setName] = useState(budget.name);
  const [currency, setCurrency] = useState(budget.currencyCode);
  useEffect(() => {
    setName(budget.name);
    setCurrency(budget.currencyCode);
  }, [budget.name, budget.currencyCode]);
  const dirty = name.trim() !== budget.name || currency !== budget.currencyCode;
  return (
    <Section title="Budget" description="Name and currency of this budget.">
      <form
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          mutate('update_budget', { budgetId, name: name.trim(), currencyCode: currency }).then(() => toast.info('Budget saved'), toast.error);
        }}
      >
        <div className="field-row">
          <Field label="Name">
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Currency" hint="Changes how amounts are displayed; amounts are not converted.">
            <CurrencySelect value={currency} onChange={setCurrency} />
          </Field>
        </div>
        <div>
          <button className="btn btn-primary btn-sm" disabled={!dirty || !name.trim()}>
            Save changes
          </button>
        </div>
      </form>
    </Section>
  );
}

function MembersSection() {
  const { budgetId, budget } = useBudget();
  const me = useMe();
  const toast = useToast();
  const members = useQuery('list_budget_members', { budgetId });
  const [username, setUsername] = useState('');
  const [role, setRole] = useState<Role>('EDITOR');
  const owner = budget.role === 'OWNER';
  return (
    <Section title="Members" description={owner ? 'People who can see and edit this budget.' : 'Only the owner can change who has access.'}>
      {members.error && <ErrorBox error={members.error} onRetry={members.reload} />}
      {!members.data && !members.error && <Spinner />}
      {members.data && (
        <table className="dtable">
          <tbody>
            {members.data.map((m) => (
              <tr key={m.userId}>
                <td style={{ width: 40 }}>
                  <span className="avatar">{initials(m.displayName ?? m.username)}</span>
                </td>
                <td>
                  <strong>{m.displayName ?? m.username}</strong> <span className="muted">@{m.username}</span>
                  {m.userId === me.userId && <span className="badge" style={{ marginLeft: 8 }}>you</span>}
                </td>
                <td>
                  <span className={`badge ${m.role === 'OWNER' ? 'badge-accent' : ''}`}>{m.role === 'OWNER' ? 'Owner' : 'Editor'}</span>
                </td>
                <td className="right">
                  {owner && m.userId !== me.userId && (
                    <button
                      className="btn btn-danger btn-sm"
                      onClick={() => mutate('remove_budget_member', { budgetId, userId: m.userId }).then(() => toast.info(`Removed ${m.username}`), toast.error)}
                    >
                      Remove
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {owner && (
        <form
          className="row-wrap"
          onSubmit={(e) => {
            e.preventDefault();
            if (!username.trim()) return;
            mutate('share_budget', { budgetId, username: username.trim(), role }).then(() => {
              toast.info(`Shared with ${username.trim()}`);
              setUsername('');
            }, toast.error);
          }}
        >
          <input className="input" style={{ maxWidth: 260 }} placeholder="Kanidm username" aria-label="Username to share with" value={username} onChange={(e) => setUsername(e.target.value)} />
          <select className="select" style={{ width: 130 }} value={role} onChange={(e) => setRole(e.target.value as Role)} aria-label="Role">
            <option value="EDITOR">Editor</option>
            <option value="OWNER">Owner</option>
          </select>
          <button className="btn btn-primary" disabled={!username.trim()}>
            Share
          </button>
          <span className="field-hint" style={{ flexBasis: '100%' }}>
            They need to have signed in to YNAY at least once.
          </span>
        </form>
      )}
    </Section>
  );
}

function DangerZone() {
  const { budget, budgetId } = useBudget();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [confirm, setConfirm] = useState('');
  if (budget.role !== 'OWNER') return null;
  return (
    <Section title="Delete budget" description="Permanently deletes this budget with all accounts, transactions and history for every member.">
      <div>
        <button className="btn btn-danger" onClick={() => setOpen(true)}>
          <Icon name="trash" size={16} /> Delete this budget…
        </button>
      </div>
      {open && (
        <FormModal
          title={`Delete “${budget.name}”?`}
          submitLabel="Delete budget forever"
          danger
          disabled={confirm !== budget.name}
          onClose={() => setOpen(false)}
          onSubmit={async () => {
            await call('delete_budget', { budgetId });
            navigate('/');
            void invalidateAll();
          }}
        >
          <p className="small">This cannot be undone. Type the budget name to confirm.</p>
          <input className="input" value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder={budget.name} aria-label="Budget name" />
        </FormModal>
      )}
    </Section>
  );
}
