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

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
