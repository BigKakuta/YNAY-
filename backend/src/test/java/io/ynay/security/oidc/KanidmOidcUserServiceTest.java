package io.ynay.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.security.AppUserRepository;
import io.ynay.security.CurrentUser;
import io.ynay.security.Me;
import io.ynay.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class KanidmOidcUserServiceTest extends IntegrationTest {

    static final String ISSUER = "https://idm.example.com/oauth2/openid/ynay";

    @Autowired
    AppUserRepository users;

    @Test
    void provisionsAndRefreshesTheUserOnEachLogin() {
        String sub = UUID.randomUUID().toString();
        var first = login(sub, Map.of("preferred_username", "alice@idm.example.com", "name", "Alice",
                "email", "alice@example.com"));

        assertThat(first).isInstanceOf(KanidmOidcUser.class);
        KanidmOidcUser user = (KanidmOidcUser) first;
        assertThat(user.username()).isEqualTo("alice@idm.example.com");
        assertThat(user.getAuthorities()).extracting(Object::toString).contains("ROLE_USER", "OIDC_USER");
        assertThat(users.findMe(user.userId())).contains(
                new Me(user.userId(), "alice@idm.example.com", "Alice", "alice@example.com"));
        assertThat(jdbc.sql("select last_login_at is not null from app_user where id = ?").param(user.userId())
                .query(Boolean.class).single()).isTrue();

        // renamed in Kanidm (e.g. prefer-short-username enabled): same row, updated profile
        KanidmOidcUser again = (KanidmOidcUser) login(sub, Map.of("preferred_username", "alice", "name", "Alice B."));
        assertThat(again.userId()).isEqualTo(user.userId());
        assertThat(users.findMe(user.userId())).contains(new Me(user.userId(), "alice", "Alice B.", null));

        // a session authenticated with it works with CurrentUser
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(again, again.getAuthorities(), "kanidm"));
        assertThat(CurrentUser.id()).isEqualTo(user.userId());
        assertThat(CurrentUser.get().username()).isEqualTo("alice");
    }

    @Test
    void sameSubjectFromAnotherIssuerIsAnotherUser() {
        String sub = UUID.randomUUID().toString();
        UUID a = ((KanidmOidcUser) login(sub, Map.of("preferred_username", "x"))).userId();
        UUID b = ((KanidmOidcUser) login(ISSUER + "-other", sub, Map.of("preferred_username", "x"))).userId();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void fallsBackToSubjectWithoutUsernameClaims() {
        String sub = UUID.randomUUID().toString();
        KanidmOidcUser user = (KanidmOidcUser) login(sub, Map.of());
        assertThat(user.username()).isEqualTo(sub);
    }

    private OidcUser login(String sub, Map<String, Object> claims) {
        return login(ISSUER, sub, claims);
    }

    private OidcUser login(String issuer, String sub, Map<String, Object> claims) {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .issuer(issuer)
                .subject(sub)
                .audience(List.of("ynay"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claims(c -> c.putAll(claims))
                .build();
        OidcUser fromKanidm = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken,
                new OidcUserInfo(Map.of("sub", sub)));
        var service = new KanidmOidcUserService(request -> fromKanidm, users);
        ClientRegistration registration = ClientRegistration.withRegistrationId("kanidm")
                .clientId("ynay")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:8080/login/oauth2/code/kanidm")
                .authorizationUri(issuer + "/authorize")
                .tokenUri(issuer + "/token")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at", now,
                now.plusSeconds(300));
        return service.loadUser(new OidcUserRequest(registration, accessToken, idToken));
    }
}
