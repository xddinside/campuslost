package com.lostfound.dto;

import com.lostfound.model.ClaimStatus;
import com.lostfound.model.ItemType;

import java.time.Instant;

/**
 * Authorized claim view. Never contains ownership answers or contact details.
 */
public record ClaimResponse(
        Long id,
        Long itemId,
        ItemType itemType,
        String itemTitle,
        Long reporterId,
        Long claimantId,
        String claimantName,
        String message,
        ClaimStatus status,
        Long lostReportId,
        long version,
        Instant createdAt,
        Instant decidedAt,
        Instant completedAt
) {
}
