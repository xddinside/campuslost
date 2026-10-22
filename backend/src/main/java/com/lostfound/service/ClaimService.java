package com.lostfound.service;

import com.lostfound.dto.ClaimDecisionRequest;
import com.lostfound.dto.ClaimRequest;
import com.lostfound.dto.ClaimResponse;
import com.lostfound.exception.ApiException;
import com.lostfound.model.Claim;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.User;
import com.lostfound.repository.ClaimRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final ItemService itemService;

    public ClaimService(ClaimRepository claimRepository, ItemService itemService) {
        this.claimRepository = claimRepository;
        this.itemService = itemService;
    }

    @Transactional
    public ClaimResponse create(User claimant, Long itemId, ClaimRequest request) {
        Item item = itemService.findItem(itemId);
        if (item.getStatus() != ItemStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "This item is no longer accepting claims");
        }
        if (item.getReporter().getId().equals(claimant.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot claim your own item");
        }
        if (claimRepository.existsByItemIdAndClaimantIdAndStatus(itemId, claimant.getId(), ClaimStatus.PENDING)) {
            throw new ApiException(HttpStatus.CONFLICT, "You already have a pending claim on this item");
        }
        Claim claim = new Claim();
        claim.setItem(item);
        claim.setClaimant(claimant);
        claim.setMessage(request.message().trim());
        claim.setStatus(ClaimStatus.PENDING);
        claimRepository.save(claim);
        return toResponse(claim);
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> listForItem(User user, Long itemId) {
        Item item = itemService.findItem(itemId);
        if (!item.getReporter().getId().equals(user.getId()) && user.getRole().name().equals("USER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only view claims on your own items");
        }
        return claimRepository.findByItemIdOrderByCreatedAtDesc(itemId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> mine(User user) {
        return claimRepository.findByClaimantIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void cancel(User user, Long claimId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claim not found"));
        if (!claim.getClaimant().getId().equals(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only cancel your own claim");
        }
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only pending claims can be cancelled");
        }
        claimRepository.delete(claim);
    }

    @Transactional
    public ClaimResponse decide(User user, Long claimId, ClaimDecisionRequest request) {
        ClaimStatus decision = request.status();
        if (decision != ClaimStatus.APPROVED && decision != ClaimStatus.REJECTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Decision must be APPROVED or REJECTED");
        }
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claim not found"));
        Item item = claim.getItem();
        if (!item.getReporter().getId().equals(user.getId()) && user.getRole().name().equals("USER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only decide claims on your own items");
        }
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "This claim has already been decided");
        }
        claim.setStatus(decision);
        if (decision == ClaimStatus.APPROVED) {
            item.setStatus(ItemStatus.RESOLVED);
            item.setResolvedAt(Instant.now());
            for (Claim other : claimRepository.findByItemIdAndStatusOrderByCreatedAtDesc(item.getId(), ClaimStatus.PENDING)) {
                other.setStatus(ClaimStatus.REJECTED);
            }
        }
        claimRepository.save(claim);
        return toResponse(claim);
    }

    private ClaimResponse toResponse(Claim claim) {
        return new ClaimResponse(
                claim.getId(),
                claim.getItem().getId(),
                claim.getItem().getTitle(),
                claim.getClaimant().getId(),
                claim.getClaimant().getName(),
                claim.getClaimant().getEmail(),
                claim.getMessage(),
                claim.getStatus(),
                claim.getCreatedAt());
    }
}
