package io.ynay.action;

/**
 * A user-facing action failure. Mapped to an HTTP status on {@code /api/actions}
 * and to an MCP tool result with {@code isError: true} on {@code /mcp}.
 */
public class ActionException extends RuntimeException {

    public enum Code {
        INVALID_INPUT(400),
        UNAUTHENTICATED(401),
        FORBIDDEN(403),
        NOT_FOUND(404),
        CONFLICT(409);

        private final int httpStatus;

        Code(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Code code;

    public ActionException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public static ActionException notFound(String what) {
        return new ActionException(Code.NOT_FOUND, what + " not found");
    }

    public static ActionException invalid(String message) {
        return new ActionException(Code.INVALID_INPUT, message);
    }

    public static ActionException forbidden(String message) {
        return new ActionException(Code.FORBIDDEN, message);
    }

    public static ActionException conflict(String message) {
        return new ActionException(Code.CONFLICT, message);
    }
}
