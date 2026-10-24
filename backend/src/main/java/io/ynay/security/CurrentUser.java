package io.ynay.security;

import io.ynay.action.ActionException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the authenticated caller from anywhere on the request thread. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static YnayPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof YnayPrincipal principal) {
            return principal;
        }
        throw new ActionException(ActionException.Code.UNAUTHENTICATED, "Not authenticated");
    }

    public static UUID id() {
        return get().userId();
    }
}
