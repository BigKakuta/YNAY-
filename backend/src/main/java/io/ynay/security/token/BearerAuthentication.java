package io.ynay.security.token;

import io.ynay.security.YnayPrincipal;
import java.io.Serial;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A request authenticated with a bearer token on {@code /mcp}. The principal is a {@link YnayPrincipal}. */
public class BearerAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Kind { API_TOKEN, KANIDM_ACCESS_TOKEN }

    private final YnayPrincipal principal;
    private final Kind kind;

    public BearerAuthentication(YnayPrincipal principal, Kind kind) {
        super(List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority(kind == Kind.API_TOKEN ? "AUTH_API_TOKEN" : "AUTH_KANIDM_TOKEN")));
        this.principal = principal;
        this.kind = kind;
        setAuthenticated(true);
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public Object getCredentials() {
        return null; // the token is not retained
    }

    @Override
    public YnayPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.username();
    }
}
