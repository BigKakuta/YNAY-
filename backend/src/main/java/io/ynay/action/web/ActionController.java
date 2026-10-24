package io.ynay.action.web;

import io.ynay.action.ActionDescriptor;
import io.ynay.action.ActionException;
import io.ynay.action.ActionRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The web UI's gateway to the {@link ActionRegistry}: the only controller serving domain data.
 *
 * <ul>
 *   <li>{@code GET /api/actions}: the action catalogue</li>
 *   <li>{@code POST /api/actions/{name}}: invoke an action, body = input JSON (may be empty)</li>
 * </ul>
 * Errors are rendered as {@code {"error": {"code": "NOT_FOUND", "message": "..."}}} with the
 * code's HTTP status; unexpected failures as {@code 500 INTERNAL}.
 */
@RestController
@RequestMapping(path = "/api/actions", produces = MediaType.APPLICATION_JSON_VALUE)
public class ActionController {

    private static final Logger log = LoggerFactory.getLogger(ActionController.class);

    private final ActionRegistry registry;
    private final JsonMapper mapper;

    public ActionController(ActionRegistry registry, JsonMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @GetMapping
    public List<ActionDescriptor> list() {
        return registry.list();
    }

    @PostMapping("/{name}")
    public JsonNode invoke(@PathVariable String name, @RequestBody(required = false) String body) {
        JsonNode arguments = null;
        if (body != null && !body.isBlank()) {
            try {
                arguments = mapper.readTree(body);
            } catch (JacksonException e) {
                throw ActionException.invalid("Request body is not valid JSON");
            }
        }
        return registry.invoke(name, arguments);
    }

    @ExceptionHandler(ActionException.class)
    ResponseEntity<JsonNode> actionFailed(ActionException e) {
        return error(e.code().httpStatus(), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<JsonNode> unexpected(Exception e) throws Exception {
        if (e instanceof org.springframework.web.ErrorResponse) {
            // Framework-level problems (unsupported media type etc.) keep their own status.
            throw e;
        }
        log.error("Unexpected failure while handling an action request", e);
        return error(500, "INTERNAL", "Internal server error");
    }

    private ResponseEntity<JsonNode> error(int status, String code, String message) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
