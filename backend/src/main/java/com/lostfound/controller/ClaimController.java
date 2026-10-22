package com.lostfound.controller;

import com.lostfound.dto.ClaimDecisionRequest;
import com.lostfound.dto.ClaimRequest;
import com.lostfound.dto.ClaimResponse;
import com.lostfound.model.User;
import com.lostfound.service.ClaimService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ClaimController {

    private final ClaimService claimService;

    public ClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    @PostMapping("/items/{itemId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse create(@AuthenticationPrincipal User user,
                                @PathVariable Long itemId,
                                @Valid @RequestBody ClaimRequest request) {
        return claimService.create(user, itemId, request);
    }

    @GetMapping("/items/{itemId}/claims")
    public List<ClaimResponse> listForItem(@AuthenticationPrincipal User user,
                                           @PathVariable Long itemId) {
        return claimService.listForItem(user, itemId);
    }

    @GetMapping("/me/claims")
    public List<ClaimResponse> mine(@AuthenticationPrincipal User user) {
        return claimService.mine(user);
    }

    @DeleteMapping("/claims/{claimId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal User user,
                       @PathVariable Long claimId) {
        claimService.cancel(user, claimId);
    }

    @PatchMapping("/claims/{claimId}")
    public ClaimResponse decide(@AuthenticationPrincipal User user,
                                @PathVariable Long claimId,
                                @Valid @RequestBody ClaimDecisionRequest request) {
        return claimService.decide(user, claimId, request);
    }
}
