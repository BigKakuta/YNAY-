package io.ynay.security.token;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

/** Authenticates {@code Authorization: Bearer ynay_pat_...} personal API tokens. */
public class ApiTokenAuthenticationProvider implements AuthenticationProvider {

    private final ApiTokenService tokens;

    public ApiTokenAuthenticationProvider(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = ((BearerTokenAuthenticationToken) authentication).getToken();
        if (!token.startsWith(ApiTokenService.TOKEN_PREFIX)) {
            return null; // not ours: let the next provider (Kanidm introspection) try
        }
        return tokens.authenticate(token)
                .map(user -> new BearerAuthentication(user, BearerAuthentication.Kind.API_TOKEN))
                .orElseThrow(() -> new InvalidBearerTokenException("Invalid, expired or revoked API token"));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
