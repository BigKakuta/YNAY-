package io.ynay.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Security related settings ({@code ynay.*} in application.yml, fed by {@code YNAY_*} env vars).
 *
 * @param publicUrl external base URL of the app, without trailing slash
 */
@ConfigurationProperties("ynay")
public record YnaySecurityProperties(
        @DefaultValue("http://localhost:8080") String publicUrl,
        @DefaultValue Kanidm kanidm,
        @DefaultValue Mcp mcp) {

    public YnaySecurityProperties {
        publicUrl = stripTrailingSlash(publicUrl);
    }

    /**
     * @param url        Kanidm origin, e.g. {@code https://idm.example.com}; login is disabled when blank
     * @param clientId   OAuth2 client name in Kanidm
     * @param idTokenAlg JWS algorithm of ID tokens (Kanidm default ES256; RS256 with legacy crypto)
     */
    public record Kanidm(
            String url,
            @DefaultValue("ynay") String clientId,
            String clientSecret,
            @DefaultValue("ES256") String idTokenAlg) {

        public Kanidm {
            url = stripTrailingSlash(url);
        }

        public boolean configured() {
            return StringUtils.hasText(url);
        }

        /** Kanidm uses one OIDC issuer per client: {@code <origin>/oauth2/openid/<client_id>}. */
        public String issuer() {
            return url + "/oauth2/openid/" + clientId;
        }

        public String introspectionUri() {
            return url + "/oauth2/token/introspect";
        }
    }

    /**
     * @param introspection  accept Kanidm access tokens on {@code /mcp} via RFC 7662 introspection
     * @param tokenAudiences comma separated Kanidm client ids whose access tokens are accepted
     *                       (defaults to {@link Kanidm#clientId()})
     */
    public record Mcp(
            @DefaultValue("false") boolean introspection,
            String tokenAudiences) {
    }

    public boolean httpsPublicUrl() {
        return publicUrl.startsWith("https://");
    }

    public List<String> acceptedTokenAudiences() {
        if (!StringUtils.hasText(mcp.tokenAudiences())) {
            return List.of(kanidm.clientId());
        }
        return Arrays.stream(mcp.tokenAudiences().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
