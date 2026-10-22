package com.lostfound.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "claims")
@Getter
@Setter
@NoArgsConstructor
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Item item;

    /**
     * Optional LOST report owned by the claimant. Present only on FOUND claims
     * that prove which lost report the reservation is for.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lost_report_id")
    private Item lostReport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claimant_id")
    private User claimant;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32)")
    private ClaimStatus status = ClaimStatus.PENDING;

    /** Private prompt snapshot captured when the claim was created. */
    @Column(length = 200)
    private String ownershipQuestionSnapshot;

    /** Private answer; never serialized in public or claim-list DTOs. */
    @Column(columnDefinition = "TEXT")
    private String ownershipAnswer;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant decidedAt;

    private Instant completedAt;

    /** True only when the shared workflow completed this claim through OTP verification. */
    @Column(nullable = false)
    private boolean handoverVerified;

    @Column(length = 500)
    private String releaseReason;

    private Instant releasedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public boolean isActive() {
        return status == ClaimStatus.PENDING || status == ClaimStatus.APPROVED;
    }
}
