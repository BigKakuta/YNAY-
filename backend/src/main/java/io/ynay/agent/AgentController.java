package io.ynay.agent;

import io.ynay.action.ActionException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/agent}: the in-app import assistant. Session-authenticated (see the security config);
 * the agent acts as the logged-in user. Errors use the same {@code {"error": {"code", "message"}}}
 * shape as {@code /api/actions}.
 */
@RestController
@RequestMapping("/api/agent")
class AgentController {

    private final AgentService agent;

    AgentController(AgentService agent) {
        this.agent = agent;
    }

    @GetMapping("/status")
    AgentService.Status status() {
        return agent.status();
    }

    /** One assistant turn. Can take minutes for large imports; the client keeps the history. */
    @PostMapping("/chat")
    AgentService.ChatResponse chat(@RequestBody AgentService.ChatRequest request) {
        return agent.chat(request);
    }

    @ExceptionHandler(AgentException.class)
    ResponseEntity<Map<String, Object>> agentError(AgentException e) {
        return error(e.httpStatus(), e.code(), e.getMessage());
    }

    @ExceptionHandler(ActionException.class)
    ResponseEntity<Map<String, Object>> actionError(ActionException e) {
        return error(e.code().httpStatus(), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return error(400, "INVALID_INPUT",
                "Request body must be {\"budgetId\": \"<uuid>\", \"messages\": [{\"role\", \"content\"}]}");
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
