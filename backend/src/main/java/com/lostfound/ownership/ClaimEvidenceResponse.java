package com.lostfound.ownership;

/**
 * Private claim evidence. The question is the snapshot stored when the claim
 * was created, never the report's current prompt.
 */
public record ClaimEvidenceResponse(Long claimId, String question, String answer) {
}
