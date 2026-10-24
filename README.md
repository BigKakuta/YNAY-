# YNAY

You don't need a budget app subscription. YNAY is a self-hosted, zero-based budgeting app in the
spirit of YNAB, built for the homelab:

- **MCP-native**: every action in the app is also an MCP tool, so any MCP client (Claude Code,
  Claude Desktop, ...) can manage your budget.
- **Import by agent**: no bank-statement parsers or bank APIs. Paste a statement or CSV into the
  built-in import assistant (or use the bundled Claude Code agent) and it creates the transactions
  through MCP.
- **Kanidm SSO**: login is OIDC only.
- Spring Boot (Java 25) + Postgres + React, packaged as Docker images built with a Nix flake.

## Docs

- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md): run it in your homelab (Docker image built by Nix, or the NixOS module)
- [docs/KANIDM.md](docs/KANIDM.md): set up the Kanidm OAuth2 client
- [docs/MCP.md](docs/MCP.md): connect Claude Code, Claude Desktop or any MCP client
- [docs/IMPORT_AGENT.md](docs/IMPORT_AGENT.md): import transactions with the agent
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): design, actions, budgeting rules

## End-to-end smoke tests

`e2e/` drives the whole stack (Postgres, a throwaway Kanidm, the jar with the SPA, MCP and the
import assistant against a scripted fake LLM): `node e2e/ui-smoke.mjs` walks through the UI with
Playwright and checks the budget math, `e2e/mcp-smoke.sh` acts as an MCP client. See
[e2e/README.md](e2e/README.md) for the setup.
