-- Multi-worker at-least-once publication; retries are idempotency-keyed on event_id.
ALTER TABLE restaurant_outbox DROP CHECK chk_outbox_status;
ALTER TABLE restaurant_outbox ADD CONSTRAINT chk_outbox_status
  CHECK(status IN ('PENDING','PUBLISHING','PUBLISHED','FAILED'));
ALTER TABLE restaurant_outbox
  ADD COLUMN next_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  ADD COLUMN claimed_until DATETIME(6) NULL,
  ADD COLUMN last_error VARCHAR(255) NULL;
CREATE INDEX idx_rest_outbox_claim ON restaurant_outbox(status,next_retry_at,claimed_until,created_at);
