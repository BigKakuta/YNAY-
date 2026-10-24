package io.ynay.support;

import io.ynay.security.YnayUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Base class for integration tests: boots the full application against a Postgres
 * Testcontainer. Use {@link #createUser(String)} + {@link #loginAs(YnayUser)} to run
 * code as a given user (sets the SecurityContext on the current thread).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    protected YnayUser createUser(String username) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into app_user (id, oidc_issuer, oidc_subject, username, display_name)
                values (?, 'https://idm.test/oauth2/openid/ynay', ?, ?, ?)""")
                .params(id, id.toString(), username, username)
                .update();
        return new YnayUser(id, username);
    }

    protected YnayUser loginAs(YnayUser user) {
        var auth = new TestingAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        return user;
    }

    protected YnayUser loginAsNewUser() {
        return loginAs(createUser("user-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
}
