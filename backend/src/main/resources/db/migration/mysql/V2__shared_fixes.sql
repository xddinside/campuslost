-- Shared fixes: lifecycle fields, soft deletion, photos, private ownership
-- evidence, reservations, and legacy claim conversion.
--
-- MySQL 8 variant of the MariaDB migration. MySQL rejects ALTER TABLE ...
-- ADD COLUMN IF NOT EXISTS and CREATE INDEX IF NOT EXISTS, so this copy uses
-- plain statements: it is meant for a fresh database or an explicitly
-- baselined adoption, never for re-running over a partially-migrated schema.
-- Legacy-database adoption is supported on MariaDB only (see
-- scripts/adopt-legacy-db.sh).

-- Enum columns created by the old ddl-auto=update prototype cannot carry new
-- lifecycle values. Widen every enum-like column to text.
ALTER TABLE items
    MODIFY COLUMN type VARCHAR(10) NOT NULL,
    MODIFY COLUMN status VARCHAR(32) NOT NULL;
ALTER TABLE claims
    MODIFY COLUMN status VARCHAR(32) NOT NULL;
ALTER TABLE users
    MODIFY COLUMN role VARCHAR(20) NOT NULL;

-- Item lifecycle, optimistic locking, soft delete and private fields.
ALTER TABLE items
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at DATETIME(6) NULL,
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN photo_id VARCHAR(36) NULL,
    ADD COLUMN ownership_question VARCHAR(200) NULL;

UPDATE items
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE items
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL;

CREATE INDEX idx_items_public
    ON items (status, deleted_at, created_at);
CREATE INDEX idx_items_location
    ON items (location);
CREATE INDEX idx_items_category
    ON items (category);

-- Claim lifecycle, linked lost report, private evidence and release audit.
ALTER TABLE claims
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lost_report_id BIGINT NULL,
    ADD COLUMN ownership_question_snapshot VARCHAR(200) NULL,
    ADD COLUMN ownership_answer TEXT NULL,
    ADD COLUMN decided_at DATETIME(6) NULL,
    ADD COLUMN completed_at DATETIME(6) NULL,
    ADD COLUMN handover_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN release_reason VARCHAR(500) NULL,
    ADD COLUMN released_at DATETIME(6) NULL;

CREATE INDEX idx_claims_status
    ON claims (item_id, status);
CREATE INDEX idx_claims_lost_report
    ON claims (lost_report_id, status);

-- One active reservation per target report and per linked lost report.
-- Generated nullable columns allow unlimited historical/rejected rows while
-- flatly preventing two APPROVED/COMPLETED claims on the same report.
ALTER TABLE claims
    ADD COLUMN active_item_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN status IN ('APPROVED', 'COMPLETED') THEN item_id ELSE NULL END) STORED,
    ADD COLUMN active_lost_report_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN status IN ('APPROVED', 'COMPLETED') THEN lost_report_id ELSE NULL END) STORED;

-- Keep the oldest approved claim if an old prototype managed to approve two.
UPDATE claims c
    JOIN (
        SELECT item_id, MIN(id) AS keep_id
        FROM claims
        WHERE status = 'APPROVED'
        GROUP BY item_id
        HAVING COUNT(*) > 1
    ) duplicates ON duplicates.item_id = c.item_id
SET c.status = 'REJECTED',
    c.decided_at = COALESCE(c.decided_at, CURRENT_TIMESTAMP(6))
WHERE c.status = 'APPROVED'
  AND c.id <> duplicates.keep_id;

-- Legacy APPROVED claims on already resolved items become COMPLETED without
-- claiming a verified pickup (only the handover module can do that).
UPDATE claims c
    JOIN items i ON i.id = c.item_id
SET c.status = 'COMPLETED',
    c.completed_at = COALESCE(i.resolved_at, c.created_at),
    c.decided_at = COALESCE(c.decided_at, i.resolved_at, c.created_at)
WHERE c.status = 'APPROVED'
  AND i.status = 'RESOLVED';

ALTER TABLE claims
    ADD UNIQUE KEY uk_claims_active_item (active_item_id),
    ADD UNIQUE KEY uk_claims_active_lost_report (active_lost_report_id),
    ADD CONSTRAINT fk_claims_lost_report FOREIGN KEY (lost_report_id) REFERENCES items (id);

CREATE TABLE IF NOT EXISTS photos (
    id VARCHAR(36) NOT NULL,
    uploader_id BIGINT NOT NULL,
    storage_name VARCHAR(120) NOT NULL,
    content_type VARCHAR(40) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INT NULL,
    height INT NULL,
    created_at DATETIME(6) NOT NULL,
    attached_item_id BIGINT NULL,
    PRIMARY KEY (id),
    KEY idx_photos_uploader (uploader_id, created_at),
    KEY idx_photos_attached (attached_item_id),
    CONSTRAINT fk_photos_uploader FOREIGN KEY (uploader_id) REFERENCES users (id),
    CONSTRAINT fk_photos_item FOREIGN KEY (attached_item_id) REFERENCES items (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

ALTER TABLE items
    ADD CONSTRAINT fk_items_photo FOREIGN KEY (photo_id) REFERENCES photos (id);
