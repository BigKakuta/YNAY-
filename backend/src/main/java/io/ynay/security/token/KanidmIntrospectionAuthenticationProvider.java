package io.ynay.security.token;

import io.ynay.security.AppUserRepository;
import io.ynay.security.YnayUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2TokenIntrospectionClaimAccessor;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.util.StringUtils;

/**
 * Accepts Kanidm-issued OAuth2 access tokens on {@code /mcp} by asking Kanidm (RFC 7662
 * introspection, authenticated with YNAY's client id/secret). Enabled with
 * {@code YNAY_MCP_INTROSPECTION=true}.
 *
 * <p>The token's subject is mapped to {@code app_user} under the login issuer
 * ({@code <kanidm>/oauth2/openid/<client_id>}): Kanidm subjects are the person's UUID and stable
 * across all clients of one Kanidm instance, so a token minted for another client (e.g. an MCP
 * client registered separately in Kanidm) resolves to the same YNAY user as a web login. Unknown
 * users are provisioned. Only tokens whose {@code client_id}/{@code aud} is in the accepted list
 * are allowed, so tokens for unrelated applications are rejected.
 *
 * <p>Successful results are cached briefly so a burst of MCP calls costs one introspection.
 */
public class KanidmIntrospectionAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(KanidmIntrospectionAuthenticationProvider.class);
    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final int CACHE_MAX = 1000;

    private final OpaqueTokenIntrospector introspector;
    private final AppUserRepository users;
    private final String issuer;
    private final List<String> acceptedAudiences;
    private final Clock clock;
    private final Map<String, CachedUser> cache = new ConcurrentHashMap<>();

    private record CachedUser(YnayUser user, Instant until) {
    }

    public KanidmIntrospectionAuthenticationProvider(OpaqueTokenIntrospector introspector, AppUserRepository users,
            String issuer, List<String> acceptedAudiences) {
        this(introspector, users, issuer, acceptedAudiences, Clock.systemUTC());
    }

    KanidmIntrospectionAuthenticationProvider(OpaqueTokenIntrospector introspector, AppUserRepository users,
            String issuer, List<String> acceptedAudiences, Clock clock) {
        this.introspector = introspector;
        this.users = users;
        this.issuer = issuer;
        this.acceptedAudiences = List.copyOf(acceptedAudiences);
        this.clock = clock;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = ((BearerTokenAuthenticationToken) authentication).getToken();
        if (token.startsWith(ApiTokenService.TOKEN_PREFIX)) {
            return null; // API tokens are never sent to Kanidm
        }
        String key = ApiTokenService.hash(token);
        Instant now = clock.instant();
        CachedUser cached = cache.get(key);
        if (cached != null && cached.until().isAfter(now)) {
            return new BearerAuthentication(cached.user(), BearerAuthentication.Kind.KANIDM_ACCESS_TOKEN);
        }

        OAuth2AuthenticatedPrincipal principal;
        try {
            principal = introspector.introspect(token);
        } catch (BadOpaqueTokenException e) {
            throw new InvalidBearerTokenException(e.getMessage(), e);
        } catch (OAuth2IntrospectionException e) {
            log.warn("Kanidm token introspection failed: {}", e.getMessage());
            throw new AuthenticationServiceException(e.getMessage(), e);
        }
        if (!(principal instanceof OAuth2TokenIntrospectionClaimAccessor claims)) {
            throw new AuthenticationServiceException("Unexpected introspection principal " + principal.getClass());
        }
        if (!audienceAccepted(claims)) {
            throw new InvalidBearerTokenException("Token was not issued for this application");
        }
        String subject = claims.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new InvalidBearerTokenException("Token has no subject");
        }
        String username = StringUtils.hasText(claims.getUsername()) ? claims.getUsername() : subject;
        YnayUser user = users.upsertFromBearer(issuer, subject, username);

        Instant until = now.plus(CACHE_TTL);
        Instant exp = claims.getExpiresAt();
        if (exp != null && exp.isBefore(until)) {
            until = exp;
        }
        if (cache.size() >= CACHE_MAX) {
            cache.values().removeIf(c -> !c.until().isAfter(now));
            if (cache.size() >= CACHE_MAX) {
                cache.clear();
            }
        }
        cache.put(key, new CachedUser(user, until));
        return new BearerAuthentication(user, BearerAuthentication.Kind.KANIDM_ACCESS_TOKEN);
    }

    private boolean audienceAccepted(OAuth2TokenIntrospectionClaimAccessor claims) {
        if (claims.getClientId() != null && acceptedAudiences.contains(claims.getClientId())) {
            return true;
        }
        List<String> aud = claims.getAudience();
        return aud != null && aud.stream().anyMatch(acceptedAudiences::contains);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
