package com.lostfound.dto;

import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;

import java.time.Instant;

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
        String reporterEmail,
        String reporterPhone,
        Instant createdAt,
        Instant resolvedAt,
        long pendingClaims
) {
}
