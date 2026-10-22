-- Baseline schema for a brand new CampusLost database.
-- Existing audited databases are adopted by Flyway (baseline version 1) and
-- receive only V2 and later migrations.
CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    email VARCHAR(150) NOT NULL,
    name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(30) NULL,
    role VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category VARCHAR(60) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    date_occurred DATETIME(6) NOT NULL,
    description TEXT NOT NULL,
    location VARCHAR(120) NOT NULL,
    resolved_at DATETIME(6) NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(120) NOT NULL,
    type VARCHAR(10) NOT NULL,
    reporter_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_items_reporter (reporter_id),
    CONSTRAINT fk_items_reporter FOREIGN KEY (reporter_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS claims (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    claimant_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_claims_claimant (claimant_id),
    KEY idx_claims_item (item_id),
    CONSTRAINT fk_claims_claimant FOREIGN KEY (claimant_id) REFERENCES users (id),
    CONSTRAINT fk_claims_item FOREIGN KEY (item_id) REFERENCES items (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
