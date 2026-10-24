package io.ynay.security.token;

import java.time.Instant;
import java.util.UUID;

/** A personal API token (metadata only; the secret is never stored or returned again). */
public record ApiToken(
        UUID id,
        String name,
        String tokenPrefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt) {
}
