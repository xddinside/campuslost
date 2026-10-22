package com.lostfound.handover;

import com.lostfound.dto.ClaimResponse;
import com.lostfound.exception.ApiException;
import com.lostfound.mapper.ClaimMapper;
import com.lostfound.model.Claim;
import com.lostfound.model.ClaimStatus;
import com.lostfound.model.Item;
import com.lostfound.model.ItemStatus;
import com.lostfound.model.ItemType;
import com.lostfound.model.Role;
import com.lostfound.model.User;
import com.lostfound.repository.ClaimRef;
import com.lostfound.repository.ClaimRepository;
import com.lostfound.repository.ItemRepository;
import com.lostfound.workflow.ClaimWorkflow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OTP pickup verification. Every mutation takes the shared item locks in
 * ascending id order, then the claim lock (through {@link ClaimWorkflow} where
 * the actor is the reviewer), then the challenge lock. Challenge and claim
 * changes commit in one transaction.
 */
@Service
public class HandoverService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration ISSUE_COOLDOWN = Duration.ofSeconds(60);

    private final ClaimWorkflow claimWorkflow;
    private final ClaimRepository claims;
    private final ItemRepository items;
    private final HandoverChallengeRepository challenges;
    private final HandoverCrypto crypto;
    private final HandoverCodeGenerator codeGenerator;
    private final ClaimMapper mapper;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public HandoverService(ClaimWorkflow claimWorkflow,
                           ClaimRepository claims,
                           ItemRepository items,
                           HandoverChallengeRepository challenges,
                           HandoverCrypto crypto,
                           HandoverCodeGenerator codeGenerator,
                           ClaimMapper mapper,
                           Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.claimWorkflow = claimWorkflow;
        this.claims = claims;
        this.items = items;
        this.challenges = challenges;
        this.crypto = crypto;
        this.codeGenerator = codeGenerator;
        this.mapper = mapper;
        this.clock = clock;
        this.write = new TransactionTemplate(transactionManager);
        this.read = new TransactionTemplate(transactionManager);
        this.read.setReadOnly(true);
    }

    /**
     * Claimant-only issuance. This path locks the reports and claim directly
     * because the shared handover lock requires the reviewer; verification and
     * release below use {@link ClaimWorkflow#lockForHandover(User, Long)}.
     */
    public HandoverCodeResponse issue(User actor, Long claimId) {
        return write.execute(status -> doIssue(actor, claimId));
    }

    private HandoverCodeResponse doIssue(User actor, Long claimId) {
        ClaimRef ref = requireClaimRef(claimId);
        LockedItems locked = lockItems(ref);
        Claim claim = claims.findByIdForUpdate(claimId)
                .orElseThrow(HandoverService::notFound);
        Item target = locked.item(claim.getItem().getId());
        Item lost = claim.getLostReport() == null ? null : locked.item(claim.getLostReport().getId());

        if (!claim.getClaimant().getId().equals(actor.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the claimant can generate a pickup code.");
        }
        if (target.isDeleted() || (lost != null && lost.isDeleted())) {
            throw notFound();
        }
        requireIssuable(claim, target, lost);

        Instant now = clock.instant();
        HandoverChallenge challenge = challenges.findByClaimIdForUpdate(claimId).orElse(null);
        if (challenge == null) {
            challenge = new HandoverChallenge();
            challenge.setClaim(claim);
            challenge.setGeneration(1);
            challenge.setCreatedAt(now);
        } else {
            if (challenge.getVerifiedAt() != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_VERIFIED",
                        "This pickup has already been verified.");
            }
            if (challenge.getNextIssueAt() != null && now.isBefore(challenge.getNextIssueAt())) {
                long seconds = secondsUntil(now, challenge.getNextIssueAt());
                throw new HandoverRateLimitException("CODE_COOLDOWN",
                        "A new pickup code can be generated in " + seconds + " seconds.", seconds);
            }
            challenge.setGeneration(challenge.getGeneration() + 1);
        }

        String code = codeGenerator.nextCode();
        challenge.setCodeDigest(crypto.digest(claimId, challenge.getGeneration(), code));
        challenge.setExpiresAt(now.plus(CODE_TTL));
        challenge.setFailedAttempts(0);
        challenge.setNextIssueAt(now.plus(ISSUE_COOLDOWN));
        challenge.setVerifiedAt(null);
        challenge.setVerifiedBy(null);
        challenge.setUpdatedAt(now);
        challenges.saveAndFlush(challenge);

        return new HandoverCodeResponse(code, challenge.getExpiresAt());
    }

    /** Participant status view; never exposes the code or digest. */
    public HandoverStatusResponse status(User actor, Long claimId) {
        return read.execute(status -> {
            Claim claim = claims.findById(claimId).orElseThrow(HandoverService::notFound);
            requireParticipant(actor, claim);
            if (claim.getItem().isDeleted()) {
                throw notFound();
            }
            if (claim.getItem().getType() != ItemType.FOUND) {
                throw new ApiException(HttpStatus.CONFLICT, "HANDOVER_NOT_APPLICABLE",
                        "This response is not a found-item pickup.");
            }
            if (claim.getStatus() != ClaimStatus.APPROVED && claim.getStatus() != ClaimStatus.COMPLETED) {
                throw new ApiException(HttpStatus.CONFLICT, "HANDOVER_NOT_READY",
                        "Only an approved found-item claim has a pickup handover.");
            }

            HandoverChallenge challenge = challenges.findByClaimId(claimId).orElse(null);
            if (challenge == null) {
                if (claim.getStatus() == ClaimStatus.COMPLETED) {
                    return new HandoverStatusResponse(claimId, HandoverState.COMPLETED,
                            null, null, null);
                }
                return new HandoverStatusResponse(claimId, HandoverState.NOT_ISSUED,
                        null, null, null);
            }

            if (claim.getStatus() == ClaimStatus.COMPLETED) {
                return new HandoverStatusResponse(claimId, HandoverState.COMPLETED,
                        challenge.getExpiresAt(), null, challenge.getVerifiedAt());
            }

            Instant now = clock.instant();
            HandoverState state;
            if (challenge.getVerifiedAt() != null) {
                state = HandoverState.COMPLETED;
            } else if (challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
                state = HandoverState.LOCKED;
            } else if (!now.isBefore(challenge.getExpiresAt())) {
                state = HandoverState.EXPIRED;
            } else {
                state = HandoverState.READY;
            }

            Integer remaining = state == HandoverState.COMPLETED
                    ? null
                    : Math.max(0, MAX_ATTEMPTS - challenge.getFailedAttempts());
            return new HandoverStatusResponse(claimId, state, challenge.getExpiresAt(),
                    remaining, challenge.getVerifiedAt());
        });
    }

    /**
     * Reviewer/admin verification. Wrong attempts are returned as an outcome so
     * the transaction commits the increment before the controller receives the
     * error; throwing from inside the transaction would roll it back.
     */
    public HandoverVerifyResponse verify(User actor, Long claimId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();

        HandoverVerifyResponse existing = existingCompletion(actor, claimId, code);
        if (existing != null) {
            return existing;
        }

        VerifyOutcome outcome;
        try {
            outcome = write.execute(status -> doVerify(actor, claimId, code));
        } catch (ApiException ex) {
            // A simultaneous or repeated verification may have completed the
            // claim between our read and lock. Return that result without a
            // second event when the same code matches.
            if ("HANDOVER_NOT_READY".equals(ex.getCode())
                    || "RESERVATION_STALE".equals(ex.getCode())) {
                HandoverVerifyResponse again = existingCompletion(actor, claimId, code);
                if (again != null) {
                    return again;
                }
            }
            throw ex;
        }

        if (outcome instanceof Failed failure) {
            if (failure.retryAfterSeconds() != null) {
                throw new HandoverRateLimitException(failure.code(), failure.message(),
                        failure.retryAfterSeconds());
            }
            throw new ApiException(failure.status(), failure.code(), failure.message());
        }
        return ((Verified) outcome).response();
    }

    private VerifyOutcome doVerify(User actor, Long claimId, String code) {
        ClaimWorkflow.LockedClaim context = claimWorkflow.lockForHandover(actor, claimId);
        HandoverChallenge challenge = challenges.findByClaimIdForUpdate(claimId).orElse(null);
        Instant now = clock.instant();

        if (challenge == null) {
            return Failed.badRequest("CODE_NOT_ISSUED",
                    "No pickup code has been generated for this claim yet.");
        }
        if (challenge.getVerifiedAt() != null) {
            return new Failed(HttpStatus.CONFLICT, "ALREADY_VERIFIED",
                    "This pickup has already been verified.", null);
        }
        if (challenge.getFailedAttempts() >= MAX_ATTEMPTS) {
            long retry = retryAfterLockout(challenge, now);
            return Failed.rateLimited("CODE_LOCKED",
                    "Too many incorrect attempts. The claimant must generate a new code.", retry);
        }
        if (!now.isBefore(challenge.getExpiresAt())) {
            return Failed.badRequest("CODE_EXPIRED",
                    "This pickup code has expired. Ask the claimant to generate a new one.");
        }

        String digest = crypto.digest(claimId, challenge.getGeneration(), code);
        if (!crypto.matches(digest, challenge.getCodeDigest())) {
            int failed = challenge.getFailedAttempts() + 1;
            challenge.setFailedAttempts(failed);
            challenge.setUpdatedAt(now);
            challenges.saveAndFlush(challenge);
            if (failed >= MAX_ATTEMPTS) {
                long retry = retryAfterLockout(challenge, now);
                return Failed.rateLimited("CODE_LOCKED",
                        "Too many incorrect attempts. The claimant must generate a new code.", retry);
            }
            int remaining = MAX_ATTEMPTS - failed;
            return Failed.badRequest("INVALID_CODE",
                    "That pickup code is not correct. " + remaining
                            + (remaining == 1 ? " attempt remains." : " attempts remain."));
        }

        Claim completed = claimWorkflow.completeVerified(context);
        challenge.setVerifiedAt(completed.getCompletedAt());
        challenge.setVerifiedBy(actor);
        challenge.setUpdatedAt(now);
        challenges.saveAndFlush(challenge);

        return new Verified(new HandoverVerifyResponse(
                mapper.toResponse(completed), challenge.getVerifiedAt()));
    }

    /** Idempotent repeat of a successful verification; no new event is emitted. */
    private HandoverVerifyResponse existingCompletion(User actor, Long claimId, String code) {
        return read.execute(status -> {
            Claim claim = claims.findById(claimId).orElse(null);
            if (claim == null) {
                return null;
            }
            if (!isReviewer(actor, claim.getItem())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Only the reporter or an administrator can verify this pickup.");
            }
            if (claim.getStatus() != ClaimStatus.COMPLETED || !claim.isHandoverVerified()) {
                return null;
            }
            HandoverChallenge challenge = challenges.findByClaimId(claimId).orElse(null);
            if (challenge == null || challenge.getVerifiedAt() == null) {
                return null;
            }
            String digest = crypto.digest(claimId, challenge.getGeneration(), code);
            if (!crypto.matches(digest, challenge.getCodeDigest())) {
                return null;
            }
            return new HandoverVerifyResponse(mapper.toResponse(claim), challenge.getVerifiedAt());
        });
    }

    /**
     * Reviewer/admin recovery path. Invalidates the challenge and uses the core
     * release operation so both reserved reports reopen together.
     */
    public ClaimResponse release(User actor, Long claimId, String reason) {
        return write.execute(status -> {
            ClaimWorkflow.LockedClaim context = claimWorkflow.lockForHandover(actor, claimId);
            HandoverChallenge challenge = challenges.findByClaimIdForUpdate(claimId).orElse(null);
            if (challenge != null) {
                Instant now = clock.instant();
                challenge.setExpiresAt(now);
                challenge.setNextIssueAt(null);
                challenge.setGeneration(challenge.getGeneration() + 1);
                challenge.setUpdatedAt(now);
                challenges.saveAndFlush(challenge);
            }
            Claim released = claimWorkflow.releaseLocked(context, actor, reason);
            return mapper.toResponse(released);
        });
    }

    private void requireIssuable(Claim claim, Item target, Item lost) {
        if (target.getType() != ItemType.FOUND) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDOVER_NOT_READY",
                    "Only an approved found-item claim can generate a pickup code.");
        }
        if (claim.getStatus() == ClaimStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_VERIFIED",
                    "This pickup has already been verified.");
        }
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDOVER_NOT_READY",
                    "Only an approved found-item claim can generate a pickup code.");
        }
        if (target.getStatus() != ItemStatus.RESERVED
                || (lost != null && lost.getStatus() != ItemStatus.RESERVED)) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_STALE",
                    "This reservation is no longer active.");
        }
    }

    private void requireParticipant(User actor, Claim claim) {
        boolean participant = claim.getClaimant().getId().equals(actor.getId())
                || claim.getItem().getReporter().getId().equals(actor.getId())
                || actor.getRole() == Role.ADMIN;
        if (!participant) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the people in this claim can view its handover.");
        }
    }

    private boolean isReviewer(User actor, Item item) {
        return actor != null && claimWorkflow.canReview(actor, item);
    }

    private ClaimRef requireClaimRef(Long claimId) {
        return claims.findRefById(claimId).orElseThrow(HandoverService::notFound);
    }

    private LockedItems lockItems(ClaimRef ref) {
        List<Long> ids = new ArrayList<>(2);
        ids.add(ref.itemId());
        if (ref.lostReportId() != null && !ref.lostReportId().equals(ref.itemId())) {
            ids.add(ref.lostReportId());
        }
        ids.sort(Long::compareTo);
        Map<Long, Item> locked = new LinkedHashMap<>();
        for (Long id : ids) {
            locked.put(id, items.findByIdForUpdate(id).orElseThrow(HandoverService::notFound));
        }
        return new LockedItems(locked);
    }

    private long retryAfterLockout(HandoverChallenge challenge, Instant now) {
        if (challenge.getNextIssueAt() != null && challenge.getNextIssueAt().isAfter(now)) {
            return secondsUntil(now, challenge.getNextIssueAt());
        }
        return ISSUE_COOLDOWN.toSeconds();
    }

    private long secondsUntil(Instant now, Instant target) {
        long seconds = Duration.between(now, target).toSeconds();
        return Math.max(1, seconds);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Claim not found.");
    }

    private record LockedItems(Map<Long, Item> items) {
        Item item(Long id) {
            Item item = items.get(id);
            if (item == null) {
                throw notFound();
            }
            return item;
        }
    }

    private sealed interface VerifyOutcome permits Verified, Failed {
    }

    private record Verified(HandoverVerifyResponse response) implements VerifyOutcome {
    }

    private record Failed(HttpStatus status, String code, String message,
                          Long retryAfterSeconds) implements VerifyOutcome {
        static Failed badRequest(String code, String message) {
            return new Failed(HttpStatus.BAD_REQUEST, code, message, null);
        }

        static Failed rateLimited(String code, String message, long retryAfterSeconds) {
            return new Failed(HttpStatus.TOO_MANY_REQUESTS, code, message, retryAfterSeconds);
        }
    }
}
