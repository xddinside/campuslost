package com.lostfound.handover;

import java.time.Instant;

/** Status view for participants. Never contains the code or its digest. */
public record HandoverStatusResponse(
        Long claimId,
        HandoverState state,
        Instant expiresAt,
        Integer attemptsRemaining,
        Instant verifiedAt
) {
}
