package io.ynay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.action.ActionException;
import io.ynay.action.McpAction;
import io.ynay.security.SecurityActions.CreateApiTokenInput;
import io.ynay.security.SecurityActions.RevokeApiTokenInput;
import io.ynay.security.token.ApiToken;
import io.ynay.security.token.ApiTokenService;
import io.ynay.security.token.CreatedApiToken;
import io.ynay.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SecurityActionsTest extends IntegrationTest {

    @Autowired
    SecurityActions actions;

    @Autowired
    ApiTokenService tokens;

    @Test
    void whoamiReturnsTheCurrentUser() {
        YnayUser user = loginAsNewUser();
        jdbc.sql("update app_user set email = 'a@example.com' where id = ?").param(user.userId()).update();

        Me me = actions.whoami();

        assertThat(me.userId()).isEqualTo(user.userId());
        assertThat(me.username()).isEqualTo(user.username());
        assertThat(me.displayName()).isEqualTo(user.username());
        assertThat(me.email()).isEqualTo("a@example.com");
    }

    @Test
    void whoamiRequiresAuthentication() {
        assertThatThrownBy(() -> actions.whoami())
                .isInstanceOfSatisfying(ActionException.class,
                        e -> assertThat(e.code()).isEqualTo(ActionException.Code.UNAUTHENTICATED));
    }

    @Test
    void actionMetadata() throws Exception {
        Map<String, McpAction> byName = Arrays.stream(SecurityActions.class.getMethods())
                .filter(m -> m.isAnnotationPresent(McpAction.class))
                .map(m -> m.getAnnotation(McpAction.class))
                .collect(Collectors.toMap(McpAction::name, a -> a));
        assertThat(byName).containsOnlyKeys("whoami", "list_api_tokens", "create_api_token", "revoke_api_token");
        assertThat(byName.get("whoami").mcp()).isTrue();
        assertThat(byName.get("whoami").readOnly()).isTrue();
        assertThat(byName.get("list_api_tokens").mcp()).isFalse();
        assertThat(byName.get("create_api_token").mcp()).isFalse();
        assertThat(byName.get("revoke_api_token").mcp()).isFalse();
        assertThat(byName.get("revoke_api_token").destructive()).isTrue();
    }

    @Test
    void createListRevoke() {
        YnayUser user = loginAsNewUser();

        CreatedApiToken created = actions.createApiToken(new CreateApiTokenInput("  Claude Desktop ", 30));

        assertThat(created.name()).isEqualTo("Claude Desktop");
        assertThat(created.token()).startsWith("ynay_pat_");
        String secret = created.token().substring("ynay_pat_".length());
        assertThat(Base64.getUrlDecoder().decode(secret)).hasSize(32);
        assertThat(secret).doesNotContain("=", "+", "/");
        assertThat(created.token()).startsWith(created.tokenPrefix());
        assertThat(created.tokenPrefix()).hasSize("ynay_pat_".length() + 6);
        assertThat(created.lastUsedAt()).isNull();
        assertThat(Duration.between(created.createdAt(), created.expiresAt())).isEqualTo(Duration.ofDays(30));

        // only the hash is stored
        String stored = jdbc.sql("select token_hash from api_token where id = ?").param(created.id())
                .query(String.class).single();
        assertThat(stored).hasSize(64).isNotEqualTo(created.token()).doesNotContain(secret);

        List<ApiToken> listed = actions.listApiTokens();
        assertThat(listed).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(created.id());
            assertThat(t.name()).isEqualTo("Claude Desktop");
            assertThat(t.tokenPrefix()).isEqualTo(created.tokenPrefix());
            assertThat(t.createdAt()).isEqualTo(created.createdAt());
            assertThat(t.expiresAt()).isEqualTo(created.expiresAt());
        });

        assertThat(tokens.authenticate(created.token())).contains(user);

        assertThat(actions.revokeApiToken(new RevokeApiTokenInput(created.id()))).isEqualTo(Map.of("revoked", true));
        assertThat(actions.listApiTokens()).isEmpty();
        assertThat(tokens.authenticate(created.token())).isEmpty();
        assertThatThrownBy(() -> actions.revokeApiToken(new RevokeApiTokenInput(created.id())))
                .isInstanceOfSatisfying(ActionException.class,
                        e -> assertThat(e.code()).isEqualTo(ActionException.Code.NOT_FOUND));
    }

    @Test
    void tokenWithoutExpiry() {
        loginAsNewUser();
        CreatedApiToken created = actions.createApiToken(new CreateApiTokenInput("cli", null));
        assertThat(created.expiresAt()).isNull();
        assertThat(tokens.authenticate(created.token())).isPresent();
    }

    @Test
    void usersOnlySeeAndRevokeTheirOwnTokens() {
        loginAsNewUser();
        CreatedApiToken mine = actions.createApiToken(new CreateApiTokenInput("mine", null));

        loginAsNewUser();
        assertThat(actions.listApiTokens()).isEmpty();
        assertThatThrownBy(() -> actions.revokeApiToken(new RevokeApiTokenInput(mine.id())))
                .isInstanceOfSatisfying(ActionException.class,
                        e -> assertThat(e.code()).isEqualTo(ActionException.Code.NOT_FOUND));
        assertThat(tokens.authenticate(mine.token())).isPresent();
    }

    @Test
    void validation() {
        loginAsNewUser();
        assertInvalid(() -> actions.createApiToken(new CreateApiTokenInput(" ", null)));
        assertInvalid(() -> actions.createApiToken(new CreateApiTokenInput(null, null)));
        assertInvalid(() -> actions.createApiToken(new CreateApiTokenInput("x".repeat(101), null)));
        assertInvalid(() -> actions.createApiToken(new CreateApiTokenInput("x", 0)));
        assertInvalid(() -> actions.createApiToken(new CreateApiTokenInput("x", 3651)));
        assertInvalid(() -> actions.revokeApiToken(new RevokeApiTokenInput(null)));
    }

    @Test
    void expiredAndUnknownTokensAreRejected() {
        loginAsNewUser();
        CreatedApiToken created = actions.createApiToken(new CreateApiTokenInput("short", 1));
        jdbc.sql("update api_token set expires_at = now() - interval '1 second' where id = ?")
                .param(created.id()).update();

        assertThat(tokens.authenticate(created.token())).isEmpty();
        assertThat(tokens.authenticate(created.token() + "x")).isEmpty();
        assertThat(tokens.authenticate("ynay_pat_nope")).isEmpty();
        assertThat(tokens.authenticate("not-a-pat")).isEmpty();
    }

    @Test
    void lastUsedAtIsUpdatedAtMostOncePerMinute() {
        loginAsNewUser();
        CreatedApiToken created = actions.createApiToken(new CreateApiTokenInput("used", null));

        tokens.authenticate(created.token());
        Instant first = lastUsed(created.id());
        assertThat(first).isNotNull();

        tokens.authenticate(created.token());
        assertThat(lastUsed(created.id())).isEqualTo(first);

        jdbc.sql("update api_token set last_used_at = now() - interval '61 seconds' where id = ?")
                .param(created.id()).update();
        Instant old = lastUsed(created.id());
        tokens.authenticate(created.token());
        assertThat(lastUsed(created.id())).isAfter(old);
        assertThat(actions.listApiTokens().getFirst().lastUsedAt()).isNotNull();
    }

    private Instant lastUsed(UUID id) {
        OffsetDateTime t = jdbc.sql("select last_used_at from api_token where id = ?").param(id)
                .query(OffsetDateTime.class).single();
        return t == null ? null : t.toInstant();
    }

    private static void assertInvalid(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ActionException.class,
                e -> assertThat(e.code()).isEqualTo(ActionException.Code.INVALID_INPUT));
    }
}
