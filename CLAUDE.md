# YNAY

Self-hosted YNAB-style budgeting app. Read `docs/ARCHITECTURE.md` first: it is the contract for
actions, DTOs, budgeting math, HTTP surface and configuration.

## Build & test

All tooling comes from the Nix dev shell (JDK 25, Gradle 9, Node 22, Postgres 17):

```sh
nix develop -c bash -c 'cd backend && ./gradlew test'      # backend tests (need Docker for Testcontainers)
nix develop -c bash -c 'cd frontend && npm ci && npm test && npm run build'
nix build .#dockerImage                                     # app image (see docs/DEPLOYMENT.md)
```

## Rules

- Business operations are `@McpAction` methods only. Never add ad-hoc REST controllers for domain data.
- Amounts are long milliunits; months are `YearMonth`; dates `LocalDate`.
- SQL via `JdbcClient`; schema via Flyway, using the version range for your area (see ARCHITECTURE.md).
- Jackson 3 (`tools.jackson.*`) — Spring Boot 4.
- Every budget-scoped action calls `BudgetAccess.requireMember(budgetId)` first.
