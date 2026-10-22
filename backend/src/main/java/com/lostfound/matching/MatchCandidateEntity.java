package com.lostfound.matching;

import com.lostfound.model.Item;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One persisted LOST -> FOUND match snapshot. Rows are always canonical:
 * {@code lostItem} is the LOST report and {@code foundItem} is the FOUND one.
 */
@Entity
@Table(name = "match_candidates",
        uniqueConstraints = @UniqueConstraint(name = "uk_match_pair",
                columnNames = {"lost_item_id", "found_item_id"}))
public class MatchCandidateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lost_item_id", nullable = false)
    private Item lostItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "found_item_id", nullable = false)
    private Item foundItem;

    @Column(name = "lost_item_version", nullable = false)
    private long lostItemVersion;

    @Column(name = "found_item_version", nullable = false)
    private long foundItemVersion;

    @Column(nullable = false)
    private int score;

    /** Newline-separated human explanations; phrases never contain a newline. */
    @Column(nullable = false, length = 1000)
    private String reasons;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public Item getLostItem() {
        return lostItem;
    }

    public void setLostItem(Item lostItem) {
        this.lostItem = lostItem;
    }

    public Item getFoundItem() {
        return foundItem;
    }

    public void setFoundItem(Item foundItem) {
        this.foundItem = foundItem;
    }

    public long getLostItemVersion() {
        return lostItemVersion;
    }

    public void setLostItemVersion(long lostItemVersion) {
        this.lostItemVersion = lostItemVersion;
    }

    public long getFoundItemVersion() {
        return foundItemVersion;
    }

    public void setFoundItemVersion(long foundItemVersion) {
        this.foundItemVersion = foundItemVersion;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getReasons() {
        return reasons;
    }

    public void setReasons(String reasons) {
        this.reasons = reasons;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
