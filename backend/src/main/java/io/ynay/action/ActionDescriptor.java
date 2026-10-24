package io.ynay.action;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    /** Human readable display name derived from the snake_case name: {@code create_account} → "Create account". */
    @JsonProperty("title")
    public String title() {
        String spaced = name.replace('_', ' ').trim();
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
