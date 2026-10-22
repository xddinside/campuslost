package com.lostfound.ownership;

import com.lostfound.model.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private ownership prompts and claim evidence reads. */
@RestController
@RequestMapping("/api")
public class OwnershipController {

    private final OwnershipService ownershipService;

    public OwnershipController(OwnershipService ownershipService) {
        this.ownershipService = ownershipService;
    }

    @GetMapping("/items/{itemId}/ownership-question")
    public OwnershipQuestionResponse question(@AuthenticationPrincipal User user,
                                              @PathVariable Long itemId) {
        return ownershipService.question(user, itemId);
    }

    @GetMapping("/claims/{claimId}/evidence")
    public ClaimEvidenceResponse evidence(@AuthenticationPrincipal User user,
                                          @PathVariable Long claimId) {
        return ownershipService.evidence(user, claimId);
    }
}
