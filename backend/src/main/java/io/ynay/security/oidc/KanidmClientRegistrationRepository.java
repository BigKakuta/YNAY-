package io.ynay.security.oidc;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Client registrations from {@code spring.security.oauth2.client.*}, resolved lazily.
 *
 * <p>Spring Boot's default repository performs OIDC discovery against the issuer at startup, so
 * the app would not boot while Kanidm is down. This one performs discovery on first use (the
 * first login attempt) and retries on the next request when Kanidm is unreachable.
 *
 * <p>When Kanidm is not configured ({@code YNAY_KANIDM_URL} unset) the repository is empty and
 * login is disabled.
 */
public class KanidmClientRegistrationRepository implements ClientRegistrationRepository, Iterable<ClientRegistration> {

    public static final String REGISTRATION_ID = "kanidm";

    private static final Logger log = LoggerFactory.getLogger(KanidmClientRegistrationRepository.class);

    private final OAuth2ClientProperties properties;
    private final boolean enabled;
    private volatile Map<String, ClientRegistration> registrations;

    public KanidmClientRegistrationRepository(OAuth2ClientProperties properties, boolean enabled) {
        this.properties = properties;
        this.enabled = enabled;
    }

    /** Whether a Kanidm login is configured at all (independent of Kanidm being reachable). */
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!enabled || !properties.getRegistration().containsKey(registrationId)) {
            return null;
        }
        return resolve().get(registrationId);
    }

    /** The resolved registrations, or empty if not configured / not yet resolvable (never throws). */
    public List<ClientRegistration> resolvedIfPossible() {
        if (!enabled) {
            return List.of();
        }
        try {
            return List.copyOf(resolve().values());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public Iterator<ClientRegistration> iterator() {
        return resolvedIfPossible().iterator();
    }

    private Map<String, ClientRegistration> resolve() {
        Map<String, ClientRegistration> current = registrations;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (registrations == null) {
                try {
                    registrations = Collections.unmodifiableMap(
                            new OAuth2ClientPropertiesMapper(properties).asClientRegistrations());
                    log.info("Resolved OIDC client registrations {}", registrations.keySet());
                } catch (RuntimeException e) {
                    log.warn("OIDC discovery failed (is Kanidm reachable?): {}", e.getMessage());
                    throw new IllegalStateException("Identity provider unavailable: " + e.getMessage(), e);
                }
            }
            return registrations;
        }
    }
}
