# YNAY architecture & contracts

YNAY ("You Need A You-know-what") is a self-hosted, zero-based budgeting app in the spirit of YNAB.
It runs in a homelab: one app container plus Postgres, with login through Kanidm (OIDC).

```
┌──────────────┐  session cookie   ┌──────────────────────────────────────────┐
│ React SPA    │ ────────────────▶ │ Spring Boot (Java 25)                    │
│ (Vite, TS)   │ POST /api/actions │                                          │
└──────────────┘                   │  ActionRegistry ◀── @McpAction methods   │
┌──────────────┐  Bearer token     │     ▲         ▲                          │──▶ Postgres
│ MCP client   │ ────────────────▶ │  /mcp      /api/actions   /api/agent     │
│ (Claude etc) │  JSON-RPC         │  (MCP)     (web)          (import agent) │
└──────────────┘                   └──────────────────────────────────────────┘
                                        │ OIDC (auth code + PKCE, ES256)
                                        ▼
                                     Kanidm
```

## Core idea: every action is an MCP tool

Business operations are written once, as `@McpAction` methods (see
`backend/src/main/java/io/ynay/action/McpAction.java`). An `ActionRegistry` discovers them at startup,
derives a JSON Schema for the input record, and exposes each one:

* as an MCP tool on `/mcp` (Streamable HTTP transport, stateless, JSON responses), and
* as `POST /api/actions/{name}` for the React frontend.

The web UI therefore uses exactly the same operations an AI agent sees. There is no separate REST
API for domain data. New features are added by writing a new `@McpAction` method; nothing else.

### Action method rules

```java
@Component
class AccountActions {
    public record CreateAccountInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Display name") String name,
            @ActionParam("Account type") AccountType type,
            @ActionParam(value = "Opening balance in milliunits", required = false) Long startingBalance) {}

    @McpAction(name = "create_account", description = "Create an account in a budget ...")
    public AccountDto createAccount(CreateAccountInput in) { ... }
}
```

* One parameter that is a `record` (or no parameter). Return a JSON-serializable value.
* Supported input component types: `String`, `UUID`, `boolean/Boolean`, `int/Integer`, `long/Long`,
  `LocalDate` (`"2026-10-24"`), `YearMonth` (`"2026-10"`), enums (by name), `List<T>` of these or of
  nested records, nested records.
* `@ActionParam(required = false)` for optional fields; they arrive as `null`.
* Throw `ActionException` (`notFound`, `invalid`, `forbidden`, `conflict`) for user-facing errors.
* Budget-scoped actions take `budgetId` and must call `BudgetAccess.requireMember(budgetId)` first.
  Report "not found" rather than "forbidden" for budgets the user can't see (no existence leak).
* Get the caller with `CurrentUser.id()`.
* Set `readOnly = true` on queries, `destructive = true` on deletes.
* Actions run inside a transaction opened by the registry; don't manage connections yourself.

### Conventions

* JSON is camelCase. Ids are UUID strings. **Amounts are integer milliunits** (`-12340` = −12.34),
  negative = outflow. Dates are ISO `YYYY-MM-DD`; months are `YYYY-MM`.
* Data access is plain SQL through Spring's `JdbcClient`. No JPA. Schema changes go through Flyway
  (`backend/src/main/resources/db/migration`). `V1__init.sql` is the shared baseline; add `V2__...`
  etc. Coordinate version numbers by area: framework `V1xx`, security `V2xx`, ledger `V3xx`,
  budgeting `V4xx`, agent `V5xx` (e.g. `V301__payee_index.sql`), so parallel work never collides.
* Spring Boot 4.1 / Spring Framework 7 / Spring Security 7 / **Jackson 3** (`tools.jackson.*`
  packages, not `com.fasterxml.jackson.databind`; annotations are still `com.fasterxml.jackson.annotation`).
* Java packages: `io.ynay.action` (framework), `io.ynay.mcp`, `io.ynay.security`, `io.ynay.budget`,
  `io.ynay.account`, `io.ynay.category`, `io.ynay.payee`, `io.ynay.transaction`, `io.ynay.month`,
  `io.ynay.report`, `io.ynay.agent`.
* Tests: extend `io.ynay.support.IntegrationTest` (Postgres Testcontainer, needs Docker) and use
  `loginAsNewUser()` to run actions as a user. Call action beans directly in domain tests.

## Domain model and budgeting rules

Tables are in `V1__init.sql`. Key rules (YNAB semantics, simplified where noted):

* **Budget** has members (`OWNER`, `EDITOR`). Creating a budget makes the creator `OWNER` and creates:
  * a hidden group `system_kind = INTERNAL` named "Internal" containing the category
    "Inflow: Ready to Assign" (`system_kind = READY_TO_ASSIGN`);
  * a group "Credit Card Payments" (`system_kind = CREDIT_CARD_PAYMENTS`);
  * unless `withDefaultCategories = false`, starter groups/categories
    (Bills: Rent/Mortgage, Utilities, Phone, Internet, Insurance; Needs: Groceries, Transportation,
    Medical; Wants: Dining Out, Entertainment, Shopping; Savings: Emergency Fund, Vacation).
* **Accounts** are on-budget (`CHECKING, SAVINGS, CASH, CREDIT_CARD, LINE_OF_CREDIT`) or tracking
  (`ASSET, LIABILITY, MORTGAGE, LOAN, OTHER`); `onBudget` defaults by type. Creating an on-budget
  `CREDIT_CARD`/`LINE_OF_CREDIT` account also creates its payment category in the Credit Card
  Payments group (`system_kind = CREDIT_CARD_PAYMENT`, `linked_account_id`). Every account gets a
  transfer payee named `Transfer : <account name>` (`payee.transfer_account_id`).
* **Starting balance**: a transaction with payee "Starting Balance". On a non-credit on-budget
  account it is categorized to Ready to Assign. On credit/tracking accounts it is uncategorized
  and has no budget effect.
* **Transactions** live in `txn`; a split transaction has `is_split = true`, `category_id = null`
  and lines in `txn_split` whose amounts sum to the parent amount.
  **Transfers** are two linked rows (`transfer_account_id` + `transfer_transaction_id` on both
  sides, amounts negated). Transfers between two on-budget accounts carry no category. A transfer
  from an on-budget account to a tracking account needs a category on the on-budget side.
* **Import**: `import_transactions` deduplicates on `(account_id, import_id)`. When the caller
  gives no `importId`, the server generates `YNAY:<amount>:<date>:<occurrence>` (like YNAB's
  `YNAB:` ids) so the same statement imported twice doesn't duplicate.
  Imported transactions default to `approved = false` so the user reviews them.

### Month math (`get_month_budget`)

Only transactions on **on-budget** accounts affect the budget. For category `c` and month `m`:

* `activity(c,m)` = sum of non-split txn amounts with `category_id = c` + split line amounts with
  `category_id = c`, dated within `m`.
* Credit card payment category `P` for on-budget credit account `A`, additionally:
  * every categorized txn/split line on `A` with category `c ≠ P` (and `c` not Ready to Assign)
    adds `-amount` to `activity(P,m)` (spending 50 on the card moves 50 into P);
  * every transfer *into* `A` from an on-budget account (a payment, `+X` on A's side) adds `-X`
    to `activity(P,m)`.
* `assigned(c,m)` = `month_assignment.assigned` (0 if no row).
* `carryover(c,m)` = `max(available(c,m-1), 0)`. Overspending does not roll over in the category.
* `available(c,m)` = `carryover + assigned + activity`.
* `overspent(m)` = Σ over non-RTA categories of `max(-available(c,m), 0)`, plus
  `max(-uncategorized(m), 0)` where `uncategorized(m)` is the sum of on-budget, non-transfer,
  non-split txns with no category.
* `income(m)` = activity of the Ready to Assign category in `m`.
* `readyToAssign(m)` = Σ_{k ≤ m} income(k) − Σ_{all k} assigned(k) − Σ_{k < m} overspent(k).
  (Future assignments reduce RTA immediately, as in YNAB; overspending hits RTA the next month.)

### Targets

`category.target_type`, `target_amount`, `target_date`. `targetNeeded(c,m)` (still to assign in m):

* `MONTHLY_SAVINGS` (assign X every month): `max(X − assigned, 0)`
* `MONTHLY_SPENDING` (have X available for spending each month): `max(X − (carryover + assigned), 0)`
* `TARGET_BALANCE` (save up to X): `max(X − (carryover + assigned), 0)`
* `TARGET_BALANCE_BY_DATE` (X by month T): with `r` = months from m to T inclusive (min 1):
  `max(ceil((X − carryover) / r) − assigned, 0)`; 0 after T.

## Action catalogue (the API)

Owner tags show which work stream implements each action. All take `budgetId` unless noted.
DTO shapes are in the next section.

**Security (`io.ynay.security`)**
| action | input | output |
|---|---|---|
| `whoami` | – | `Me` |
| `list_api_tokens` (mcp=false) | – | `ApiToken[]` |
| `create_api_token` (mcp=false) | `name, expiresInDays?` | `ApiToken & {token}` (plaintext shown once) |
| `revoke_api_token` (mcp=false) | `tokenId` | `{revoked: true}` |

**Ledger (`budget`, `account`, `category`, `payee`, `transaction`)**
| action | input | output |
|---|---|---|
| `list_budgets` | – (no budgetId) | `Budget[]` |
| `get_budget` | `budgetId` | `Budget` |
| `create_budget` | `name, currencyCode?="USD", withDefaultCategories?=true` | `Budget` |
| `update_budget` | `budgetId, name?, currencyCode?` | `Budget` |
| `delete_budget` (owner) | `budgetId` | `{deleted: true}` |
| `list_budget_members` | `budgetId` | `Member[]` |
| `share_budget` (owner) | `budgetId, username, role?="EDITOR"` | `Member[]` |
| `remove_budget_member` (owner) | `budgetId, userId` | `Member[]` |
| `list_accounts` | `budgetId, includeClosed?=false` | `Account[]` |
| `create_account` | `budgetId, name, type, onBudget?, startingBalance?=0, startingDate?=today, note?` | `Account` |
| `update_account` | `budgetId, accountId, name?, note?, closed?` | `Account` |
| `delete_account` | `budgetId, accountId` | `{deleted: true}` |
| `list_categories` | `budgetId, includeHidden?=false` | `CategoryGroup[]` (incl. system groups; INTERNAL group always included so the RTA category id is discoverable) |
| `create_category_group` | `budgetId, name` | `CategoryGroup` |
| `update_category_group` | `budgetId, groupId, name?, hidden?, sortOrder?` | `CategoryGroup` |
| `delete_category_group` | `budgetId, groupId, reassignToCategoryId?` | `{deleted: true}` |
| `create_category` | `budgetId, groupId, name, note?` | `Category` |
| `update_category` | `budgetId, categoryId, name?, groupId?, hidden?, note?, sortOrder?` | `Category` |
| `delete_category` | `budgetId, categoryId, reassignToCategoryId?` | `{deleted: true}` |
| `list_payees` | `budgetId` | `Payee[]` |
| `update_payee` | `budgetId, payeeId, name` | `Payee` |
| `merge_payees` | `budgetId, sourcePayeeIds[], targetPayeeId` | `Payee` |
| `list_transactions` | `budgetId, accountId?, categoryId?, payeeId?, sinceDate?, untilDate?, search?, unapprovedOnly?, limit?=200, offset?=0` | `{transactions: Transaction[], total}` (newest first) |
| `get_transaction` | `budgetId, transactionId` | `Transaction` |
| `create_transaction` | `budgetId, accountId, date, amount, payeeId?, payeeName?, categoryId?, memo?, cleared?, approved?=true, flagColor?, transferAccountId?, splits?: SplitInput[], importId?` | `Transaction` |
| `update_transaction` | `budgetId, transactionId` + any create field | `Transaction` |
| `delete_transaction` | `budgetId, transactionId` | `{deleted: true}` |
| `import_transactions` | `budgetId, accountId, transactions: ImportLine[], approved?=false` | `{imported, duplicates, transactionIds[], duplicateImportIds[]}` |
| `approve_transactions` | `budgetId, transactionIds[]` | `{approved: n}` |
| `set_transactions_cleared` | `budgetId, transactionIds[], cleared` | `{updated: n}` |
| `reconcile_account` | `budgetId, accountId, statementBalance, statementDate?=today` | `{reconciledCount, adjustmentTransactionId?}` |

`SplitInput = {amount, categoryId?, payeeName?, memo?}`;
`ImportLine = {date, amount, payeeName?, memo?, categoryId?, importId?, cleared?}`.
When a payee is given by name and doesn't exist, it is created. When `categoryId` is omitted on
create and the payee was used before, the payee's most recent category is used (YNAB-style
memorized category) unless the txn is a transfer.

**Budgeting (`month`, `report`)**
| action | input | output |
|---|---|---|
| `get_month_budget` | `budgetId, month` | `MonthBudget` |
| `list_month_summaries` | `budgetId, fromMonth, toMonth` | `MonthSummary[]` |
| `assign_to_category` | `budgetId, month, categoryId, assigned` (absolute) | `CategoryMonth` |
| `move_money` | `budgetId, month, amount, fromCategoryId?, toCategoryId?` (null = Ready to Assign) | `MonthBudget` |
| `assign_underfunded` | `budgetId, month` | `MonthBudget` |
| `set_category_target` | `budgetId, categoryId, type, amount, targetDate?` | `Category` |
| `clear_category_target` | `budgetId, categoryId` | `Category` |
| `report_spending_by_category` | `budgetId, fromMonth, toMonth` | `{rows: [{categoryId, categoryName, groupName, total}], total}` |
| `report_income_vs_expense` | `budgetId, fromMonth, toMonth` | `[{month, income, expense, net}]` |
| `report_net_worth` | `budgetId, fromMonth, toMonth` | `[{month, assets, liabilities, netWorth}]` |

## DTO shapes (TypeScript notation)

```ts
type Me = { userId: string; username: string; displayName: string | null; email: string | null };
type ApiToken = { id: string; name: string; tokenPrefix: string; createdAt: string;
                  lastUsedAt: string | null; expiresAt: string | null };
type Budget = { id: string; name: string; currencyCode: string; role: "OWNER" | "EDITOR"; createdAt: string };
type Member = { userId: string; username: string; displayName: string | null; role: "OWNER" | "EDITOR" };
type AccountType = "CHECKING" | "SAVINGS" | "CASH" | "CREDIT_CARD" | "LINE_OF_CREDIT"
                 | "ASSET" | "LIABILITY" | "MORTGAGE" | "LOAN" | "OTHER";
type Account = { id: string; budgetId: string; name: string; type: AccountType; onBudget: boolean;
                 closed: boolean; note: string | null; sortOrder: number;
                 balance: number; clearedBalance: number; unclearedBalance: number };
type Target = { type: "MONTHLY_SAVINGS" | "MONTHLY_SPENDING" | "TARGET_BALANCE" | "TARGET_BALANCE_BY_DATE";
                amount: number; date: string | null };
type Category = { id: string; groupId: string; name: string; hidden: boolean; note: string | null;
                  sortOrder: number; systemKind: "READY_TO_ASSIGN" | "CREDIT_CARD_PAYMENT" | null;
                  linkedAccountId: string | null; target: Target | null };
type CategoryGroup = { id: string; name: string; hidden: boolean; sortOrder: number;
                       systemKind: "INTERNAL" | "CREDIT_CARD_PAYMENTS" | null; categories: Category[] };
type Payee = { id: string; name: string; transferAccountId: string | null };
type Cleared = "UNCLEARED" | "CLEARED" | "RECONCILED";
type Split = { id: string; amount: number; categoryId: string | null; categoryName: string | null;
               payeeId: string | null; payeeName: string | null; memo: string | null };
type Transaction = { id: string; budgetId: string; accountId: string; accountName: string; date: string;
                     amount: number; payeeId: string | null; payeeName: string | null;
                     categoryId: string | null; categoryName: string | null; memo: string | null;
                     cleared: Cleared; approved: boolean; flagColor: string | null;
                     transferAccountId: string | null; transferTransactionId: string | null;
                     importId: string | null; splits: Split[] };
type CategoryMonth = { categoryId: string; name: string; hidden: boolean; systemKind: string | null;
                       linkedAccountId: string | null; carryover: number; assigned: number;
                       activity: number; available: number; target: Target | null; targetNeeded: number };
type GroupMonth = { groupId: string; name: string; hidden: boolean; systemKind: string | null;
                    assigned: number; activity: number; available: number; categories: CategoryMonth[] };
type MonthBudget = { month: string; currencyCode: string; readyToAssign: number; income: number;
                     assigned: number; activity: number; available: number;
                     overspentLastMonth: number; uncategorized: number; groups: GroupMonth[] };
                     // groups excludes the INTERNAL group; totals exclude Ready to Assign
type MonthSummary = { month: string; income: number; assigned: number; activity: number; readyToAssign: number };
```

## HTTP surface

| path | auth | purpose |
|---|---|---|
| `GET /` and static assets | public | React SPA (bundled into the jar under `static/`) |
| `GET /api/me` | session | `Me`, or 401 → SPA shows "Sign in with Kanidm" |
| `GET /oauth2/authorization/kanidm` | public | starts OIDC login |
| `POST /logout` | session + CSRF | logout; 302 to `/`, or with `Accept: application/json` → `200 {redirectUrl}` (see docs/KANIDM.md) |
| `GET /api/actions` | session | action catalogue `[{name, description, inputSchema, readOnly, destructive, idempotent, mcp}]` |
| `POST /api/actions/{name}` | session + CSRF | invoke action; body = input JSON; 200 = output JSON; error = `{"error": {"code", "message"}}` with the code's HTTP status |
| `POST /mcp` | Bearer (API token `ynay_...` or Kanidm access token) | MCP Streamable HTTP (stateless, `application/json` responses) |
| `GET /.well-known/oauth-protected-resource` | public | RFC 9728 metadata pointing at Kanidm |
| `GET /api/agent/status` | session | `{enabled, provider, model}` |
| `POST /api/agent/chat` | session + CSRF | import agent turn (see below) |
| `GET /actuator/health` | public | health check |

CSRF: Spring Security cookie-based token. The SPA reads cookie `XSRF-TOKEN` and sends header
`X-XSRF-TOKEN` on every non-GET request.

### Import agent contract

`POST /api/agent/chat`
```ts
request  = { budgetId: string; messages: { role: "user" | "assistant"; content: string }[] }
response = { reply: string;                     // assistant's final text for this turn
             toolCalls: { name: string; input: unknown; output: unknown; isError: boolean }[] }
```
The client keeps the history (stateless server) and appends `{role:"assistant", content: reply}`.
The agent uses the same action registry (in-process, as the logged-in user) and is restricted to
the given budget.

## Configuration (environment variables)

| var | default | meaning |
|---|---|---|
| `YNAY_DB_URL` | `jdbc:postgresql://localhost:5432/ynay` | JDBC URL |
| `YNAY_DB_USER` / `YNAY_DB_PASSWORD` | `ynay` / `ynay` | DB credentials |
| `YNAY_PORT` | `8080` | HTTP port |
| `YNAY_PUBLIC_URL` | `http://localhost:8080` | external base URL (OIDC redirect, MCP metadata) |
| `YNAY_KANIDM_URL` | – | Kanidm origin, e.g. `https://idm.example.com` |
| `YNAY_KANIDM_CLIENT_ID` | `ynay` | OAuth2 client name in Kanidm |
| `YNAY_KANIDM_CLIENT_SECRET` | – | client secret (`kanidm system oauth2 show-basic-secret ynay`) |
| `YNAY_KANIDM_ID_TOKEN_ALG` | `ES256` | ID token JWS algorithm (`RS256` if the Kanidm client uses legacy crypto) |
| `YNAY_MCP_INTROSPECTION` | `false` | also accept Kanidm access tokens on `/mcp` via RFC 7662 introspection |
| `YNAY_MCP_TOKEN_AUDIENCES` | client id | comma separated Kanidm client ids whose access tokens `/mcp` accepts |
| `YNAY_AGENT_PROVIDER` | `anthropic` | `anthropic` or `openai` (any OpenAI-compatible server: Ollama, llama.cpp, vLLM) |
| `YNAY_AGENT_API_KEY` | – | API key; agent is disabled when unset (except `openai` with a local base URL) |
| `YNAY_AGENT_MODEL` | provider default | model id |
| `YNAY_AGENT_BASE_URL` | provider default | override API base URL |

## Repository layout

```
flake.nix, flake.lock    Nix flake: packages (backend, frontend, ynay, dockerImage), devShell, NixOS module
nix/                     Nix expressions (backend.nix, frontend.nix, docker.nix, module.nix, deps.json)
backend/                 Spring Boot app (Gradle, Kotlin DSL, wrapper)
frontend/                React + Vite + TypeScript SPA
deploy/                  docker-compose.yml, .env.example, Kanidm setup scripts
docs/                    this file, deployment & MCP guides
.claude/agents/          Claude Code import agent definition
```
