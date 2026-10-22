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
 * A delivered alert snapshot. Item and claim references are stored as IDs so a
 * read can re-check visibility without trusting anything supplied by a caller.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32)")
    private NotificationType type;

    @Column(name = "dedupe_key", nullable = false, length = 120)
    private String dedupeKey;

    private Long lostItemId;

    private Long foundItemId;

    private Long claimId;

    private Long lostItemVersion;

    private Long foundItemVersion;

    private Integer score;

    /** Human-readable reason snapshot, one reason per line. */
    @Column(columnDefinition = "TEXT")
    private String reasons;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    /** False when the underlying match no longer qualifies; historical rows remain. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant readAt;
}
