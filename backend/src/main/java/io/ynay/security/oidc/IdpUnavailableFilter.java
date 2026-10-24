package io.ynay.security.oidc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * When a login is started ({@code /oauth2/authorization/**}) but Kanidm's discovery document
 * cannot be fetched, sends the browser back to {@code /?login_error=idp_unavailable} instead of a
 * 500 page. Discovery is retried on the next attempt.
 */
public class IdpUnavailableFilter extends OncePerRequestFilter {

    private final KanidmClientRegistrationRepository registrations;

    public IdpUnavailableFilter(KanidmClientRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/oauth2/authorization/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (registrations.isEnabled() && registrations.resolvedIfPossible().isEmpty()) {
            response.sendRedirect(request.getContextPath() + "/?login_error=idp_unavailable");
            return;
        }
        chain.doFilter(request, response);
    }
}
