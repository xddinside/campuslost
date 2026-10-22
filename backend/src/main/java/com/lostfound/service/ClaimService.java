package com.lostfound.service;

import com.lostfound.dto.*;
import com.lostfound.exception.ApiException;
import com.lostfound.mapper.ClaimMapper;
import com.lostfound.model.Claim;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.Item;
import com.lostfound.model.Role;
import com.lostfound.model.User;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.workflow.ClaimWorkflow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final ItemService itemService;
    private final ClaimWorkflow claimWorkflow;
    private final ClaimMapper mapper;

    public ClaimService(ClaimRepository claimRepository,
                        ItemService itemService,
                        ClaimWorkflow claimWorkflow,
                        ClaimMapper mapper) {
        this.claimRepository = claimRepository;
        this.itemService = itemService;
        this.claimWorkflow = claimWorkflow;
        this.mapper = mapper;
    }

    @Transactional
    public ClaimResponse create(User claimant, Long itemId, ClaimRequest request) {
        return mapper.toResponse(claimWorkflow.create(claimant, itemId, request));
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> listForItem(User user, Long itemId) {
        Item item = itemService.findVisible(itemId);
        if (!claimWorkflow.canReview(user, item)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "You can only view responses on your own reports.");
        }
        return claimRepository.findByItemIdOrderByCreatedAtDesc(itemId)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> mine(User user) {
        return claimRepository.findByClaimantIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional
    public ClaimResponse decide(User user, Long claimId, ClaimDecisionRequest request) {
        if (request.status() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECISION",
                    "Decision must be APPROVED or REJECTED.");
        }
        return mapper.toResponse(claimWorkflow.decide(user, claimId, request.status()));
    }

    @Transactional
    public void cancel(User user, Long claimId) {
        claimWorkflow.cancel(user, claimId);
    }

    @Transactional
    public ClaimResponse release(User user, Long claimId, String reason) {
        return mapper.toResponse(claimWorkflow.release(user, claimId, reason));
    }

    @Transactional(readOnly = true)
    public ContactResponse contact(User user, Long claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Claim not found."));
        Item item = claim.getItem();
        boolean participant = claim.getClaimant().getId().equals(user.getId())
                || item.getReporter().getId().equals(user.getId());
        if (!participant && user.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the people in this claim can see contact details.");
        }
        if (claim.getStatus() != ClaimStatus.APPROVED && claim.getStatus() != ClaimStatus.COMPLETED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CONTACT_UNAVAILABLE",
                    "Contact details are available only after a claim is approved.");
        }
        if (item.isDeleted()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Claim not found.");
        }
        User reporter = item.getReporter();
        User claimant = claim.getClaimant();
        return new ContactResponse(
                claim.getId(),
                new ContactResponse.Participant(reporter.getName(), reporter.getEmail(), reporter.getPhone()),
                new ContactResponse.Participant(claimant.getName(), claimant.getEmail(), claimant.getPhone()));
    }
}
