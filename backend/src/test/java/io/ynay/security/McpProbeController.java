package io.ynay.security;

import java.util.Map;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint inside the {@code /mcp/**} filter chain that echoes {@link CurrentUser}.
 * Mapped below {@code /mcp} so it never clashes with the real MCP endpoint. Not component-scanned
 * ({@code @TestComponent}); tests {@code @Import} it.
 */
@TestComponent
@RestController
public class McpProbeController {

    @GetMapping("/mcp/test-probe")
    public Map<String, Object> probe() {
        YnayPrincipal p = CurrentUser.get();
        return Map.of("userId", p.userId().toString(), "username", p.username());
    }
}
