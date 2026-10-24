package io.ynay.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP Streamable HTTP transport on {@code /mcp}, stateless, JSON responses only.
 *
 * <ul>
 *   <li>{@code POST}: one JSON-RPC message or a batch. Requests get a {@code 200} JSON response,
 *       bodies made only of notifications/responses get {@code 202} with no body.</li>
 *   <li>{@code GET} / {@code DELETE}: {@code 405}; there is no SSE stream and no session.</li>
 * </ul>
 * Authentication (bearer token) is enforced by the security filter chain, not here.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpServer server;
    private final JsonMapper mapper;
    private final OriginPolicy originPolicy;

    public McpController(McpServer server, JsonMapper mapper,
                         @Value("${ynay.public-url:${YNAY_PUBLIC_URL:http://localhost:8080}}") String publicUrl,
                         @Value("${ynay.mcp.allowed-origins:${YNAY_MCP_ALLOWED_ORIGINS:}}") String extraOrigins) {
        this.server = server;
        this.mapper = mapper;
        this.originPolicy = new OriginPolicy(publicUrl, extraOrigins);
    }

    @PostMapping
    public ResponseEntity<JsonNode> post(@RequestBody(required = false) String body,
                                         @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
                                         @RequestHeader(name = "MCP-Protocol-Version", required = false) String protocolVersion,
                                         HttpServletRequest request) {
        if (!originPolicy.isAllowed(origin, requestOrigin(request))) {
            log.warn("Rejected MCP request with Origin {}", origin);
            return json(HttpStatus.FORBIDDEN, server.error(null, McpServer.INVALID_REQUEST, "Forbidden origin"));
        }
        if (protocolVersion != null && !McpServer.SUPPORTED_VERSIONS.contains(protocolVersion)) {
            // Lenient: the body is plain JSON-RPC either way, so we carry on.
            log.debug("Client sent unsupported MCP-Protocol-Version {}", protocolVersion);
        }
        if (body == null || body.isBlank()) {
            return json(HttpStatus.BAD_REQUEST, server.error(null, McpServer.INVALID_REQUEST, "Invalid Request: empty body"));
        }
        JsonNode message;
        try {
            message = mapper.readTree(body);
        } catch (JacksonException e) {
            return json(HttpStatus.BAD_REQUEST, server.error(null, McpServer.PARSE_ERROR, "Parse error"));
        }
        JsonNode reply = server.handleBody(message);
        if (reply == null) {
            return ResponseEntity.accepted().build();
        }
        return json(isTransportError(reply) ? HttpStatus.BAD_REQUEST : HttpStatus.OK, reply);
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<Void> notAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).allow(HttpMethod.POST).build();
    }

    /** A lone (non-batch) error without id: the message itself was unusable. */
    private static boolean isTransportError(JsonNode reply) {
        return reply.isObject() && reply.has("error") && reply.get("id").isNull();
    }

    private static String requestOrigin(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
    }

    private static ResponseEntity<JsonNode> json(HttpStatus status, JsonNode body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
