import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { agent } from '../api/client';
import { invalidateAll } from '../api/query';
import type { AgentStatus, AgentToolCall, ChatMessage } from '../api/types';
import { ErrorBox, Icon, PageLoading } from '../components/ui';
import { useBudget } from '../context';

export type ChatEntry = ChatMessage & { toolCalls?: AgentToolCall[]; error?: boolean };

const storageKey = (budgetId: string) => `ynay.agent.${budgetId}`;

function loadHistory(budgetId: string): ChatEntry[] {
  try {
    const raw = sessionStorage.getItem(storageKey(budgetId));
    return raw ? (JSON.parse(raw) as ChatEntry[]) : [];
  } catch {
    return [];
  }
}

function saveHistory(budgetId: string, entries: ChatEntry[]) {
  try {
    sessionStorage.setItem(storageKey(budgetId), JSON.stringify(entries));
  } catch {
    // storage full or unavailable: history just won't survive a reload
  }
}

/** Text with **bold**, `code` and line breaks; everything else is literal (no HTML injection). */
export function RichText({ text }: { text: string }) {
  const parts: ReactNode[] = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let i = 0;
  while ((m = re.exec(text))) {
    if (m.index > last) parts.push(text.slice(last, m.index));
    const tok = m[0];
    parts.push(tok.startsWith('**') ? <strong key={i++}>{tok.slice(2, -2)}</strong> : <code key={i++}>{tok.slice(1, -1)}</code>);
    last = m.index + tok.length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return <>{parts}</>;
}

export function ImportPage() {
  const { budgetId, accounts } = useBudget();
  const [status, setStatus] = useState<AgentStatus | null>(null);
  const [statusError, setStatusError] = useState<Error | null>(null);
  const [entries, setEntries] = useState<ChatEntry[]>(() => loadHistory(budgetId));
  const [draft, setDraft] = useState('');
  const [accountId, setAccountId] = useState('');
  const [sending, setSending] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    agent.status().then(setStatus, setStatusError);
  }, []);
  useEffect(() => {
    setEntries(loadHistory(budgetId));
  }, [budgetId]);
  useEffect(() => {
    saveHistory(budgetId, entries);
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [budgetId, entries, sending]);

  const openAccounts = accounts.filter((a) => !a.closed);

  async function send() {
    const text = draft.trim();
    if (!text || sending) return;
    const account = openAccounts.find((a) => a.id === accountId);
    const content = account ? `Import into account "${account.name}" (id ${account.id}).\n\n${text}` : text;
    const next: ChatEntry[] = [...entries.filter((e) => !e.error), { role: 'user', content }];
    setEntries(next);
    setDraft('');
    setSending(true);
    try {
      const res = await agent.chat({ budgetId, messages: next.map(({ role, content }) => ({ role, content })) });
      setEntries([...next, { role: 'assistant', content: res.reply, toolCalls: res.toolCalls }]);
      if (res.toolCalls.some((c) => !c.isError)) void invalidateAll();
    } catch (e) {
      setEntries([...next, { role: 'assistant', content: (e as Error).message, error: true }]);
    } finally {
      setSending(false);
    }
  }

  async function attach(file: File) {
    const text = await file.text();
    setDraft((d) => `${d ? d.trimEnd() + '\n\n' : ''}Contents of ${file.name}:\n\`\`\`\n${text.trimEnd()}\n\`\`\`\n`);
  }

  if (!status && !statusError) return <PageLoading />;

  return (
    <>
      <header className="page-header">
        <div className="stack" style={{ gap: 2 }}>
          <h1>Import assistant</h1>
          <span className="small muted">
            Paste a bank statement or CSV export. The assistant reads it and creates the transactions for you to review.
          </span>
        </div>
        <span className="spacer" />
        {status?.enabled && (
          <span className="badge">
            {status.provider}
            {status.model && ` · ${status.model}`}
          </span>
        )}
        {status?.enabled && entries.length > 0 && (
          <button className="btn btn-sm" onClick={() => setEntries([])} disabled={sending}>
            New conversation
          </button>
        )}
      </header>

      {statusError && (
        <div className="page-body page-scroll">
          <ErrorBox error={statusError} />
        </div>
      )}
      {status && !status.enabled && <AgentDisabled />}
      {status?.enabled && (
        <div className="chat-layout">
          <div className="chat-scroll" ref={scrollRef}>
            {entries.length === 0 && <ChatIntro />}
            {entries.map((e, i) => (
              <Message key={i} entry={e} />
            ))}
            {sending && (
              <div className="msg msg-assistant">
                <div className="msg-bubble typing" aria-label="Assistant is working">
                  <span />
                  <span />
                  <span />
                </div>
              </div>
            )}
          </div>
          <form
            className="composer"
            onSubmit={(e) => {
              e.preventDefault();
              void send();
            }}
          >
            <textarea
              className="textarea"
              aria-label="Message"
              placeholder="Paste statement text or CSV here, or describe what to import…"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                  e.preventDefault();
                  void send();
                }
              }}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                const f = e.dataTransfer.files[0];
                if (f) {
                  e.preventDefault();
                  void attach(f);
                }
              }}
            />
            <div className="row-wrap">
              <select className="select input-sm" style={{ width: 220 }} value={accountId} onChange={(e) => setAccountId(e.target.value)} aria-label="Import into account">
                <option value="">Account: let the assistant ask</option>
                {openAccounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    Account: {a.name}
                  </option>
                ))}
              </select>
              <input
                ref={fileRef}
                type="file"
                accept=".csv,.tsv,.txt,.qif,.ofx,.qfx,.json,text/*"
                hidden
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) void attach(f);
                  e.target.value = '';
                }}
              />
              <button type="button" className="btn btn-sm" onClick={() => fileRef.current?.click()}>
                <Icon name="file" size={14} /> Attach file
              </button>
              <span className="spacer" />
              <span className="xs muted">
                <kbd>Ctrl</kbd>+<kbd>Enter</kbd> to send
              </span>
              <button className="btn btn-primary btn-sm" disabled={!draft.trim() || sending}>
                <Icon name="send" size={14} /> Send
              </button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}

function ChatIntro() {
  return (
    <div className="card card-pad stack" style={{ maxWidth: 680, alignSelf: 'center' }}>
      <h3>How it works</h3>
      <ol className="small" style={{ margin: 0, paddingLeft: 18, display: 'grid', gap: 6 }}>
        <li>Pick the account the statement belongs to (or just say it in your message).</li>
        <li>Paste the statement text or CSV, or attach the exported file. Nothing is parsed in your browser — the assistant does it.</li>
        <li>The assistant imports the transactions as <strong>unapproved</strong>; duplicates from earlier imports are skipped.</li>
        <li>Review categories in the account register and approve them.</li>
      </ol>
    </div>
  );
}

function Message({ entry }: { entry: ChatEntry }) {
  const { budgetId, accounts } = useBudget();
  const imports = (entry.toolCalls ?? []).filter((c) => c.name === 'import_transactions' && !c.isError);
  return (
    <div className={`msg msg-${entry.role}`}>
      <div className="msg-bubble" style={entry.error ? { borderColor: 'var(--neg)', color: 'var(--neg)' } : undefined}>
        {entry.role === 'assistant' ? <RichText text={entry.content} /> : entry.content}
      </div>
      {entry.toolCalls && entry.toolCalls.length > 0 && (
        <div className="tool-calls">
          {entry.toolCalls.map((c, i) => (
            <ToolCall key={i} call={c} />
          ))}
        </div>
      )}
      {imports.map((c, i) => {
        const input = c.input as { accountId?: string } | null;
        const output = c.output as { imported?: number } | null;
        const account = accounts.find((a) => a.id === input?.accountId);
        if (!account) return null;
        return (
          <Link key={i} className="btn btn-success btn-sm" to={`/budgets/${budgetId}/accounts/${account.id}`}>
            Review {output?.imported ?? ''} imported transaction{output?.imported === 1 ? '' : 's'} in {account.name} →
          </Link>
        );
      })}
    </div>
  );
}

function ToolCall({ call }: { call: AgentToolCall }) {
  const fmt = (v: unknown) => (typeof v === 'string' ? v : JSON.stringify(v, null, 2));
  return (
    <details className={`tool-call ${call.isError ? 'error' : ''}`}>
      <summary>
        <code>{call.name}</code>
        {call.isError ? <span className="badge" style={{ color: 'var(--neg)' }}>error</span> : <span className="badge badge-pos">ok</span>}
      </summary>
      <div className="io-label">Input</div>
      <pre>{fmt(call.input)}</pre>
      <div className="io-label">{call.isError ? 'Error' : 'Output'}</div>
      <pre>{fmt(call.output)}</pre>
    </details>
  );
}

function AgentDisabled() {
  const { budgetId } = useBudget();
  return (
    <div className="page-body page-scroll">
      <div className="card card-pad stack" style={{ maxWidth: 760 }}>
        <h2>The import assistant isn’t configured</h2>
        <p className="small">
          The built-in assistant needs an LLM provider. Ask whoever runs this YNAY server to set these environment variables and
          restart it:
        </p>
        <pre className="codeblock">{`# Anthropic (default)
YNAY_AGENT_PROVIDER=anthropic
YNAY_AGENT_API_KEY=sk-ant-...
# optional: YNAY_AGENT_MODEL=<model id>

# …or any OpenAI-compatible server (Ollama, llama.cpp, vLLM)
YNAY_AGENT_PROVIDER=openai
YNAY_AGENT_BASE_URL=http://ollama:11434/v1
YNAY_AGENT_MODEL=<model id>`}</pre>
        <hr className="divider" />
        <h3>Or use your own MCP client</h3>
        <p className="small">
          Every YNAY action is also an MCP tool. Create an access token and connect Claude Code, Claude Desktop or another MCP
          client — then paste your statement there and ask it to import the transactions.
        </p>
        <div>
          <Link className="btn btn-primary" to={`/budgets/${budgetId}/settings#mcp`}>
            Set up MCP access
          </Link>
        </div>
      </div>
    </div>
  );
}
