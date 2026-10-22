package com.lostfound.contract;

import com.lostfound.dto.ClaimRequest;
import com.lostfound.model.Item;
import com.lostfound.model.User;

/**
 * Optional adapter supplied by the ownership feature. It validates the current
 * prompt and answer under the claim transaction's lock and returns the exact
 * evidence to persist atomically with the claim.
 */
public interface ClaimEvidencePolicy {

    /**
     * @return evidence to persist, or {@code null} when the item has no question
     *         and the claim needs no evidence.
     * @throws com.lostfound.exception.ApiException for invalid or unavailable evidence
     */
    PreparedEvidence prepare(Item item, User claimant, ClaimRequest request);

    record PreparedEvidence(String questionSnapshot, String answer) {
    }
}
