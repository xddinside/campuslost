package com.lostfound.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A committed event whose delivery failed. Stores the flat event payload, not
 * entities, so a retry replays exactly what the listener was handed.
 */
@Entity
@Table(name = "notification_retries")
@Getter
@Setter
@NoArgsConstructor
public class NotificationRetry {

    public enum Kind {
        MATCH_SNAPSHOT,
        CLAIM_CHANGED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private Kind kind;

    private Long itemId;

    private Long itemVersion;

    @Column(length = 10, columnDefinition = "varchar(10)")
    private String itemType;

    private Long claimId;

    private Long lostReportId;

    private Long claimantId;

    private Long reporterId;

    @Column(length = 20, columnDefinition = "varchar(20)")
    private String claimStatus;

    private Boolean handoverVerified;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 500, columnDefinition = "varchar(500)")
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant nextAttemptAt;
}
