package io.ynay.security.token;

import java.time.Instant;
import java.util.UUID;

/** {@link ApiToken} plus the plaintext {@code token}, returned exactly once by {@code create_api_token}. */
public record CreatedApiToken(
        UUID id,
        String name,
        String tokenPrefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        String token) {
}
