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
