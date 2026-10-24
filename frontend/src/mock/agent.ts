// A scripted stand-in for the import agent: it pulls "date, payee, amount" lines out of the last
// user message and imports them through the same mock actions the real agent would call.
import type { Account, AgentChatRequest, AgentChatResponse, AgentToolCall } from '../api/types';
import { parseMoney } from '../lib/money';
import type { Handlers } from './actions';

const DATE = /(\d{4}-\d{2}-\d{2}|\d{1,2}[./]\d{1,2}[./]\d{2,4})/;

function isoDate(raw: string): string | null {
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return raw;
  const m = raw.match(/^(\d{1,2})[./](\d{1,2})[./](\d{2,4})$/);
  if (!m) return null;
  const [, a, b, y] = m as unknown as [string, string, string, string];
  const year = y.length === 2 ? `20${y}` : y;
  // Slash = US month/day, dot = European day.month.
  const [month, day] = raw.includes('/') ? [a, b] : [b, a];
  return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
}

export function parseStatement(text: string) {
  const lines: { date: string; amount: number; payeeName: string }[] = [];
  for (const line of text.split(/\r?\n/)) {
    const dm = line.match(DATE);
    if (!dm) continue;
    const date = isoDate(dm[1]!);
    if (!date) continue;
    const rest = line.replace(dm[0], ' ');
    const amounts = [...rest.matchAll(/(?:^|[\s,;\t"])(-?\(?[$€£]?\s?-?\d[\d.,]*\)?)(?=$|[\s,;\t"])/g)].map((x) => x[1]!);
    const rawAmount = amounts.at(-1);
    if (!rawAmount) continue;
    const amount = parseMoney(rawAmount);
    if (amount === null || amount === 0) continue;
    const payeeName =
      rest
        .replace(rawAmount, ' ')
        .split(/[,;\t"]/)
        .map((x) => x.trim())
        .filter((x) => x && !/^-?[\d.,$€£()]+$/.test(x))[0] ?? 'Unknown payee';
    lines.push({ date, amount, payeeName });
  }
  return lines;
}

export function mockAgentChat(h: Handlers, req: AgentChatRequest): AgentChatResponse {
  const last = [...req.messages].reverse().find((m) => m.role === 'user')?.content ?? '';
  const toolCalls: AgentToolCall[] = [];
  const tool = <T>(name: string, input: object): T => {
    try {
      const output = h[name]!(input) as T;
      toolCalls.push({ name, input, output, isError: false });
      return output;
    } catch (e) {
      toolCalls.push({ name, input, output: { error: (e as Error).message }, isError: true });
      throw e;
    }
  };

  const accounts = tool<Account[]>('list_accounts', { budgetId: req.budgetId });
  const parsed = parseStatement(last);
  if (parsed.length === 0) {
    return {
      reply:
        "I couldn't find any transactions in that message. Paste a bank statement or CSV export " +
        '(one transaction per line with a date, payee and amount) and tell me which account it belongs to. ' +
        `Your accounts: ${accounts.map((a) => a.name).join(', ')}.`,
      toolCalls,
    };
  }
  const named = accounts.find((a) => last.toLowerCase().includes(a.name.toLowerCase()));
  const account = named ?? accounts.find((a) => a.type === 'CHECKING') ?? accounts[0];
  if (!account) return { reply: 'This budget has no accounts yet. Create one first.', toolCalls };

  const result = tool<{ imported: number; duplicates: number }>('import_transactions', {
    budgetId: req.budgetId,
    accountId: account.id,
    transactions: parsed,
  });
  const dupes = result.duplicates ? ` ${result.duplicates} were already imported and skipped.` : '';
  return {
    reply:
      `I imported ${result.imported} transaction${result.imported === 1 ? '' : 's'} into **${account.name}**` +
      `${named ? '' : ' (you did not name an account, so I picked it)'}.${dupes} ` +
      'They are unapproved, so please review the categories in the account register and approve them.',
    toolCalls,
  };
}
