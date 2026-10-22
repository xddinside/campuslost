package com.lostfound.contract;

import com.lostfound.model.ItemType;
import com.lostfound.model.User;

/**
 * Optional adapter supplied by the ownership feature to enforce question rules
 * on create/edit. Fixes persists the field but installs no proof validator.
 */
public interface ItemEvidencePolicy {

    void validate(User actor, MergedItemInput merged);

    record MergedItemInput(
            ItemType type,
            String title,
            String description,
            String category,
            String location,
            String ownershipQuestion,
            boolean ownershipQuestionProvided
    ) {
    }
}
