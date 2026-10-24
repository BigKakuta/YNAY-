package io.ynay.security.web;

import io.ynay.security.YnaySecurityProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) for the MCP endpoint. MCP clients discover the
 * authorization server (Kanidm) from here after a 401 with {@code WWW-Authenticate: Bearer
 * resource_metadata="..."}. Served at the root and at the path-suffixed location
 * ({@code /.well-known/oauth-protected-resource/mcp}) that RFC 9728 derives for {@code /mcp}.
 */
@RestController
public class ProtectedResourceMetadataController {

    public static final String PATH = "/.well-known/oauth-protected-resource";

    private final YnaySecurityProperties props;

    public ProtectedResourceMetadataController(YnaySecurityProperties props) {
        this.props = props;
    }

    @GetMapping({PATH, PATH + "/mcp"})
    public Map<String, Object> metadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", props.publicUrl() + "/mcp");
        if (props.kanidm().configured()) {
            m.put("authorization_servers", List.of(props.kanidm().issuer()));
        }
        m.put("bearer_methods_supported", List.of("header"));
        m.put("scopes_supported", List.of("openid", "profile", "email"));
        m.put("resource_name", "YNAY");
        return m;
    }
}
