package io.ynay.security.web;

import io.ynay.security.AppUserRepository;
import io.ynay.security.YnayPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/me}: the signed-in user as {@link io.ynay.security.Me} (same shape as the
 * {@code whoami} action), or 401 so the SPA shows "Sign in with Kanidm".
 */
@RestController
public class MeController {

    private final AppUserRepository users;

    public MeController(AppUserRepository users) {
        this.users = users;
    }

    @GetMapping("/api/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Object principal) {
        if (principal instanceof YnayPrincipal p) {
            var me = users.findMe(p.userId());
            if (me.isPresent()) {
                return ResponseEntity.ok(me.get());
            }
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonSecurityHandlers.UNAUTHENTICATED_BODY);
    }
}
