package io.ynay.security;

import java.util.UUID;

/** The authenticated user, as returned by {@code GET /api/me} and the {@code whoami} action. */
public record Me(UUID userId, String username, String displayName, String email) {
}
