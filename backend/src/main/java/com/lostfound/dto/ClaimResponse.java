package com.lostfound.dto;

import com.lostfound.model.ClaimStatus;

import java.time.Instant;

public record ClaimResponse(
        Long id,
        Long itemId,
        String itemTitle,
        Long claimantId,
        String claimantName,
        String claimantEmail,
        String message,
        ClaimStatus status,
        Instant createdAt
) {
}
