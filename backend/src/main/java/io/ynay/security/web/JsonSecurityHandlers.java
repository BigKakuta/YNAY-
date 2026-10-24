package io.ynay.security.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401/403 responses for {@code /api/**} in the action error format
 * ({@code {"error": {"code", "message"}}}) instead of login redirects.
 */
public final class JsonSecurityHandlers {

    static final String UNAUTHENTICATED_BODY =
            "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"Not authenticated\"}}";
    static final String FORBIDDEN_BODY =
            "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Access denied (missing or invalid CSRF token?)\"}}";

    private JsonSecurityHandlers() {
    }

    public static AuthenticationEntryPoint unauthorized() {
        return (request, response, ex) -> write(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHENTICATED_BODY);
    }

    public static AccessDeniedHandler forbidden() {
        return (request, response, ex) -> write(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_BODY);
    }

    private static void write(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
