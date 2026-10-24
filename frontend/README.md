# YNAY frontend

React 19 + TypeScript + Vite single-page app. Hand-written CSS (light/dark via
`prefers-color-scheme`), `react-router` for routing, no UI kit, no chart library.

The SPA talks to the backend only through the HTTP surface in [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md):

| what | how |
|---|---|
| session | `GET /api/me`; a 401 shows the login screen with a single **Sign in with Kanidm** link to `/oauth2/authorization/kanidm` |
| every domain operation | `POST /api/actions/{name}` through the typed `call()` client (`src/api/client.ts`); action names, inputs and DTOs live in `src/api/types.ts` |
| logout | `POST /logout` |
| import assistant | `GET /api/agent/status`, `POST /api/agent/chat` |

Every non-GET request sends `X-XSRF-TOKEN` from the `XSRF-TOKEN` cookie, with same-origin credentials. Errors
(`{"error": {"code", "message"}}`) become `ApiError(status, code, message)`.

## Scripts

```sh
npm ci
npm run dev:mock   # UI against the in-browser mock backend (no Java, no Postgres, no Kanidm)
npm run dev        # Vite on :5173, proxying /api /oauth2 /login /logout /mcp /.well-known to :8080
npm test           # Vitest (jsdom + Testing Library), non-interactive
npm run lint       # tsc --noEmit
npm run build      # → dist/ (bundled into the Spring Boot jar as static resources)
```

Inside the Nix dev shell: `nix develop -c bash -c 'cd frontend && npm ci && npm test && npm run build'`.

## Layout

```
src/api/        types.ts (DTOs + action catalogue), client.ts (fetch, CSRF, errors), query.ts (tiny SWR cache)
src/lib/        money.ts (milliunits ⇄ text, Intl formatting), dates.ts
src/components/ ui.tsx (Modal, Money, Icon…), inputs.tsx (AssignInput, PayeeInput, CategorySelect…), toast.tsx
src/pages/      one file per screen
src/mock/       in-browser mock backend (VITE_MOCK=1 only; tree-shaken from production builds)
```

Data fetching: `useQuery(action, input)` caches results by action + input; `mutate(action, input)` calls the
action and then refetches every mounted query, so screens never drift from the server's view.

Money: amounts are integer milliunits everywhere. `parseMoney` accepts `12.34`, `-5`, `1,234.50`, `1.234,50`,
`(12.00)`, `$ 12`; display goes through `Intl.NumberFormat(undefined, {style: 'currency', currency})`.

## Mock backend

`npm run dev:mock` sets `VITE_MOCK=1` (see `.env.mock`). `src/mock` replaces `fetch` for backend paths and
implements every action in memory, including the month math (carryover, overspending hitting next month's
Ready to Assign, credit card payment categories, targets), with sample data relative to the current month.
State is kept in `sessionStorage`. URL switches: `?mock-reset` (reseed), `?mock-agent=off` / `?mock-agent=on`
(simulate an unconfigured import agent). The mock agent extracts `date, payee, amount` lines and imports them.

## Routes

`/` budgets · `/settings` profile & MCP tokens · `/budgets/:id/budget/:month` · `/budgets/:id/accounts` (all) ·
`/budgets/:id/accounts/:accountId` · `/budgets/:id/reports` · `/budgets/:id/import` · `/budgets/:id/settings`.
The backend forwards unknown paths to `index.html`.

Keyboard: in the budget, <kbd>Enter</kbd> saves an Assigned cell and moves to the next one, <kbd>Esc</kbd> reverts,
<kbd>Alt</kbd>+<kbd>←</kbd>/<kbd>→</kbd> switches months. In the register, <kbd>Enter</kbd> saves the row,
<kbd>Esc</kbd> cancels, arrow keys pick payees. In the import assistant, <kbd>Ctrl</kbd>+<kbd>Enter</kbd> sends.

## Screenshots

Taken with Playwright against `npm run dev:mock`.

| | |
|---|---|
| Login ![](docs/screenshots/login.png) | Budgets ![](docs/screenshots/budgets.png) |
| Budget month ![](docs/screenshots/budget-month.png) | Category details & target ![](docs/screenshots/category-details.png) |
| Move money ![](docs/screenshots/move-money.png) | Dark mode ![](docs/screenshots/budget-month-dark.png) |
| Account register with unapproved imports ![](docs/screenshots/register.png) | Adding a transaction (payee autocomplete) ![](docs/screenshots/register-edit.png) |
| Split editor ![](docs/screenshots/split-editor.png) | Reconcile ![](docs/screenshots/reconcile.png) |
| All accounts ![](docs/screenshots/all-accounts.png) | Reports ![](docs/screenshots/reports.png) |
| Import assistant ![](docs/screenshots/import-assistant.png) | Import assistant not configured ![](docs/screenshots/import-disabled.png) |
| Settings ![](docs/screenshots/settings.png) | MCP token & client config ![](docs/screenshots/settings-mcp.png) |
| Tablet ![](docs/screenshots/tablet.png) | |
