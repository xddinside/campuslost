-- OTP-verified handover. One current challenge per approved found-item claim.
-- The plaintext pickup code is never stored; code_digest is an HMAC-SHA256
-- digest bound to the claim id and generation.
CREATE TABLE IF NOT EXISTS handover_challenges (
    id BIGINT NOT NULL AUTO_INCREMENT,
    claim_id BIGINT NOT NULL,
    code_digest VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    next_issue_at DATETIME(6) NULL,
    verified_at DATETIME(6) NULL,
    verified_by_id BIGINT NULL,
    generation INT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_handover_challenge_claim (claim_id),
    KEY idx_handover_challenge_verifier (verified_by_id),
    CONSTRAINT fk_handover_challenge_claim FOREIGN KEY (claim_id) REFERENCES claims (id),
    CONSTRAINT fk_handover_challenge_verifier FOREIGN KEY (verified_by_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
