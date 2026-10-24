# Connecting MCP clients

Every YNAY action is an MCP tool on `POST /mcp` (Streamable HTTP, stateless, JSON responses). Any
MCP client that speaks the HTTP transport can manage a budget: list accounts, import transactions,
assign money, run reports. This page covers connecting clients. The protocol itself is described in
`docs/MCP.md` ("Protocol details").

Throughout, replace `https://ynay.example.com` with your `YNAY_PUBLIC_URL`.

## 1. Create an API token

MCP clients authenticate with a personal API token sent as `Authorization: Bearer <token>`:

1. Sign in to YNAY and open **Settings → API tokens**.
2. Click **Create token**, give it a name that identifies the client (e.g. `laptop claude code`),
   and optionally an expiry.
3. Copy the token (it starts with `ynay_`) right away. It is shown only once; YNAY stores just a
   hash of it.

The token acts as you: it can do everything you can in every budget you are a member of. Create
one token per client so you can revoke them one by one under **Settings → API tokens**. Token
management is deliberately not exposed over MCP.

If `YNAY_MCP_INTROSPECTION=true`, `/mcp` also accepts Kanidm access tokens, and clients that
support MCP OAuth can discover Kanidm through `/.well-known/oauth-protected-resource`. Personal
tokens are the simple, universal option and are what the examples below use.

## 2. Claude Code

Add the server once for your user (available in every project):

```sh
claude mcp add --scope user --transport http ynay https://ynay.example.com/mcp \
  --header "Authorization: Bearer ynay_pat_..."
```

Check it with `claude mcp list` (or `/mcp` inside a session). The tools then appear as
`mcp__ynay__list_accounts`, `mcp__ynay__import_transactions`, and so on.

### Project config without committing the token

This repository ships `.mcp.json.example`:

```json
{
  "mcpServers": {
    "ynay": {
      "type": "http",
      "url": "${YNAY_URL:-https://ynay.example.com}/mcp",
      "headers": {
        "Authorization": "Bearer ${YNAY_TOKEN}"
      }
    }
  }
}
```

Copy it to `.mcp.json` in your project root and export the variables before starting Claude Code:

```sh
cp .mcp.json.example .mcp.json
export YNAY_URL=https://ynay.example.com
export YNAY_TOKEN=ynay_pat_...
claude
```

Claude Code expands `${VAR}` and `${VAR:-default}` in `url` and `headers`, so the token stays in
your environment (or a secrets manager) rather than in the file. Claude Code asks you to approve
project-scoped servers from `.mcp.json` the first time; `claude mcp reset-project-choices` resets
that decision.

### The import subagent

`.claude/agents/ynay-importer.md` is a Claude Code subagent that imports statements through these
tools. Copy it to `~/.claude/agents/` to use it everywhere, or keep it in a project's
`.claude/agents/`. Then ask, for example:

> Use the ynay-importer agent to import ~/Downloads/statement.csv into Checking.

See `docs/IMPORT_AGENT.md` for details.

## 3. Claude Desktop

Claude Desktop's own remote-server setup (**Settings → Connectors → Add custom connector**) signs
in with OAuth and has no field for a static bearer token. If your Kanidm setup supports that flow,
add `https://ynay.example.com/mcp` there. Otherwise, bridge the remote server to a local stdio
server with [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) (needs Node.js). In
**Settings → Developer → Edit Config** (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "ynay": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "https://ynay.example.com/mcp",
        "--header",
        "Authorization:${YNAY_AUTH}"
      ],
      "env": {
        "YNAY_AUTH": "Bearer ynay_pat_..."
      }
    }
  }
}
```

The header value is passed through an environment variable because some platforms mangle spaces
inside `args`. Restart Claude Desktop afterwards; the YNAY tools appear in the tools menu.

## 4. Other clients

Any client with Streamable HTTP support works with these settings:

| setting | value |
|---|---|
| transport | Streamable HTTP (sometimes labeled "HTTP" or `streamable-http`; not SSE) |
| URL | `https://ynay.example.com/mcp` |
| header | `Authorization: Bearer ynay_pat_...` |

Clients that only support stdio servers can use the `mcp-remote` bridge shown above. A quick check
with curl:

```sh
curl -s https://ynay.example.com/mcp \
  -H "Authorization: Bearer $YNAY_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Tips

- **Amounts are milliunits.** `-12340` is an outflow of 12.34. Tool descriptions say so, but it
  helps to remind a model when you write your own prompts.
- **Destructive tools** (`delete_*`) carry the MCP `destructiveHint` annotation. Most clients ask
  for confirmation before calling them; in Claude Code you can also deny them outright with
  permission rules such as `"deny": ["mcp__ynay__delete_budget"]`.
- **Reverse proxies.** `/mcp` is a plain POST endpoint with JSON responses, so no special proxy
  settings (WebSockets, SSE buffering) are needed. Allow large request bodies and generous
  timeouts if you import large statements.
