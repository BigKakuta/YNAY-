package io.ynay.mcp;

import io.ynay.action.ActionDescriptor;
import io.ynay.action.ActionException;
import io.ynay.action.ActionRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Transport-independent MCP server: handles JSON-RPC 2.0 messages (single or batched) and
 * exposes the {@link ActionRegistry} as MCP tools. Stateless: there are no sessions, every
 * request carries everything needed. Tool calls run synchronously on the caller's thread, so
 * the Spring Security context of the HTTP request applies.
 */
@Component
public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    /** Protocol versions we speak, newest first. */
    public static final List<String> SUPPORTED_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    public static final String LATEST_VERSION = SUPPORTED_VERSIONS.getFirst();

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    static final String INSTRUCTIONS = """
            YNAY is a self-hosted, zero-based (YNAB-style) budgeting app. Conventions for every tool:
            - Amounts are integer milliunits of the budget's currency: 1000 = 1.00, -12340 = -12.34. \
            Negative amounts are outflows, positive amounts inflows.
            - Dates are YYYY-MM-DD, months are YYYY-MM. Ids are UUID strings.
            - Call list_budgets first: almost every tool needs a budgetId. Resolve accounts, categories \
            and payees with list_accounts / list_categories / list_payees before creating transactions.
            - "Ready to Assign" is a special category (systemKind READY_TO_ASSIGN); get_month_budget shows \
            assigned, activity and available money per category for a month.
            - Tool results are JSON; failures come back as tool errors with a human readable message.""";

    private final ActionRegistry registry;
    private final JsonMapper mapper;
    private final String version;

    public McpServer(ActionRegistry registry, JsonMapper mapper, ObjectProvider<BuildProperties> buildProperties) {
        this.registry = registry;
        this.mapper = mapper;
        BuildProperties build = buildProperties.getIfAvailable();
        String implementationVersion = McpServer.class.getPackage().getImplementationVersion();
        this.version = build != null ? build.getVersion()
                : implementationVersion != null ? implementationVersion : "dev";
    }

    /**
     * Handles a parsed HTTP request body: a single JSON-RPC message or a batch (array).
     *
     * @return the response (object or array), or {@code null} if the body only contained
     *         notifications / responses and nothing has to be sent back
     */
    public JsonNode handleBody(JsonNode body) {
        if (body.isArray()) {
            if (body.isEmpty()) {
                return error(null, INVALID_REQUEST, "Invalid Request: empty batch");
            }
            ArrayNode responses = mapper.createArrayNode();
            for (JsonNode message : body) {
                ObjectNode response = handle(message);
                if (response != null) {
                    responses.add(response);
                }
            }
            return responses.isEmpty() ? null : responses;
        }
        return handle(body);
    }

    /** Handles one JSON-RPC message. Returns {@code null} for notifications and client responses. */
    public ObjectNode handle(JsonNode message) {
        if (!message.isObject()) {
            return error(null, INVALID_REQUEST, "Invalid Request: expected a JSON-RPC object");
        }
        JsonNode id = message.get("id");
        JsonNode method = message.get("method");
        boolean validId = id != null && (id.isString() || id.isIntegralNumber());
        if (!"2.0".equals(text(message.get("jsonrpc")))) {
            return error(validId ? id : null, INVALID_REQUEST, "Invalid Request: jsonrpc must be \"2.0\"");
        }
        if (method == null) {
            if (message.has("result") || message.has("error")) {
                return null; // a response to a server request; we never send any, so ignore it
            }
            return error(validId ? id : null, INVALID_REQUEST, "Invalid Request: missing method");
        }
        if (!method.isString()) {
            return error(validId ? id : null, INVALID_REQUEST, "Invalid Request: method must be a string");
        }
        if (id == null) {
            log.debug("MCP notification {}", method.stringValue());
            return null;
        }
        if (!validId) {
            return error(null, INVALID_REQUEST, "Invalid Request: id must be a string or an integer");
        }
        JsonNode params = message.get("params");
        if (params != null && !params.isNull() && !params.isObject()) {
            return error(id, INVALID_PARAMS, "params must be an object");
        }
        ObjectNode safeParams = params != null && params.isObject() ? (ObjectNode) params : mapper.createObjectNode();
        try {
            return result(id, dispatch(method.stringValue(), safeParams));
        } catch (JsonRpcError e) {
            return error(id, e.code, e.getMessage());
        } catch (RuntimeException e) {
            log.error("MCP request {} failed", method.stringValue(), e);
            return error(id, INTERNAL_ERROR, "Internal error");
        }
    }

    private JsonNode dispatch(String method, ObjectNode params) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> mapper.createObjectNode();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            default -> throw new JsonRpcError(METHOD_NOT_FOUND, "Method not found: " + method);
        };
    }

    private ObjectNode initialize(ObjectNode params) {
        String requested = text(params.get("protocolVersion"));
        String negotiated = requested != null && SUPPORTED_VERSIONS.contains(requested) ? requested : LATEST_VERSION;
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "ynay");
        serverInfo.put("title", "YNAY");
        serverInfo.put("version", version);
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (ActionDescriptor action : registry.list()) {
            if (!action.mcp()) {
                continue;
            }
            ObjectNode tool = tools.addObject();
            tool.put("name", action.name());
            tool.put("title", action.title());
            tool.put("description", action.description());
            tool.set("inputSchema", action.inputSchema());
            ObjectNode annotations = tool.putObject("annotations");
            annotations.put("title", action.title());
            annotations.put("readOnlyHint", action.readOnly());
            annotations.put("destructiveHint", !action.readOnly() && action.destructive());
            annotations.put("idempotentHint", action.readOnly() || action.idempotent());
            annotations.put("openWorldHint", false);
        }
        return result;
    }

    private ObjectNode toolsCall(ObjectNode params) {
        String name = text(params.get("name"));
        if (name == null) {
            throw new JsonRpcError(INVALID_PARAMS, "Missing tool name");
        }
        if (registry.find(name).filter(ActionDescriptor::mcp).isEmpty()) {
            throw new JsonRpcError(INVALID_PARAMS, "Unknown tool: " + name);
        }
        JsonNode value;
        try {
            value = registry.invoke(name, params.get("arguments"));
        } catch (ActionException e) {
            return toolError(e.getMessage());
        } catch (RuntimeException e) {
            log.error("MCP tool {} failed", name, e);
            return toolError("Internal server error");
        }
        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject()
                .put("type", "text")
                .put("text", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        if (value.isObject()) {
            result.set("structuredContent", value);
        } else {
            result.putObject("structuredContent").set("result", value);
        }
        result.put("isError", false);
        return result;
    }

    private ObjectNode toolError(String message) {
        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", message);
        result.put("isError", true);
        return result;
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    /** A JSON-RPC error response; {@code id} null means "id": null. */
    public ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static final class JsonRpcError extends RuntimeException {
        private final int code;

        JsonRpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
