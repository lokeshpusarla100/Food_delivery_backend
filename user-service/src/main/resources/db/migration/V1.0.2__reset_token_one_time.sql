-- Password reset tokens must be single-use.
ALTER TABLE password_reset_tokens ADD COLUMN consumed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX uq_password_reset_hash ON password_reset_tokens(token_hash);
