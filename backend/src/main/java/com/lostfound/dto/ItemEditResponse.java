package com.lostfound.dto;

import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;

import java.time.Instant;

/** Reporter/admin-only editable view, including private fields. */
public record ItemEditResponse(
        Long id,
        ItemType type,
        String title,
        String description,
        String category,
        String location,
        Instant dateOccurred,
        ItemStatus status,
        long version,
        String photoId,
        String photoUrl,
        String ownershipQuestion
) {
}
