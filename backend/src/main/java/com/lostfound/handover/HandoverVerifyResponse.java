package com.lostfound.handover;

import com.lostfound.dto.ClaimResponse;

import java.time.Instant;

/** Authorized claim result plus the timestamp recorded on the verified handover row. */
public record HandoverVerifyResponse(ClaimResponse claim, Instant verifiedAt) {
}
