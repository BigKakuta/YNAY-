#!/usr/bin/env node
// A tiny scripted stand-in for an OpenAI-compatible Chat Completions server, so the in-app import
// assistant can be exercised end to end without an LLM. Start it, then run the backend with
//
//   YNAY_AGENT_PROVIDER=openai YNAY_AGENT_BASE_URL=http://localhost:4010/v1 YNAY_AGENT_MODEL=fake
//
// The "model" is a fixed script driven by the conversation it is sent (the server is stateless,
// like the real API):
//   1. after a user message      -> call list_accounts
//   2. after the list_accounts result -> call import_transactions for the CSV lines in the latest
//      user message ("date,payee,amount[,memo]", amounts in currency units, outflows negative),
//      into the account named in the message (the Import page's 'account "<name>"' prefix, or
//      "into <name>:"), else the first on-budget account
//   3. after the import result   -> a final summary message
//
// Every request is appended to $FAKE_OPENAI_LOG (JSON lines) if set, for inspection.
import http from 'node:http';
import fs from 'node:fs';

const PORT = Number(process.env.FAKE_OPENAI_PORT || 4010);
const LOG = process.env.FAKE_OPENAI_LOG;

function parseCsv(text) {
  const lines = [];
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    const m = /^(\d{4}-\d{2}-\d{2})\s*,\s*([^,]+?)\s*,\s*(-?\d+(?:\.\d{1,3})?)\s*(?:,\s*(.*))?$/.exec(line);
    if (!m) continue;
    const entry = { date: m[1], payeeName: m[2], amount: Math.round(Number(m[3]) * 1000) };
    if (m[4]) entry.memo = m[4];
    lines.push(entry);
  }
  return lines;
}

function lastUser(messages) {
  for (let i = messages.length - 1; i >= 0; i--) if (messages[i].role === 'user') return { msg: messages[i], index: i };
  return { msg: { content: '' }, index: -1 };
}

let callSeq = 0;
function toolCall(name, args) {
  return {
    finish_reason: 'tool_calls',
    message: {
      role: 'assistant',
      content: null,
      tool_calls: [{ id: `call_${++callSeq}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }],
    },
  };
}

function say(text) {
  return { finish_reason: 'stop', message: { role: 'assistant', content: text } };
}

function respond(body) {
  const messages = body.messages || [];
  const { msg: user, index } = lastUser(messages);
  const since = messages.slice(index + 1);
  // tool name for each tool_call id issued since the user message
  const names = {};
  for (const m of since) for (const tc of m.tool_calls || []) names[tc.id] = tc.function.name;
  const results = since.filter(m => m.role === 'tool').map(m => ({ name: names[m.tool_call_id], content: m.content }));
  const last = results[results.length - 1];

  if (!last) {
    const tools = (body.tools || []).map(t => t.function.name);
    if (!tools.includes('list_accounts') || !tools.includes('import_transactions')) {
      return say('I cannot import: the tools list_accounts / import_transactions were not offered.');
    }
    return toolCall('list_accounts', {});
  }
  if (last.name === 'list_accounts') {
    if (last.content.startsWith('ERROR')) return say('list_accounts failed: ' + last.content);
    let accounts = JSON.parse(last.content);
    if (!Array.isArray(accounts)) accounts = accounts.result || [];
    // The Import page prefixes 'Import into account "<name>" (id <uuid>).'; also accept "into <name>:".
    const wanted = /account "([^"]+)"/i.exec(user.content) || /into\s+([\w ]+?)\s*[:.\n]/i.exec(user.content);
    const account = (wanted && accounts.find(a => a.name.toLowerCase() === wanted[1].toLowerCase()))
      || accounts.find(a => a.onBudget && !a.closed);
    if (!account) return say('Which account should I import into? I found none.');
    const transactions = parseCsv(user.content);
    if (transactions.length === 0) return say('I could not find any CSV lines (date,payee,amount) to import.');
    return toolCall('import_transactions', { accountId: account.id, transactions });
  }
  if (last.name === 'import_transactions') {
    if (last.content.startsWith('ERROR')) return say('The import failed: ' + last.content);
    const r = JSON.parse(last.content);
    return say(`Imported ${r.imported} transaction(s); ${r.duplicates} duplicate(s) skipped. ` +
      'They are unapproved, please review them in the register.');
  }
  return say('Done.');
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url.endsWith('/models')) {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ object: 'list', data: [{ id: 'fake', object: 'model' }] }));
    return;
  }
  if (req.method !== 'POST' || !req.url.endsWith('/chat/completions')) {
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: { message: 'not found: ' + req.url } }));
    return;
  }
  let data = '';
  req.on('data', c => { data += c; });
  req.on('end', () => {
    let body;
    try {
      body = JSON.parse(data);
    } catch {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'invalid JSON' } }));
      return;
    }
    let choice;
    try {
      choice = respond(body);
    } catch (e) {
      choice = say('fake model crashed: ' + e.message);
    }
    if (LOG) fs.appendFileSync(LOG, JSON.stringify({ request: body, choice }) + '\n');
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      id: 'chatcmpl-fake-' + callSeq,
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: body.model || 'fake',
      choices: [{ index: 0, ...choice }],
      usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
    }));
  });
});

server.listen(PORT, '127.0.0.1', () => console.log(`fake OpenAI server on http://localhost:${PORT}/v1`));
