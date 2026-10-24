package io.ynay.action;

import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Catalogue of all {@link McpAction} methods. The single entry point used by the MCP endpoint,
 * the {@code /api/actions} web endpoint and the in-process import agent.
 */
public interface ActionRegistry {

    /** All registered actions, sorted by name. */
    List<ActionDescriptor> list();

    Optional<ActionDescriptor> find(String name);

    /**
     * Validates {@code arguments} against the action's input type, invokes it as the current
     * user inside a transaction and returns the result converted to a JSON tree.
     *
     * @param arguments JSON object (may be {@code null} for no-arg actions)
     * @throws ActionException for unknown actions ({@code NOT_FOUND}), invalid input and
     *                         failures raised by the action itself
     */
    JsonNode invoke(String name, JsonNode arguments);
}
