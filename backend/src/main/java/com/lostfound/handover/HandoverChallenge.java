package com.lostfound.handover;

import com.lostfound.model.Claim;
import com.lostfound.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One current pickup-code challenge per found-item claim. The plaintext code is
 * never persisted: only an HMAC digest bound to the claim id and generation.
 */
@Entity
@Table(name = "handover_challenges")
@Getter
@Setter
@NoArgsConstructor
public class HandoverChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false, unique = true)
    private Claim claim;

    @Column(name = "code_digest", nullable = false, length = 64)
    private String codeDigest;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "next_issue_at")
    private Instant nextIssueAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by_id")
    private User verifiedBy;

    /** Incremented on every regeneration so old digests cannot verify. */
    @Column(nullable = false)
    private int generation = 1;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
