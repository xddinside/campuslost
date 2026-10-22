package com.lostfound.ownership;

import com.lostfound.contract.ClaimEvidencePolicy;
import com.lostfound.contract.ItemEvidencePolicy;
import com.lostfound.dto.ClaimRequest;
import com.lostfound.exception.ApiException;
import com.lostfound.model.Item;
import com.lostfound.model.ItemType;
import com.lostfound.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The ownership feature's implementation of the two optional evidence hooks.
 *
 * <p>Item-side validation requires a meaningful prompt on every newly created
 * FOUND report and rejects prompts on LOST reports. Claim-side validation runs
 * inside {@code ClaimWorkflow.create}, under the target item's pessimistic
 * lock, so the saved snapshot is exactly the prompt the claimant answered.</p>
 */
@Component
public class OwnershipEvidencePolicy implements ItemEvidencePolicy, ClaimEvidencePolicy {

    public static final int MIN_QUESTION_LENGTH = 10;
    public static final int MAX_QUESTION_LENGTH = 200;
    public static final int MIN_ANSWER_LENGTH = 10;
    public static final int MAX_ANSWER_LENGTH = 2000;

    @Override
    public void validate(User actor, MergedItemInput merged) {
        String question = trimToNull(merged.ownershipQuestion());

        if (merged.type() == ItemType.LOST) {
            if (question != null) {
                throw fieldError("INVALID_QUESTION",
                        "Lost reports cannot ask an ownership question.",
                        "ownershipQuestion", "Lost reports cannot ask an ownership question.");
            }
            return;
        }

        if (question == null) {
            // On create, ItemService marks "provided" only when the submitted
            // prompt survived trimming. An edit that explicitly clears the
            // field is still allowed, which keeps legacy reports editable.
            if (!merged.ownershipQuestionProvided()) {
                throw fieldError("INVALID_QUESTION",
                        "Add an ownership question only the true owner could answer.",
                        "ownershipQuestion",
                        "Add an ownership question of 10-200 characters.");
            }
            return;
        }

        if (question.length() < MIN_QUESTION_LENGTH) {
            throw fieldError("INVALID_QUESTION",
                    "Ownership question must be at least 10 characters.",
                    "ownershipQuestion",
                    "Use at least " + MIN_QUESTION_LENGTH + " characters.");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw fieldError("INVALID_QUESTION",
                    "Ownership question must be at most 200 characters.",
                    "ownershipQuestion",
                    "Use at most " + MAX_QUESTION_LENGTH + " characters.");
        }
    }

    @Override
    public PreparedEvidence prepare(Item item, User claimant, ClaimRequest request) {
        if (item.getType() != ItemType.FOUND) {
            throw fieldError("INVALID_EVIDENCE",
                    "This report does not ask an ownership question.",
                    "ownershipAnswer", "This report does not accept an ownership answer.");
        }

        String question = trimToNull(item.getOwnershipQuestion());
        if (question == null) {
            return null;
        }

        String answer = trimToNull(request.ownershipAnswer());
        if (answer == null) {
            throw fieldError("INVALID_EVIDENCE",
                    "Answer the finder's ownership question.",
                    "ownershipAnswer", "Answer the finder's ownership question.");
        }
        if (answer.length() < MIN_ANSWER_LENGTH) {
            throw fieldError("INVALID_EVIDENCE",
                    "Ownership answer must be at least 10 characters.",
                    "ownershipAnswer", "Use at least " + MIN_ANSWER_LENGTH + " characters.");
        }
        if (answer.length() > MAX_ANSWER_LENGTH) {
            throw fieldError("INVALID_EVIDENCE",
                    "Ownership answer must be at most 2000 characters.",
                    "ownershipAnswer", "Use at most " + MAX_ANSWER_LENGTH + " characters.");
        }

        return new PreparedEvidence(question, answer);
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException fieldError(String code, String message,
                                           String field, String fieldMessage) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message,
                Map.of(field, fieldMessage));
    }
}
