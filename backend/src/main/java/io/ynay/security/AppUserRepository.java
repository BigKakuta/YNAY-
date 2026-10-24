package io.ynay.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Access to {@code app_user}: users are provisioned from Kanidm identities keyed by (issuer, subject). */
@Repository
public class AppUserRepository {

    private final JdbcClient jdbc;

    public AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates or refreshes the user for an interactive OIDC login. Profile fields come from the
     * ID token / userinfo and always overwrite the stored values.
     */
    public YnayUser upsertFromLogin(String issuer, String subject, String username, String displayName, String email) {
        UUID id = jdbc.sql("""
                insert into app_user (id, oidc_issuer, oidc_subject, username, display_name, email, last_login_at)
                values (:id, :iss, :sub, :username, :displayName, :email, now())
                on conflict (oidc_issuer, oidc_subject) do update
                   set username = excluded.username,
                       display_name = excluded.display_name,
                       email = excluded.email,
                       last_login_at = now()
                returning id""")
                .param("id", UUID.randomUUID())
                .param("iss", issuer)
                .param("sub", subject)
                .param("username", username)
                .param("displayName", displayName)
                .param("email", email)
                .query(UUID.class)
                .single();
        return new YnayUser(id, username);
    }

    /**
     * Resolves the user behind an introspected bearer token. Known users keep their profile (the
     * introspection response carries less information than a login); unknown ones are created.
     */
    public YnayUser upsertFromBearer(String issuer, String subject, String username) {
        return jdbc.sql("""
                insert into app_user (id, oidc_issuer, oidc_subject, username)
                values (:id, :iss, :sub, :username)
                on conflict (oidc_issuer, oidc_subject) do update set oidc_subject = excluded.oidc_subject
                returning id, username""")
                .param("id", UUID.randomUUID())
                .param("iss", issuer)
                .param("sub", subject)
                .param("username", username)
                .query((rs, n) -> new YnayUser(rs.getObject("id", UUID.class), rs.getString("username")))
                .single();
    }

    public Optional<Me> findMe(UUID userId) {
        return jdbc.sql("select id, username, display_name, email from app_user where id = ?")
                .param(userId)
                .query((rs, n) -> new Me(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("email")))
                .optional();
    }
}
