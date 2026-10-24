package io.ynay.security;

import java.util.UUID;

/**
 * Implemented by every authentication principal the application accepts
 * (Kanidm OIDC session users, API-token users, introspected Kanidm bearer tokens).
 */
public interface YnayPrincipal {

    /** Primary key of the {@code app_user} row. */
    UUID userId();

    String username();
}
