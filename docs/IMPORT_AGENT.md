# Importing transactions with an agent

YNAY has no bank-statement parsers and no bank connections. Instead, a language model reads
whatever you have (a CSV export, statement text copied from online banking or a PDF, a photo of a
receipt transcribed by you, a list of notes) and creates transactions through YNAY's actions, the
same ones the web UI and MCP clients use. There are two ways to do that:

| | In-app import assistant | External agent over MCP |
|---|---|---|
| where | **Import** page in the YNAY web UI | Claude Code (or any MCP client) on your machine |
| LLM | configured by the YNAY admin (Anthropic or a local OpenAI-compatible server) | whatever your client uses |
| input | text you paste | local files (CSV, PDF, ...) and pasted text |
| setup | environment variables on the server | an API token + `claude mcp add` |

Either way the agent:

1. looks up your accounts, categories and payees;
2. asks which account to use if the statement doesn't make it obvious;
3. converts amounts to milliunits (outflows negative) and dates to `YYYY-MM-DD`;
4. calls `import_transactions` in batches, with stable import ids taken from the statement where
   it has them, so importing the same statement twice does not create duplicates;
5. leaves transactions **unapproved** and only categorizes when it is confident (memorized payee
   categories are applied automatically);
6. summarizes what it imported, what was skipped as a duplicate and what it couldn't parse.

It never invents transactions. Always review imported transactions in the account register
(unapproved transactions are highlighted) before relying on them.

## In-app import assistant

### Configuration

The assistant is enabled when the server has an LLM configured:

| variable | default | meaning |
|---|---|---|
| `YNAY_AGENT_PROVIDER` | `anthropic` | `anthropic` (Claude API) or `openai` (any OpenAI-compatible Chat Completions server) |
| `YNAY_AGENT_API_KEY` | – | API key. Required for `anthropic` and for `openai` without a custom base URL |
| `YNAY_AGENT_MODEL` | `claude-opus-5-5` for `anthropic`; none for `openai` | model id |
| `YNAY_AGENT_BASE_URL` | `https://api.anthropic.com` / `https://api.openai.com/v1` | API base URL (e.g. `http://ollama:11434/v1`) |
| `YNAY_AGENT_MAX_ITERATIONS` | `25` | maximum model calls per assistant turn |
| `YNAY_AGENT_MAX_TOKENS` | `16000` | maximum output tokens per model call |
| `YNAY_AGENT_EFFORT` | `medium` | Anthropic only: `output_config.effort` (`low` … `max`); empty to omit for models without effort support |
| `YNAY_AGENT_REFUSAL_FALLBACK` | `true` | Anthropic only: if a safety classifier declines a request, let the API retry it on Anthropic's recommended fallback model (supported models only) |
| `YNAY_AGENT_TIMEOUT` | `10m` | timeout of one model call |
| `YNAY_AGENT_ALLOW_DESTRUCTIVE` | `false` | also offer delete actions to the assistant |

`GET /api/agent/status` reports `{enabled, provider, model}` (plus a `reason` when disabled); the
Import page uses it to show whether the assistant is available.

#### Anthropic (Claude API)

```sh
YNAY_AGENT_PROVIDER=anthropic
YNAY_AGENT_API_KEY=sk-ant-...
# optional: a different model, e.g. a faster, cheaper one for routine imports
# YNAY_AGENT_MODEL=claude-sonnet-5-5
```

Create the key in the Claude Console. The tool definitions and instructions are sent with prompt
caching, so each step of a multi-step import re-reads them (and the pasted statement) from the
cache rather than paying for them again.

#### Ollama and other local servers

For a fully local setup, point the `openai` provider at any server that implements the OpenAI
Chat Completions API **with function calling**: Ollama, llama.cpp `llama-server` (start it with
`--jinja`), vLLM (with tool calling enabled), LM Studio, LocalAI.

```sh
YNAY_AGENT_PROVIDER=openai
YNAY_AGENT_BASE_URL=http://ollama:11434/v1
YNAY_AGENT_MODEL=qwen3:14b
# no API key needed for a local server; set one if your server requires it
```

Pick a model with tool support (in the Ollama library, models tagged **tools**, e.g. `qwen3`,
`llama3.1`, `mistral-small3.2`). Small models make more mistakes with signs, dates and ids;
14B parameters or more works noticeably better for imports. The tool catalogue plus a statement
easily exceeds Ollama's default context window, which then silently truncates the prompt: raise
it on the Ollama server, e.g. `OLLAMA_CONTEXT_LENGTH=32768`.

docker-compose example (next to the `ynay` service):

```yaml
  ollama:
    image: ollama/ollama
    environment:
      OLLAMA_CONTEXT_LENGTH: "32768"
    volumes:
      - ollama:/root/.ollama
  # then: docker compose exec ollama ollama pull qwen3:14b
```

### Using it

Open **Import**, pick the budget, paste the statement (or describe transactions in plain words:
"coffee 4.50 yesterday, groceries 82.10 on the 3rd, both from Checking") and send. The assistant
may ask a follow-up question, typically which account to use. Each turn can take a while for large
statements; the tool calls it made are listed under its reply.

Limits: one conversation is at most 200,000 characters (about 2,000-4,000 statement lines). Split
larger exports by month. A turn stops after `YNAY_AGENT_MAX_ITERATIONS` model calls; reply
"continue" to let it carry on.

A turn is one long HTTP request (`POST /api/agent/chat`). If YNAY sits behind a reverse proxy,
raise its read timeout to match `YNAY_AGENT_TIMEOUT` or more (nginx: `proxy_read_timeout 600s;`,
Traefik: `respondingTimeouts.readTimeout`), or large imports fail with a 504 from the proxy even
though the import keeps running on the server.

### Security model

- The assistant runs **as you**: every tool call goes through the same action code and
  authorization checks as the web UI, in your session.
- It is restricted to the budget the conversation was started for. Calls naming any other budget
  are rejected, budget-management actions (create/rename/delete/share budgets, remove members) are
  not offered, and delete actions are not offered unless `YNAY_AGENT_ALLOW_DESTRUCTIVE=true`.
- The server keeps no conversation state; the browser sends the history with each turn.

### Privacy

The text you paste, the conversation, and the data the assistant reads from your budget to do its
job (account, category and payee names, transactions it looks up) are sent to the configured LLM
provider. With `anthropic`, that is Anthropic's API, under your API account's data retention
terms. If that is not acceptable for your financial data, use a local model through the `openai`
provider: then nothing leaves your network. Remove account numbers and other details you don't
want to share before pasting; the assistant doesn't need them.

## External agents over MCP

Any MCP client can import, because `import_transactions` and the lookup actions are MCP tools.
This repository includes a ready-made Claude Code subagent, `.claude/agents/ynay-importer.md`,
with the same import rules as the in-app assistant. It can read local files, so you don't have to
paste anything.

1. Create an API token in **Settings → API tokens** and add the server (see
   `docs/MCP_CLIENTS.md`):

   ```sh
   claude mcp add --scope user --transport http ynay https://ynay.example.com/mcp \
     --header "Authorization: Bearer ynay_pat_..."
   ```

2. Install the subagent for your user (or run Claude Code inside this repository, where it is
   picked up automatically):

   ```sh
   mkdir -p ~/.claude/agents
   cp .claude/agents/ynay-importer.md ~/.claude/agents/
   ```

3. Ask Claude Code:

   > Use the ynay-importer agent to import ~/Downloads/statement.csv into Checking.

   > Import the transactions from ~/Downloads/visa-2026-10.pdf into my Visa account in the
   > "Household" budget.

The subagent may only use the `ynay` MCP tools and Read; deleting, sharing and merging tools are
withheld in its definition. It picks the budget with `list_budgets` (asking if you have several),
reads the file, imports in batches and reports a summary. Review the unapproved transactions in
YNAY afterwards.

Privacy works the same way: the file contents go to the model your Claude Code session uses.
