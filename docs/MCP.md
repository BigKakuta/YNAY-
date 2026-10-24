# MCP

Every YNAY action is an MCP tool on `POST /mcp` (Streamable HTTP, stateless, JSON responses). Any
MCP client that speaks the HTTP transport can manage a budget: list accounts, import transactions,
assign money, run reports. This page covers connecting clients first, then the protocol details.

Throughout, replace `https://ynay.example.com` with your `YNAY_PUBLIC_URL`.

## 1. Create an API token

MCP clients authenticate with a personal API token sent as `Authorization: Bearer <token>`:

1. Sign in to YNAY and open **Settings → MCP access tokens**.
2. Click **Create token**, give it a name that identifies the client (e.g. `laptop claude code`),
   and optionally an expiry.
3. Copy the token (it starts with `ynay_`) right away. It is shown only once; YNAY stores just a
   hash of it.

The token acts as you: it can do everything you can in every budget you are a member of. Create
one token per client so you can revoke them one by one under **Settings → MCP access tokens**. Token
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

Clients that only support stdio servers can use the `mcp-remote` bridge shown above. Clients that
run in a browser (e.g. the MCP Inspector at `http://localhost:6274`) send an `Origin` header, which
`/mcp` rejects unless it is listed in `YNAY_MCP_ALLOWED_ORIGINS`. A quick check with curl:

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

## Protocol details

YNAY implements the MCP server by hand (`backend/src/main/java/io/ynay/mcp`); there is no MCP SDK.
Every `@McpAction` with `mcp = true` (the default) is a tool; the tool list comes straight from the
`ActionRegistry`, so tools, the web UI and the import agent always share one implementation.

**Transport.** Streamable HTTP, **stateless**, at `POST /mcp`:

* Body: one JSON-RPC 2.0 message, or a batch (JSON array; accepted for every protocol version).
* Requests are answered with `200` and `Content-Type: application/json`. We never open an SSE stream.
* Bodies that contain only notifications (e.g. `notifications/initialized`) or client responses get
  `202 Accepted` with an empty body.
* No `Mcp-Session-Id` is issued; each request stands alone. `GET /mcp` and `DELETE /mcp` → `405`.
* Unparsable JSON → `400` with error `-32700`; a malformed message (not an object, bad `jsonrpc`,
  bad/null `id`, empty batch) → `400`/`-32600` when there is no usable id.
* The `MCP-Protocol-Version` header is accepted but not enforced.
* Authentication: `Authorization: Bearer <token>` (see client setup). Tool calls run synchronously
  on the request thread, as the token's user.

**Origin check (DNS rebinding).** Requests with an `Origin` header are rejected with `403` unless the
origin is the origin of `YNAY_PUBLIC_URL`, listed in `YNAY_MCP_ALLOWED_ORIGINS` (comma separated, e.g.
`http://localhost:6274` for the MCP Inspector), or same-origin on a loopback host. Requests without
`Origin` (native clients, server-to-server connectors) are allowed.

**Methods.**

| method | result |
|---|---|
| `initialize` | `protocolVersion` (the client's if supported, else the latest), `capabilities: {tools: {listChanged: false}}`, `serverInfo: {name: "ynay", title, version}`, `instructions` |
| `ping` | `{}` |
| `tools/list` | all tools at once (no pagination): `name`, `title`, `description`, `inputSchema`, `annotations {title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint: false}` |
| `tools/call` | see below |
| anything else | error `-32601` (no resources, prompts, logging, completion) |

Supported protocol versions: `2025-11-25` (latest), `2025-06-18`, `2025-03-26`, `2024-11-05`.

**Tool input schemas** are JSON Schema (2020-12 dialect, no `$schema` keyword) generated from the
action's input record: `type: object`, `properties` with `description` from `@ActionParam`,
`required`, `additionalProperties: false`. UUIDs are `format: uuid`, dates `format: date`, months
`pattern: ^\d{4}-\d{2}$`, enums `enum: [...]`, lists `type: array`.

**`tools/call` results.**

* Success: `content: [{type: "text", text: <pretty-printed JSON>}]`, `structuredContent: <result>`
  (results that are not JSON objects are wrapped as `{"result": <value>}`), `isError: false`.
  Actions returning nothing yield `{"ok": true}`.
* Action failure (invalid input, not found, forbidden, conflict): a normal result with
  `isError: true` and the message as text, so the model can read it and correct itself.
  Unexpected server failures also use `isError: true` with the text `Internal server error`.
* Unknown tool name (or an action with `mcp = false`): JSON-RPC error `-32602`.

**Input validation.** Unknown properties, missing/`null` required properties, wrong shapes and
bad formats (e.g. a malformed UUID, `"2026-1"` for a month, `1.5` for an amount) are rejected with
a message naming the parameter path, e.g. `Missing required parameter 'splits[0].amount'`.
