-- Automatic in-app alerts. Rows are denormalised snapshots: they never store
-- contact data, ownership answers, or pickup codes, and they point at reports
-- and claims by ID so later reads can re-check visibility.

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    dedupe_key VARCHAR(120) NOT NULL,
    lost_item_id BIGINT NULL,
    found_item_id BIGINT NULL,
    claim_id BIGINT NULL,
    lost_item_version BIGINT NULL,
    found_item_version BIGINT NULL,
    score INT NULL,
    reasons TEXT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(500) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    read_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_recipient_dedupe (recipient_id, dedupe_key),
    KEY idx_notifications_recipient_created (recipient_id, id),
    KEY idx_notifications_recipient_unread (recipient_id, read_at),
    KEY idx_notifications_match_source (lost_item_id, found_item_id),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
