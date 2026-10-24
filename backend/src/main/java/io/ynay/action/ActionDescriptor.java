package io.ynay.action;

import tools.jackson.databind.node.ObjectNode;

/** Metadata of one action, as published to MCP clients ({@code tools/list}) and the web UI. */
public record ActionDescriptor(
        String name,
        String description,
        ObjectNode inputSchema,
        boolean readOnly,
        boolean destructive,
        boolean idempotent,
        boolean mcp) {
}
