package com.lostfound.handover;

import com.lostfound.dto.ClaimResponse;
import com.lostfound.dto.ErrorResponse;
import com.lostfound.model.User;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/claims/{claimId}/handover")
public class HandoverController {

    private final HandoverService handoverService;

    public HandoverController(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    @PostMapping("/code")
    public ResponseEntity<HandoverCodeResponse> issue(@AuthenticationPrincipal User user,
                                                       @PathVariable Long claimId) {
        HandoverCodeResponse response = handoverService.issue(user, claimId);
        return privateResponse(response);
    }

    @GetMapping
    public ResponseEntity<HandoverStatusResponse> status(@AuthenticationPrincipal User user,
                                                          @PathVariable Long claimId) {
        return privateResponse(handoverService.status(user, claimId));
    }

    @PostMapping("/verify")
    public ResponseEntity<HandoverVerifyResponse> verify(@AuthenticationPrincipal User user,
                                                          @PathVariable Long claimId,
                                                          @Valid @RequestBody HandoverVerifyRequest request) {
        return privateResponse(handoverService.verify(user, claimId, request.code()));
    }

    @PostMapping("/release")
    public ResponseEntity<ClaimResponse> release(@AuthenticationPrincipal User user,
                                                  @PathVariable Long claimId,
                                                  @Valid @RequestBody HandoverReleaseRequest request) {
        return privateResponse(handoverService.release(user, claimId, request.reason()));
    }

    @ExceptionHandler(HandoverRateLimitException.class)
    public ResponseEntity<ErrorResponse> rateLimited(HandoverRateLimitException ex) {
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(ex.getCode(), ex.getMessage(), null));
    }

    private <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }
}
