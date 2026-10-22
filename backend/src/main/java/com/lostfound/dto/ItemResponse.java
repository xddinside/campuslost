package com.lostfound.dto;

import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;

import java.time.Instant;

/**
 * Public item shape. Intentionally contains no email, phone, ownership
 * question/answer, or claimant data.
 */
public record ItemResponse(
        Long id,
        ItemType type,
        String title,
        String description,
        String category,
        String location,
        Instant dateOccurred,
        ItemStatus status,
        Long reporterId,
        String reporterName,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        long version,
        String photoUrl,
        long pendingClaims
) {
}
