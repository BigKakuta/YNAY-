package io.ynay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ynay.support.IntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kanidm configured (against a tiny fake Kanidm HTTP server) with MCP token introspection on:
 * lazy discovery, the PKCE authorization redirect and RFC 7662 introspection on {@code /mcp}.
 */
@AutoConfigureMockMvc
@Import(McpProbeController.class)
class KanidmConfiguredTest extends IntegrationTest {

    static final String SECRET = "s3cret";
    static final String SUBJECT = UUID.randomUUID().toString();
    static final AtomicInteger discoveryHits = new AtomicInteger();
    static final AtomicInteger introspectionHits = new AtomicInteger();
    static HttpServer kanidm;
    static String origin;

    @DynamicPropertySource
    static void kanidmProperties(DynamicPropertyRegistry registry) throws IOException {
        startFakeKanidm();
        registry.add("YNAY_KANIDM_URL", () -> origin);
        registry.add("YNAY_KANIDM_CLIENT_SECRET", () -> SECRET);
        registry.add("YNAY_MCP_INTROSPECTION", () -> "true");
        registry.add("YNAY_MCP_TOKEN_AUDIENCES", () -> "ynay, claude-mcp");
    }

    @Autowired
    MockMvc mvc;

    String issuer() {
        return origin + "/oauth2/openid/ynay";
    }

    @Test
    void bootsWithoutContactingKanidmAndRedirectsToItWithPkce() throws Exception {
        // context is up; discovery happens on first use only (may already have run in another test)
        int before = discoveryHits.get();
        String location = mvc.perform(get("/oauth2/authorization/kanidm"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(discoveryHits.get()).isLessThanOrEqualTo(before + 1).isGreaterThanOrEqualTo(1);

        String decoded = URLDecoder.decode(location, StandardCharsets.UTF_8);
        assertThat(location).startsWith(origin + "/ui/oauth2?");
        assertThat(decoded)
                .contains("response_type=code")
                .contains("client_id=ynay")
                .contains("scope=openid profile email")
                .contains("redirect_uri=http://localhost:8080/login/oauth2/code/kanidm")
                .contains("code_challenge_method=S256")
                .contains("code_challenge=")
                .contains("state=")
                .contains("nonce=");

        // the discovery result is cached
        mvc.perform(get("/oauth2/authorization/kanidm")).andExpect(status().isFound());
        assertThat(discoveryHits.get()).isLessThanOrEqualTo(before + 1);
    }

    @Test
    void metadataNamesKanidmAsAuthorizationServer() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost:8080/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value(issuer()));
    }

    @Test
    void introspectedKanidmTokensAuthenticateAndProvisionTheUser() throws Exception {
        String body = mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("zoe@idm.test"))
                .andReturn().getResponse().getContentAsString();

        UUID userId = jdbc.sql("select id from app_user where oidc_issuer = ? and oidc_subject = ?")
                .params(issuer(), SUBJECT).query(UUID.class).single();
        assertThat(body).contains(userId.toString());

        // cached: a second call does not hit Kanidm again
        int hits = introspectionHits.get();
        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()));
        assertThat(introspectionHits.get()).isEqualTo(hits);
    }

    @Test
    void introspectionMapsToTheExistingLoginUser() throws Exception {
        String sub = "existing-" + UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into app_user (id, oidc_issuer, oidc_subject, username) values (?, ?, ?, 'yara')")
                .params(id, issuer(), sub).update();

        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer sub-" + sub))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(id.toString()))
                .andExpect(jsonPath("$.username").value("yara"));
    }

    @Test
    void tokensForOtherClientsAreRejectedUnlessAllowed() throws Exception {
        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer grafana-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer claude-mcp-token"))
                .andExpect(status().isOk());
    }

    @Test
    void inactiveTokensAreRejected() throws Exception {
        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer garbage-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }

    // --- fake Kanidm ------------------------------------------------------------------------------

    static synchronized void startFakeKanidm() throws IOException {
        if (kanidm != null) {
            return;
        }
        kanidm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://localhost:" + kanidm.getAddress().getPort();
        kanidm.createContext("/oauth2/openid/ynay/.well-known/openid-configuration", ex -> {
            discoveryHits.incrementAndGet();
            String iss = origin + "/oauth2/openid/ynay";
            respond(ex, 200, """
                    {"issuer":"%1$s","authorization_endpoint":"%2$s/ui/oauth2","token_endpoint":"%2$s/oauth2/token",
                     "userinfo_endpoint":"%1$s/userinfo","jwks_uri":"%1$s/public_key.jwk",
                     "scopes_supported":["openid","profile","email"],"response_types_supported":["code"],
                     "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["ES256"],
                     "token_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post"],
                     "code_challenge_methods_supported":["S256"]}""".formatted(iss, origin));
        });
        kanidm.createContext("/oauth2/token/introspect", ex -> {
            introspectionHits.incrementAndGet();
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString(("ynay:" + SECRET).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                respond(ex, 401, "{\"error\":\"invalid_client\"}");
                return;
            }
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String token = "";
            for (String pair : form.split("&")) {
                if (pair.startsWith("token=")) {
                    token = URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
                }
            }
            if (token.startsWith("garbage")) {
                // real Kanidm: tokens it cannot parse get a bodyless 401 instead of {"active":false}
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            long exp = Instant.now().plusSeconds(300).getEpochSecond();
            String iss = origin + "/oauth2/openid/ynay";
            String json = switch (token) {
                case "good-token" -> active("ynay", SUBJECT, "zoe@idm.test", iss, exp);
                case "grafana-token" -> active("grafana", UUID.randomUUID().toString(), "x@idm.test", origin
                        + "/oauth2/openid/grafana", exp);
                case "claude-mcp-token" -> active("claude-mcp", UUID.randomUUID().toString(), "c@idm.test", origin
                        + "/oauth2/openid/claude-mcp", exp);
                default -> token.startsWith("sub-")
                        ? active("ynay", token.substring(4), "ignored@idm.test", iss, exp)
                        : "{\"active\":false}";
            };
            respond(ex, 200, json);
        });
        kanidm.start();
    }

    static String active(String clientId, String sub, String username, String iss, long exp) {
        return """
                {"active":true,"scope":"openid profile email","client_id":"%s","username":"%s",
                 "token_type":"Bearer","exp":%d,"iat":%d,"sub":"%s","aud":"%s","iss":"%s"}"""
                .formatted(clientId, username, exp, exp - 300, sub, clientId, iss);
    }

    static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
