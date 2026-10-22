package com.lostfound.matching;

import com.lostfound.model.Role;
import com.lostfound.model.User;
import com.lostfound.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit maintenance path for the deterministic catch-up used on startup.
 * A failed after-commit job is also retried automatically, but an admin can
 * force a complete bounded refresh during a demo or after a database restore.
 */
@RestController
@RequestMapping("/api/admin/matches")
public class MatchAdminController {

    private final MatchRefreshService refreshService;

    public MatchAdminController(MatchRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @PostMapping("/refresh")
    public MatchRefreshResponse refresh(@AuthenticationPrincipal User user) {
        if (user == null || user.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only an administrator can refresh match snapshots.");
        }
        MatchRefreshService.RefreshSummary summary = refreshService.refreshAll();
        return new MatchRefreshResponse(
                summary.affectedItems(), summary.storedPairs(), summary.publishedEvents());
    }

    public record MatchRefreshResponse(int refreshedItems, int storedPairs, int publishedEvents) {
    }
}
