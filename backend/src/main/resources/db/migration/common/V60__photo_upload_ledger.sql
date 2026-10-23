-- Durable upload accounting. Quota reads this ledger rather than surviving
-- photo rows, so deleting a photo can never hand the allowance back.
CREATE TABLE IF NOT EXISTS photo_uploads (
    id BIGINT NOT NULL AUTO_INCREMENT,
    uploader_id BIGINT NOT NULL,
    photo_id VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_photo_uploads_quota (uploader_id, created_at),
    CONSTRAINT fk_photo_uploads_uploader FOREIGN KEY (uploader_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Charge existing uploads so the ledger agrees with what users already spent.
INSERT INTO photo_uploads (uploader_id, photo_id, created_at)
SELECT uploader_id, id, created_at FROM photos;
