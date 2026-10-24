package io.ynay.security;

import io.ynay.security.oidc.IdpUnavailableFilter;
import io.ynay.security.oidc.KanidmClientRegistrationRepository;
import io.ynay.security.oidc.KanidmOidcUserService;
import io.ynay.security.token.ApiTokenAuthenticationProvider;
import io.ynay.security.token.ApiTokenService;
import io.ynay.security.token.KanidmIntrospectionAuthenticationProvider;
import io.ynay.security.web.JsonSecurityHandlers;
import io.ynay.security.web.ProtectedResourceMetadataController;
import io.ynay.security.web.SpaCsrfTokenRequestHandler;
import io.ynay.security.web.YnayLogoutSuccessHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.introspection.RestClientOpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

/**
 * Two filter chains:
 * <ul>
 *   <li>{@code /mcp}: stateless bearer auth (personal API tokens, optionally Kanidm access tokens
 *       via introspection); 401 carries the RFC 9728 {@code resource_metadata} pointer.</li>
 *   <li>everything else: Kanidm OIDC login (auth code + PKCE, ES256 ID tokens) with a session
 *       cookie, CSRF cookie for the SPA, {@code /api/**} protected with JSON 401s and all other
 *       paths public (SPA, static assets, health, metadata).</li>
 * </ul>
 * There is no local password login of any kind.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({YnaySecurityProperties.class, OAuth2ClientProperties.class})
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Always present (empty when Kanidm is not configured) so Boot's eager, startup-time OIDC
     * discovery never runs.
     */
    @Bean
    KanidmClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties clientProperties,
            YnaySecurityProperties props) {
        boolean enabled = props.kanidm().configured();
        if (enabled) {
            log.info("Kanidm login enabled: issuer {}", props.kanidm().issuer());
            if (!StringUtils.hasText(props.kanidm().clientSecret())) {
                log.warn("YNAY_KANIDM_CLIENT_SECRET is not set; Kanidm will reject the token request");
            }
        } else {
            log.warn("YNAY_KANIDM_URL is not set: login is disabled (only API tokens on /mcp work)");
        }
        return new KanidmClientRegistrationRepository(clientProperties, enabled);
    }

    /** Kanidm signs ID tokens with ES256 by default; Spring assumes RS256. */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(YnaySecurityProperties props) {
        SignatureAlgorithm alg = SignatureAlgorithm.from(props.kanidm().idTokenAlg().trim().toUpperCase());
        if (alg == null) {
            throw new IllegalStateException("Unsupported YNAY_KANIDM_ID_TOKEN_ALG: " + props.kanidm().idTokenAlg());
        }
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwsAlgorithmResolver(registration -> alg);
        return factory;
    }

    @Bean
    ServletContextInitializer sessionCookieSecureInitializer(YnaySecurityProperties props) {
        // HttpOnly and SameSite=Lax come from server.servlet.session.cookie.*; Secure follows the public URL.
        return servletContext -> servletContext.getSessionCookieConfig().setSecure(props.httpsPublicUrl());
    }

    @Bean
    @Order(1)
    SecurityFilterChain mcpFilterChain(HttpSecurity http, YnaySecurityProperties props, ApiTokenService tokens,
            AppUserRepository users) throws Exception {
        BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
        String metadataUrl = props.publicUrl() + ProtectedResourceMetadataController.PATH;
        entryPoint.setResourceMetadataParameterResolver(request -> metadataUrl);
        AuthenticationManager bearerAuth = bearerAuthenticationManager(props, tokens, users);

        http.securityMatcher("/mcp", "/mcp/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(rs -> rs
                        .authenticationManagerResolver(request -> bearerAuth)
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    static AuthenticationManager bearerAuthenticationManager(YnaySecurityProperties props, ApiTokenService tokens,
            AppUserRepository users) {
        ApiTokenAuthenticationProvider apiTokens = new ApiTokenAuthenticationProvider(tokens);
        KanidmIntrospectionAuthenticationProvider introspection = null;
        if (props.mcp().introspection()) {
            if (!props.kanidm().configured()) {
                throw new IllegalStateException("YNAY_MCP_INTROSPECTION=true requires YNAY_KANIDM_URL");
            }
            var introspector = RestClientOpaqueTokenIntrospector
                    .withIntrospectionUri(props.kanidm().introspectionUri())
                    .clientId(props.kanidm().clientId())
                    .clientSecret(props.kanidm().clientSecret())
                    .build();
            introspection = new KanidmIntrospectionAuthenticationProvider(
                    introspector, users, props.kanidm().issuer(), props.acceptedTokenAudiences());
            log.info("MCP accepts Kanidm access tokens for clients {} via {}",
                    props.acceptedTokenAudiences(), props.kanidm().introspectionUri());
        }
        KanidmIntrospectionAuthenticationProvider kanidm = introspection;
        return authentication -> {
            String token = ((BearerTokenAuthenticationToken) authentication).getToken();
            if (token.startsWith(ApiTokenService.TOKEN_PREFIX)) {
                return apiTokens.authenticate(authentication);
            }
            if (kanidm != null) {
                return kanidm.authenticate(authentication);
            }
            throw new InvalidBearerTokenException("Unsupported bearer token: use a YNAY API token (ynay_pat_...)");
        };
    }

    @Bean
    @Order(2)
    SecurityFilterChain webFilterChain(HttpSecurity http, YnaySecurityProperties props,
            KanidmClientRegistrationRepository registrations, AppUserRepository users) throws Exception {
        RequestMatcher api = PathPatternRequestMatcher.pathPattern("/api/**");

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(c -> c.secure(props.httpsPublicUrl()).sameSite("Lax"));

        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(api).authenticated()
                        // SPA, static assets, /actuator/health, /.well-known/**, /oauth2/**, /login/**
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(JsonSecurityHandlers.unauthorized(), api)
                        .defaultAccessDeniedHandlerFor(JsonSecurityHandlers.forbidden(), api))
                .requestCache(rc -> rc.requestCache(new NullRequestCache()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(new YnayLogoutSuccessHandler(
                                registrations.isEnabled() ? registrations : null, props.publicUrl())));

        if (registrations.isEnabled()) {
            DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                    registrations, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
            // Kanidm requires PKCE (S256) for confidential clients too.
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(login -> login
                    .loginPage("/")
                    .authorizationEndpoint(a -> a.authorizationRequestResolver(resolver))
                    .userInfoEndpoint(u -> u.oidcUserService(new KanidmOidcUserService(users)))
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/?login_error"))
                    .addFilterBefore(new IdpUnavailableFilter(registrations),
                            OAuth2AuthorizationRequestRedirectFilter.class);
        }
        return http.build();
    }
}
