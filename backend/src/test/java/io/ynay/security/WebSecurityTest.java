package io.ynay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ynay.security.oidc.KanidmOidcUser;
import io.ynay.security.token.ApiTokenService;
import io.ynay.security.token.CreatedApiToken;
import io.ynay.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP-level security behaviour without a Kanidm configured (the default in tests). */
@AutoConfigureMockMvc
@Import(McpProbeController.class)
class WebSecurityTest extends IntegrationTest {

    private static final String METADATA = "http://localhost:8080/.well-known/oauth-protected-resource";

    @Autowired
    MockMvc mvc;

    @Autowired
    ApiTokenService tokens;

    static KanidmOidcUser sessionUser(YnayUser user) {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .issuer("https://idm.test/oauth2/openid/ynay")
                .subject(user.userId().toString())
                .claim("preferred_username", user.username())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return new KanidmOidcUser(user.userId(), user.username(),
                List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, null);
    }

    // --- /api ---------------------------------------------------------------------------------

    @Test
    void apiMeRequiresLoginAndAnswersJson401() throws Exception {
        mvc.perform(get("/api/me").accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/actions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void apiMeReturnsTheSessionUser() throws Exception {
        YnayUser user = createUser("alice@idm.example.com");
        jdbc.sql("update app_user set display_name = 'Alice', email = 'alice@example.com' where id = ?")
                .param(user.userId()).update();

        mvc.perform(get("/api/me").with(oidcLogin().oidcUser(sessionUser(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.userId().toString()))
                .andExpect(jsonPath("$.username").value("alice@idm.example.com"))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void bearerTokensAreNotAcceptedOnTheWebApi() throws Exception {
        YnayUser user = createUser("bob");
        CreatedApiToken token = tokens.create(user.userId(), "t", null);
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsafeApiRequestsNeedTheCsrfHeader() throws Exception {
        YnayUser user = createUser("carol");
        var login = oidcLogin().oidcUser(sessionUser(user));

        mvc.perform(post("/api/me").with(login))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // SPA flow: token from the XSRF-TOKEN cookie echoed in X-XSRF-TOKEN passes the CSRF filter
        Cookie xsrf = mvc.perform(get("/")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/me").with(login).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isMethodNotAllowed());

        // a wrong header value is rejected
        mvc.perform(post("/api/me").with(login).cookie(xsrf).header("X-XSRF-TOKEN", "nope"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfCookieIsEmittedOnFirstGet() throws Exception {
        MvcResult result = mvc.perform(get("/"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(cookie().path("XSRF-TOKEN", "/"))
                .andReturn();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN").getValue()).isNotBlank();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN").getAttribute("SameSite")).isEqualTo("Lax");

        // also on unauthenticated API calls (first thing the SPA does is GET /api/me)
        mvc.perform(get("/api/me")).andExpect(cookie().exists("XSRF-TOKEN"));
    }

    // --- public paths, SPA ----------------------------------------------------------------------

    @Test
    void publicPaths() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }

    @Test
    void clientSideRoutesServeTheSpa() throws Exception {
        mvc.perform(get("/budgets/123/accounts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(get("/assets/missing-file.js")).andExpect(status().isNotFound());
        mvc.perform(get("/api/does-not-exist").with(oidcLogin().oidcUser(sessionUser(createUser("dave")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void loginEndpointIsAbsentWithoutKanidm() throws Exception {
        mvc.perform(get("/oauth2/authorization/kanidm")).andExpect(status().isNotFound());
        mvc.perform(get("/login")).andExpect(status().isOk()); // just the SPA, no Spring login page
    }

    @Test
    void protectedResourceMetadataWithoutKanidm() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resource").value("http://localhost:8080/mcp"))
                    .andExpect(jsonPath("$.authorization_servers").doesNotExist())
                    .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
                    .andExpect(jsonPath("$.scopes_supported").isArray());
        }
    }

    // --- logout ----------------------------------------------------------------------------------

    @Test
    void logout() throws Exception {
        // Note: the csrf() request post-processor is avoided on purpose: it swaps the CsrfFilter's
        // repository for all later requests in the shared context. Use the real SPA flow instead.
        var login = oidcLogin().oidcUser(sessionUser(createUser("erin")));
        mvc.perform(post("/logout").with(login)).andExpect(status().isForbidden()); // CSRF required
        Cookie xsrf = mvc.perform(get("/")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/logout").with(login).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/logout").with(login).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value("/"));
    }

    // --- /mcp bearer auth --------------------------------------------------------------------------

    @Test
    void mcpWithoutTokenIs401WithResourceMetadata() throws Exception {
        mvc.perform(get("/mcp/test-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        allOf(containsString("Bearer"), containsString("resource_metadata=\"" + METADATA + "\""))))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("resource_metadata=")));
    }

    @Test
    void mcpAcceptsApiTokens() throws Exception {
        YnayUser user = createUser("frank");
        CreatedApiToken token = tokens.create(user.userId(), "mcp", null);

        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.userId().toString()))
                .andExpect(jsonPath("$.username").value("frank"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)); // stateless, no CSRF cookie

        // POST without CSRF is fine on /mcp (reaches MVC: no handler here -> not 401/403)
        int status = mvc.perform(post("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token.token()))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);

        tokens.revoke(user.userId(), token.id());
        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        allOf(containsString("error=\"invalid_token\""), containsString("resource_metadata="))));
    }

    @Test
    void mcpRejectsUnknownTokensWhenIntrospectionIsOff() throws Exception {
        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer ynay_pat_bogus"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
        mvc.perform(get("/mcp/test-probe").header(HttpHeaders.AUTHORIZATION, "Bearer some-kanidm-access-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }

    @Test
    void mcpWithoutTokenHasNoErrorCode() throws Exception {
        mvc.perform(get("/mcp/test-probe"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, not(containsString("error="))));
    }
}
