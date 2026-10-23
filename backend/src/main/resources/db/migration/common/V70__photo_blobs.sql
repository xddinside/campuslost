-- Database-backed photo bytes. Hosts with ephemeral disks (e.g. Render)
-- cannot keep uploaded files between deploys, so the photo blob store can
-- persist re-encoded image bytes here instead of on the filesystem. Local
-- development keeps using the filesystem store and this table stays empty.
CREATE TABLE IF NOT EXISTS photo_blobs (
    photo_id VARCHAR(36) NOT NULL,
    data LONGBLOB NOT NULL,
    PRIMARY KEY (photo_id),
    CONSTRAINT fk_photo_blobs_photo FOREIGN KEY (photo_id) REFERENCES photos (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
