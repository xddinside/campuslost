package com.lostfound.handover;

import java.time.Instant;

/** Plaintext exists only in this response and in the claimant's component memory. */
public record HandoverCodeResponse(String code, Instant expiresAt) {
}
