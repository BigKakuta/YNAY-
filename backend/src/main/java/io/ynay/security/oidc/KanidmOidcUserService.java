package io.ynay.security.oidc;

import io.ynay.security.AppUserRepository;
import io.ynay.security.YnayUser;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.StringUtils;

/**
 * Loads the OIDC user (ID token + userinfo) and provisions the matching {@code app_user} row on
 * every login, keyed by (issuer, subject).
 */
public class KanidmOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final AppUserRepository users;

    public KanidmOidcUserService(AppUserRepository users) {
        this(new OidcUserService(), users);
    }

    KanidmOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, AppUserRepository users) {
        this.delegate = delegate;
        this.users = users;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidc = delegate.loadUser(request);
        YnayUser user = provision(users, oidc);
        Set<GrantedAuthority> authorities = new HashSet<>(oidc.getAuthorities());
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        return new KanidmOidcUser(user.userId(), user.username(), authorities, oidc.getIdToken(), oidc.getUserInfo());
    }

    static YnayUser provision(AppUserRepository users, OidcUser oidc) {
        String issuer = oidc.getIssuer().toString();
        String subject = oidc.getSubject();
        // Kanidm's preferred_username is the SPN ("name@domain") unless the client is configured
        // with prefer-short-username; store whatever we get.
        String username = firstNonBlank(oidc.getPreferredUsername(), oidc.getEmail(), subject);
        String displayName = firstNonBlank(oidc.getFullName(), oidc.getGivenName(), null);
        String email = StringUtils.hasText(oidc.getEmail()) ? oidc.getEmail() : null;
        return users.upsertFromLogin(issuer, subject, username, displayName, email);
    }

    private static String firstNonBlank(String a, String b, String c) {
        if (StringUtils.hasText(a)) {
            return a;
        }
        if (StringUtils.hasText(b)) {
            return b;
        }
        return c;
    }
}
