package io.ynay.security.oidc;

import io.ynay.security.YnayPrincipal;
import java.io.Serial;
import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** A Kanidm OIDC session user that is also a {@link YnayPrincipal} (userId = {@code app_user.id}). */
public class KanidmOidcUser extends DefaultOidcUser implements YnayPrincipal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final String username;

    public KanidmOidcUser(UUID userId, String username, Collection<? extends GrantedAuthority> authorities,
            OidcIdToken idToken, OidcUserInfo userInfo) {
        super(authorities, idToken, userInfo);
        this.userId = userId;
        this.username = username;
    }

    public KanidmOidcUser(UUID userId, String username, OidcUser source) {
        this(userId, username, source.getAuthorities(), source.getIdToken(), source.getUserInfo());
    }

    @Override
    public UUID userId() {
        return userId;
    }

    @Override
    public String username() {
        return username;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KanidmOidcUser other && super.equals(o) && userId.equals(other.userId);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + userId.hashCode();
    }
}
