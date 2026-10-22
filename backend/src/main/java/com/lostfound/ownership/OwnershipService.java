package com.lostfound.ownership;

import com.lostfound.exception.ApiException;
import com.lostfound.model.Claim;
import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import com.lostfound.model.Role;
import com.lostfound.model.User;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.service.ItemService;
import com.lostfound.workflow.ClaimWorkflow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorized, read-only access to ownership prompts and private claim evidence.
 * Identity always comes from authentication; no endpoint accepts a reader ID.
 */
@Service
public class OwnershipService {

    private final ItemService itemService;
    private final ClaimRepository claimRepository;
    private final ClaimWorkflow claimWorkflow;

    public OwnershipService(ItemService itemService,
                            ClaimRepository claimRepository,
                            ClaimWorkflow claimWorkflow) {
        this.itemService = itemService;
        this.claimRepository = claimRepository;
        this.claimWorkflow = claimWorkflow;
    }

    @Transactional(readOnly = true)
    public OwnershipQuestionResponse question(User reader, Long itemId) {
        if (reader == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Sign in to see the ownership question.");
        }
        Item item = itemService.findVisible(itemId);
        if (item.getType() != ItemType.FOUND) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ITEM_TYPE",
                    "Only found reports ask an ownership question.");
        }

        boolean reviewer = claimWorkflow.canReview(reader, item);
        if (!reviewer && item.getStatus() != ItemStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_NOT_OPEN",
                    "This report is no longer accepting responses.");
        }

        String question = OwnershipEvidencePolicy.trimToNull(item.getOwnershipQuestion());
        return new OwnershipQuestionResponse(item.getId(), question, question != null);
    }

    @Transactional(readOnly = true)
    public ClaimEvidenceResponse evidence(User reader, Long claimId) {
        if (reader == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Sign in to see private claim evidence.");
        }
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Claim not found."));

        Item target = claim.getItem();
        boolean claimant = claim.getClaimant().getId().equals(reader.getId());
        boolean reporter = target.getReporter().getId().equals(reader.getId());
        boolean admin = reader.getRole() == Role.ADMIN;
        if (!claimant && !reporter && !admin) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the claimant, the report owner, or an administrator can read this evidence.");
        }

        return new ClaimEvidenceResponse(
                claim.getId(),
                OwnershipEvidencePolicy.trimToNull(claim.getOwnershipQuestionSnapshot()),
                OwnershipEvidencePolicy.trimToNull(claim.getOwnershipAnswer()));
    }
}
