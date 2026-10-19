-- Additive migration: never modify V1 on an existing Flyway-managed database.
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uq_refresh_tokens_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_user_revoked ON refresh_tokens(user_id, revoked, expires_at);
CREATE INDEX idx_login_attempt_window ON login_attempts(identifier, attempt_status, attempted_at);
CREATE TABLE email_verification_tokens (
  token_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id CHAR(36) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  consumed BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_email_verify_user FOREIGN KEY(user_id) REFERENCES users(user_id),
  CONSTRAINT uq_email_verify_hash UNIQUE(token_hash),
  INDEX idx_email_verify_user (user_id, consumed)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
