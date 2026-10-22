package com.lostfound.workflow;

import com.lostfound.contract.ClaimChangedEvent;
import com.lostfound.contract.ClaimEvidencePolicy;
import com.lostfound.contract.ItemChangeKind;
import com.lostfound.contract.ItemChangedEvent;
import com.lostfound.dto.ClaimRequest;
import com.lostfound.exception.ApiException;
import com.lostfound.model.*;
import com.lostfound.repository.ClaimRef;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * The one place report/claim lifecycle transitions happen. Every mutation
 * takes pessimistic item locks in ascending ID order, then the claim lock,
 * re-reads state under lock, and publishes committed-change events inside the
 * same transaction.
 *
 * The datasource pool uses READ COMMITTED. A locking read after an earlier
 * snapshot read must see the newest committed row; MariaDB's default
 * REPEATABLE READ raises "record has changed since last read" instead.
 */
@Service
public class ClaimWorkflow {

    public static final List<ClaimStatus> ACTIVE_CLAIM_STATUSES =
            List.of(ClaimStatus.PENDING, ClaimStatus.APPROVED);
    public static final List<ClaimStatus> RESERVED_CLAIM_STATUSES =
            List.of(ClaimStatus.APPROVED, ClaimStatus.COMPLETED);

    private final ItemRepository itemRepository;
    private final ClaimRepository claimRepository;
    private final ObjectProvider<ClaimEvidencePolicy> evidencePolicy;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    public ClaimWorkflow(ItemRepository itemRepository,
                         ClaimRepository claimRepository,
                         ObjectProvider<ClaimEvidencePolicy> evidencePolicy,
                         ApplicationEventPublisher events,
                         EntityManager entityManager) {
        this.itemRepository = itemRepository;
        this.claimRepository = claimRepository;
        this.evidencePolicy = evidencePolicy;
        this.events = events;
        this.entityManager = entityManager;
    }

    public record LockedClaim(Claim claim, Item target, Item lostReport) {
    }

    @Transactional
    public Claim create(User claimant, Long itemId, ClaimRequest request) {
        Item target = lockItem(itemId);
        requireVisible(target);
        if (target.getStatus() != ItemStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_NOT_OPEN",
                    "This report is no longer accepting responses.");
        }
        if (target.getReporter().getId().equals(claimant.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OWN_REPORT",
                    "You cannot claim your own report.");
        }
        if (request.message().trim().length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Write at least 10 characters.",
                    Map.of("message", "Write at least 10 characters."));
        }

        Item lostReport = null;
        if (target.getType() == ItemType.FOUND) {
            if (request.lostReportId() != null) {
                lostReport = lockItem(request.lostReportId());
                requireVisible(lostReport);
                if (lostReport.getType() != ItemType.LOST
                        || lostReport.getStatus() != ItemStatus.OPEN
                        || !lostReport.getReporter().getId().equals(claimant.getId())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOST_REPORT",
                            "Link one of your open lost reports.");
                }
                if (!claimRepository.findByLostReportIdAndStatusIn(
                        lostReport.getId(), RESERVED_CLAIM_STATUSES).isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "LOST_REPORT_RESERVED",
                            "That lost report already has an approved pickup.");
                }
            }
        } else {
            if (request.lostReportId() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOST_REPORT",
                        "Lost reports cannot be linked to this response.");
            }
            if (request.ownershipAnswer() != null && !request.ownershipAnswer().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE",
                        "This report does not ask an ownership question.");
            }
        }

        if (claimRepository.existsByItemIdAndClaimantIdAndStatusIn(
                target.getId(), claimant.getId(), ACTIVE_CLAIM_STATUSES)) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_CLAIM",
                    "You already have an active response on this report.");
        }

        Claim claim = new Claim();
        claim.setItem(target);
        claim.setLostReport(lostReport);
        claim.setClaimant(claimant);
        claim.setMessage(request.message().trim());
        claim.setStatus(ClaimStatus.PENDING);

        if (target.getOwnershipQuestion() != null) {
            ClaimEvidencePolicy policy = evidencePolicy.getIfAvailable();
            if (policy == null) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UNAVAILABLE",
                        "Ownership verification is not available yet. Please try another report.");
            }
            ClaimEvidencePolicy.PreparedEvidence evidence = policy.prepare(target, claimant, request);
            if (evidence == null) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVIDENCE_UNAVAILABLE",
                        "Ownership verification is not available yet. Please try another report.");
            }
            claim.setOwnershipQuestionSnapshot(evidence.questionSnapshot());
            claim.setOwnershipAnswer(evidence.answer());
        }

        claimRepository.save(claim);
        entityManager.flush();
        publishClaim(claim, false);
        return claim;
    }

    @Transactional
    public Claim decide(User actor, Long claimId, ClaimStatus decision) {
        if (decision != ClaimStatus.APPROVED && decision != ClaimStatus.REJECTED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECISION",
                    "Decision must be APPROVED or REJECTED.");
        }
        ClaimRef ref = requireClaimRef(claimId);
        LockedItems locked = lockClaimItems(ref);
        Claim claim = claimRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> notFound("Claim not found."));
        Item target = locked.item(claim.getItem().getId());
        Item lost = claim.getLostReport() == null ? null : locked.item(claim.getLostReport().getId());
        requireReviewer(actor, target);
        requireVisible(target);

        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "CLAIM_NOT_PENDING",
                    "This response has already been decided.");
        }
        if (target.getStatus() != ItemStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_NOT_OPEN",
                    "This report is no longer open, so it cannot be approved.");
        }

        Instant now = Instant.now();
        if (decision == ClaimStatus.REJECTED) {
            claim.setStatus(ClaimStatus.REJECTED);
            claim.setDecidedAt(now);
            entityManager.flush();
            publishClaim(claim, false);
            return claim;
        }

        if (target.getType() == ItemType.FOUND) {
            if (lost != null) {
                requireVisible(lost);
                if (lost.getType() != ItemType.LOST
                        || lost.getStatus() != ItemStatus.OPEN
                        || !lost.getReporter().getId().equals(claim.getClaimant().getId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "INVALID_LOST_REPORT",
                            "The linked lost report is no longer available.");
                }
                List<Claim> competing = claimRepository.findByLostReportIdAndStatusIn(
                        lost.getId(), RESERVED_CLAIM_STATUSES).stream()
                        .filter(other -> !other.getId().equals(claim.getId()))
                        .toList();
                if (!competing.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "LOST_REPORT_RESERVED",
                            "Another found report already has an approved pickup for that lost report.");
                }
            }
            target.setStatus(ItemStatus.RESERVED);
            target.setResolvedAt(null);
            claim.setStatus(ClaimStatus.APPROVED);
            claim.setDecidedAt(now);
            claim.setCompletedAt(null);
            if (lost != null) {
                lost.setStatus(ItemStatus.RESERVED);
                lost.setResolvedAt(null);
            }
            rejectPending(target, claim);
            if (lost != null) {
                rejectPending(lost, claim);
            }
            entityManager.flush();
            publishItem(target, ItemChangeKind.STATUS_CHANGED);
            if (lost != null) {
                publishItem(lost, ItemChangeKind.STATUS_CHANGED);
            }
            publishClaim(claim, false);
        } else {
            // A response to a LOST report is a "found it" message: approval
            // resolves that report and completes the response directly.
            target.setStatus(ItemStatus.RESOLVED);
            target.setResolvedAt(now);
            claim.setStatus(ClaimStatus.COMPLETED);
            claim.setDecidedAt(now);
            claim.setCompletedAt(now);
            claim.setHandoverVerified(false);
            rejectPending(target, claim);
            entityManager.flush();
            publishItem(target, ItemChangeKind.STATUS_CHANGED);
            publishClaim(claim, false);
        }
        return claim;
    }

    @Transactional
    public Claim cancel(User actor, Long claimId) {
        ClaimRef ref = requireClaimRef(claimId);
        LockedItems locked = lockClaimItems(ref);
        Claim claim = claimRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> notFound("Claim not found."));
        if (!claim.getClaimant().getId().equals(actor.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "You can only cancel your own response.");
        }
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "CLAIM_NOT_PENDING",
                    "Only pending responses can be cancelled. Ask the reporter to release an approved pickup.");
        }
        claim.setStatus(ClaimStatus.CANCELLED);
        claim.setDecidedAt(Instant.now());
        entityManager.flush();
        publishClaim(claim, false);
        return claim;
    }

    @Transactional
    public Claim release(User actor, Long claimId, String reason) {
        ClaimRef ref = requireClaimRef(claimId);
        LockedItems locked = lockClaimItems(ref);
        Claim claim = claimRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> notFound("Claim not found."));
        Item target = locked.item(claim.getItem().getId());
        Item lost = claim.getLostReport() == null ? null : locked.item(claim.getLostReport().getId());
        requireReviewer(actor, target);
        return applyRelease(claim, target, lost, reason);
    }

    /**
     * Acquires the shared locked claim context. Handover calls this inside its
     * own transaction before locking its challenge row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedClaim lockForHandover(User actor, Long claimId) {
        ClaimRef ref = requireClaimRef(claimId);
        LockedItems locked = lockClaimItems(ref);
        Claim claim = claimRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> notFound("Claim not found."));
        Item target = locked.item(claim.getItem().getId());
        Item lost = claim.getLostReport() == null ? null : locked.item(claim.getLostReport().getId());
        requireReviewer(actor, target);
        if (target.getType() != ItemType.FOUND || claim.getStatus() != ClaimStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDOVER_NOT_READY",
                    "Only an approved found-item claim can be verified for pickup.");
        }
        if (target.getStatus() != ItemStatus.RESERVED
                || (lost != null && lost.getStatus() != ItemStatus.RESERVED)) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_STALE",
                    "This reservation is no longer active.");
        }
        return new LockedClaim(claim, target, lost);
    }

    /**
     * Completes an already locked FOUND reservation. Only handover verification
     * may call this; ordinary claim decisions cannot.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim completeVerified(LockedClaim context) {
        Claim claim = context.claim();
        Item target = context.target();
        Item lost = context.lostReport();
        if (claim.getStatus() != ClaimStatus.APPROVED || target.getType() != ItemType.FOUND) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDOVER_NOT_READY",
                    "This claim is not awaiting a verified pickup.");
        }
        Instant now = Instant.now();
        claim.setStatus(ClaimStatus.COMPLETED);
        claim.setCompletedAt(now);
        claim.setHandoverVerified(true);
        target.setStatus(ItemStatus.RESOLVED);
        target.setResolvedAt(now);
        if (lost != null) {
            lost.setStatus(ItemStatus.RESOLVED);
            lost.setResolvedAt(now);
        }
        entityManager.flush();
        publishItem(target, ItemChangeKind.STATUS_CHANGED);
        if (lost != null) {
            publishItem(lost, ItemChangeKind.STATUS_CHANGED);
        }
        publishClaim(claim, true);
        return claim;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim releaseLocked(LockedClaim context, User actor, String reason) {
        requireReviewer(actor, context.target());
        return applyRelease(context.claim(), context.target(), context.lostReport(), reason);
    }

    /** Rejects pending responses on a report resolved, closed, or deleted manually. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rejectPendingForItem(Item item, Claim except) {
        rejectPending(item, except);
        entityManager.flush();
    }

    public boolean canReview(User actor, Item item) {
        return actor != null && (actor.getRole() == Role.ADMIN
                || item.getReporter().getId().equals(actor.getId()));
    }

    private Claim applyRelease(Claim claim, Item target, Item lost, String reason) {
        if (claim.getStatus() != ClaimStatus.APPROVED || target.getType() != ItemType.FOUND) {
            throw new ApiException(HttpStatus.CONFLICT, "CLAIM_NOT_APPROVED",
                    "Only an approved found-item reservation can be released.");
        }
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REASON",
                    "Release reason must be at most 500 characters.");
        }
        Instant now = Instant.now();
        claim.setStatus(ClaimStatus.CANCELLED);
        claim.setReleasedAt(now);
        claim.setReleaseReason(trimmedReason.isEmpty() ? "Reservation released" : trimmedReason);
        target.setStatus(target.getStatus() == ItemStatus.RESERVED ? ItemStatus.OPEN : target.getStatus());
        target.setResolvedAt(null);
        if (lost != null && lost.getStatus() == ItemStatus.RESERVED) {
            lost.setStatus(ItemStatus.OPEN);
            lost.setResolvedAt(null);
        }
        entityManager.flush();
        publishItem(target, ItemChangeKind.STATUS_CHANGED);
        if (lost != null) {
            publishItem(lost, ItemChangeKind.STATUS_CHANGED);
        }
        publishClaim(claim, false);
        return claim;
    }

    private void rejectPending(Item item, Claim except) {
        Set<Long> rejected = new HashSet<>();
        List<Claim> candidates = new ArrayList<>(
                claimRepository.findByItemIdAndStatusInOrderByCreatedAtDesc(item.getId(),
                        List.of(ClaimStatus.PENDING)));
        // Pending claims on other found reports that link this lost report.
        candidates.addAll(claimRepository.findByLostReportIdAndStatusIn(item.getId(),
                List.of(ClaimStatus.PENDING)));
        Instant now = Instant.now();
        for (Claim pending : candidates) {
            if (except != null && pending.getId().equals(except.getId())) {
                continue;
            }
            if (!pending.isActive() || pending.getStatus() != ClaimStatus.PENDING) {
                continue;
            }
            if (!rejected.add(pending.getId())) {
                continue;
            }
            pending.setStatus(ClaimStatus.REJECTED);
            pending.setDecidedAt(now);
            publishClaim(pending, false);
        }
    }

    private ClaimRef requireClaimRef(Long claimId) {
        return claimRepository.findRefById(claimId)
                .orElseThrow(() -> notFound("Claim not found."));
    }

    private LockedItems lockClaimItems(ClaimRef ref) {
        return lockItems(ref.itemId(), ref.lostReportId());
    }

    private LockedItems lockItems(Long targetId, Long lostId) {
        List<Long> ids = new ArrayList<>(2);
        ids.add(targetId);
        if (lostId != null && !lostId.equals(targetId)) {
            ids.add(lostId);
        }
        ids.sort(Long::compareTo);
        Map<Long, Item> locked = new LinkedHashMap<>();
        for (Long id : ids) {
            locked.put(id, itemRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> notFound("Report not found.")));
        }
        return new LockedItems(locked);
    }

    private Item lockItem(Long itemId) {
        return itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> notFound("Report not found."));
    }

    private void requireVisible(Item item) {
        if (item.isDeleted()) {
            throw notFound("Report not found.");
        }
    }

    private void requireReviewer(User actor, Item item) {
        if (!canReview(actor, item)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the reporter or an administrator can review this response.");
        }
    }

    private void publishItem(Item item, ItemChangeKind kind) {
        events.publishEvent(new ItemChangedEvent(item.getId(), item.getVersion(), kind));
    }

    private void publishClaim(Claim claim, boolean handoverVerified) {
        events.publishEvent(new ClaimChangedEvent(
                claim.getId(),
                claim.getItem().getId(),
                claim.getItem().getType(),
                claim.getLostReport() == null ? null : claim.getLostReport().getId(),
                claim.getClaimant().getId(),
                claim.getItem().getReporter().getId(),
                claim.getStatus(),
                handoverVerified));
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    private record LockedItems(Map<Long, Item> items) {
        Item item(Long id) {
            Item item = items.get(id);
            if (item == null) {
                throw notFound("Report not found.");
            }
            return item;
        }
    }
}
