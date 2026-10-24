# End-to-end smoke tests

These scripts drive the whole stack for real: Postgres, a throwaway Kanidm, the Spring Boot jar
with the React SPA bundled, the MCP endpoint and the import assistant (against a scripted fake
LLM). They complement the unit/integration tests; run them after changes that cross layers.

| script | what it checks |
|---|---|
| `ui-smoke.mjs` | Playwright, through the UI only: Kanidm login (real login form), create a budget, checking account with a starting balance and a credit card, assign money, register (regular, split, transfer to the card, card spending), edit (change/clear category, clear memo), import a CSV with the import assistant (twice: dedupe), approve, reconcile both accounts (one with an adjustment), the month numbers from the "Month math" in `docs/ARCHITECTURE.md` (this month and the next), reports, an MCP token in Settings, sign out. Fails on console errors and on any 4xx/5xx from `/api`. Screenshots go to `e2e/out/`. |
| `mcp-smoke.sh` | curl + jq as an MCP client: `initialize`, `notifications/initialized`, `tools/list` (token tools must be absent), `tools/call` (`list_budgets`, `create_account`, `import_transactions` of a CSV twice to prove dedupe, `get_month_budget`, ...), `Origin` check (403), 401 + `WWW-Authenticate`, `GET /mcp` 405. With `E2E_MCP_INSPECTOR=1` it also lists and calls tools with the official MCP Inspector CLI (`npx`). Creates and deletes its own budget. |
| `fake-openai.mjs` | A tiny OpenAI-compatible Chat Completions server that plays a fixed script (`list_accounts` → `import_transactions` of the CSV lines in the last user message → summary), so the import assistant runs without an LLM or API key. |

## Run

Needs Docker, Nix (for the dev shell and Kanidm), Node with Playwright and Chromium
(`npm i -g playwright && npx playwright install chromium` if you don't have them), curl and jq.
From the repository root:

```sh
# 1. Postgres and Kanidm
docker run -d --name ynay-pg -e POSTGRES_USER=ynay -e POSTGRES_PASSWORD=ynay -e POSTGRES_DB=ynay \
  -p 5432:5432 postgres:17-alpine
nix develop -c deploy/kanidm/dev-kanidm.sh start      # writes .kanidm-dev/ynay.env

# 2. the fake LLM for the import assistant (keep it running)
node e2e/fake-openai.mjs &                           # http://localhost:4010/v1

# 3. the app, with the SPA bundled into the jar
nix develop -c bash -c 'cd frontend && npm ci && npm run build && cd ../backend && ./gradlew bootJar'
set -a; source .kanidm-dev/ynay.env; set +a     # Kanidm client, truststore, test login
export YNAY_AGENT_PROVIDER=openai YNAY_AGENT_BASE_URL=http://localhost:4010/v1 YNAY_AGENT_MODEL=fake
nix develop -c java -jar backend/build/libs/ynay.jar &

# 4. the tests (ui-smoke writes the MCP token that mcp-smoke uses)
node e2e/ui-smoke.mjs
E2E_MCP_INSPECTOR=1 e2e/mcp-smoke.sh

# clean up
kill %1 %2; docker rm -f ynay-pg; deploy/kanidm/dev-kanidm.sh stop
```

`ui-smoke.mjs` creates a new budget named `E2E Household <timestamp>` on every run, so it can be
repeated against the same database. `E2E_HEADED=1` shows the browser; `E2E_OUT` changes the
screenshot directory. Without the fake LLM (or any configured agent) the import step falls back
to calling `import_transactions` directly and the assistant UI is not covered.

`mcp-smoke.sh` takes the token from `YNAY_TOKEN` or `e2e/out/mcp-token.txt`, and the server from
`YNAY_URL` (default `http://localhost:8080`), so it also works against a deployed instance:
`YNAY_URL=https://ynay.example.com YNAY_TOKEN=ynay_pat_... e2e/mcp-smoke.sh`.

`deploy/kanidm/e2e-login.js` is a lower-level check of the OIDC flow itself (PKCE, cookies, CSRF,
Kanidm access tokens on `/mcp`); see `docs/KANIDM.md`.
