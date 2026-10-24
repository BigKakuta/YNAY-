package io.ynay.agent;

/** A failure of the agent endpoint itself, rendered as {@code {"error": {"code", "message"}}}. */
public class AgentException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public AgentException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public AgentException(int httpStatus, String code, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    static AgentException disabled(String reason) {
        return new AgentException(503, "AGENT_DISABLED", "The import assistant is not configured: " + reason
                + " See docs/IMPORT_AGENT.md.");
    }

    static AgentException tooLarge(String message) {
        return new AgentException(413, "PAYLOAD_TOO_LARGE", message);
    }

    static AgentException invalid(String message) {
        return new AgentException(400, "INVALID_INPUT", message);
    }

    /** The LLM provider failed or could not be reached. */
    static AgentException upstream(String message, Throwable cause) {
        return new AgentException(502, "LLM_ERROR", message, cause);
    }
}
