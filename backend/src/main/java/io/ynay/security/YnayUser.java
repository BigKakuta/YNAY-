package io.ynay.security;

import java.util.UUID;

/** Plain principal used for API tokens and tests. */
public record YnayUser(UUID userId, String username) implements YnayPrincipal {
}
