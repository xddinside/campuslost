-- Retained delivery failures. The event listener records the event payload
-- here when after-commit delivery throws; the scheduled retry replays it and
-- the notification upsert dedupe keys keep replays idempotent.
CREATE TABLE IF NOT EXISTS notification_retries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    kind VARCHAR(20) NOT NULL,
    item_id BIGINT NULL,
    item_version BIGINT NULL,
    item_type VARCHAR(10) NULL,
    claim_id BIGINT NULL,
    lost_report_id BIGINT NULL,
    claimant_id BIGINT NULL,
    reporter_id BIGINT NULL,
    claim_status VARCHAR(20) NULL,
    handover_verified TINYINT(1) NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_notification_retries_due (next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
