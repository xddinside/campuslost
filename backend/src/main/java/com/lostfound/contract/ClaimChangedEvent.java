package com.lostfound.contract;

import com.lostfound.model.ClaimStatus;
import com.lostfound.model.ItemType;

/** Immutable claim transition event published inside the writing transaction. */
public record ClaimChangedEvent(
        Long claimId,
        Long itemId,
        ItemType itemType,
        Long lostReportId,
        Long claimantId,
        Long reporterId,
        ClaimStatus status,
        boolean handoverVerified
) {
}
