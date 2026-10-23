-- Persistent, explainable lost/found match snapshots.
--
-- Each row is one canonical LOST -> FOUND pair. A snapshot is replaced
-- whenever either report changes so readers never see an obsolete pair.
CREATE TABLE IF NOT EXISTS match_candidates (
    id BIGINT NOT NULL AUTO_INCREMENT,
    lost_item_id BIGINT NOT NULL,
    found_item_id BIGINT NOT NULL,
    lost_item_version BIGINT NOT NULL,
    found_item_version BIGINT NOT NULL,
    score INT NOT NULL,
    reasons VARCHAR(1000) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_match_pair (lost_item_id, found_item_id),
    KEY idx_match_lost (lost_item_id, score),
    KEY idx_match_found (found_item_id, score),
    -- A hard-deleted report must never leave an orphaned snapshot behind.
    CONSTRAINT fk_match_lost FOREIGN KEY (lost_item_id) REFERENCES items (id) ON DELETE CASCADE,
    CONSTRAINT fk_match_found FOREIGN KEY (found_item_id) REFERENCES items (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
