package io.ynay.security.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/**
 * After {@code POST /logout}: goes to {@code /}, or to Kanidm's {@code end_session_endpoint}
 * (RP-initiated logout) when the provider's discovery document advertises one.
 *
 * <p>Browsers posting a form get a 302. Clients sending {@code Accept: application/json} (the SPA's
 * fetch) get {@code 200 {"redirectUrl": "..."}} and navigate there themselves, since a
 * cross-origin redirect cannot be followed by fetch.
 */
public class YnayLogoutSuccessHandler implements LogoutSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(YnayLogoutSuccessHandler.class);

    private final RpInitiated rpInitiated;

    /** @param registrations Kanidm registrations, or {@code null} when login is not configured */
    public YnayLogoutSuccessHandler(ClientRegistrationRepository registrations, String publicUrl) {
        if (registrations == null) {
            this.rpInitiated = null;
        } else {
            this.rpInitiated = new RpInitiated(registrations);
            this.rpInitiated.setPostLogoutRedirectUri(publicUrl + "/");
            this.rpInitiated.setDefaultTargetUrl("/");
        }
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        String target = "/";
        if (rpInitiated != null && authentication != null) {
            try {
                target = rpInitiated.targetUrl(request, response, authentication);
            } catch (RuntimeException e) {
                log.debug("RP-initiated logout unavailable: {}", e.getMessage());
            }
        }
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"redirectUrl\":\"" + target.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        } else {
            response.sendRedirect(target);
        }
    }

    private static final class RpInitiated extends OidcClientInitiatedLogoutSuccessHandler {
        RpInitiated(ClientRegistrationRepository registrations) {
            super(registrations);
        }

        String targetUrl(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
            return determineTargetUrl(request, response, authentication);
        }
    }
}
