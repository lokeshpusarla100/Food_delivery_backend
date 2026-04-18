-- ============================================================================================
-- FLYWAY MIGRATION: NOTIFICATION SERVICE SCHEMA (50M+ USERS SCALE)
-- FILE: V1.0.0__init_notification_service.sql
-- DATABASE: MySQL 8.0+ (InnoDB)
-- STATUS: HARDENED & SCALABLE ✅
--
-- ROLE OF NOTIFICATION SERVICE:
--   - Central "Post Office" for SMS, Email, Push, WhatsApp.
--   - Manages Vendor Failover (Twilio -> Gupshup, etc.).
--   - Stores In-App Notification History (partitioned, 90-day+ retention).
--   - Handles Idempotency, Rate Limiting, Forensics.
--
-- NOTE:
--   * All user_id fields are CHAR(36) to align with UUID-based User/Driver/Merchant IDs.
--   * No hard FKs to other microservices (Users, Orders, etc.) by design.
-- ============================================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================================
-- 1. CONFIGURATION & TEMPLATES (STATIC / LOW-VOLUME)
-- ============================================================================================

/*
TABLE: notification_providers
-----------------------------------------------------------------------------------------------
PURPOSE:
  Registry of 3rd party providers (Twilio, SendGrid, FCM, WhatsApp BSPs).

WHY IT EXISTS:
  - Allows runtime routing / failover:
      * Prefer FCM for PUSH, fallback to another.
      * Prefer Provider A for SMS, fallback to Provider B.
  - Prevents hardcoding credentials in code.

NORMALIZATION:
  - PK: provider_id
  - JSON fields (config) intentionally break 1NF for flexibility (per-provider config structures).
*/

CREATE TABLE notification_providers (
    provider_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,               -- 'TWILIO', 'SENDGRID', 'FCM'
    channel_type VARCHAR(20) NOT NULL,              -- 'SMS', 'EMAIL', 'PUSH', 'WHATSAPP'
    config JSON NOT NULL,                           -- Encrypted API keys, URLs, headers, etc.
    priority INT DEFAULT 1,                         -- Higher = preferred
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_provider_channel
        CHECK (channel_type IN ('SMS', 'EMAIL', 'PUSH', 'WHATSAPP'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

/*
TABLE: notification_templates
-----------------------------------------------------------------------------------------------
PURPOSE:
  Stores template definitions used to render notifications (subject/body).

WHY IT EXISTS:
  - Marketing / ops can update message content without code changes.
  - Supports multiple versions for A/B testing or rollback.

NORMALIZATION:
  - PK: (template_code, version, channel_type)
  - All non-key attributes depend on this key → BCNF.
*/
CREATE TABLE notification_templates (
    template_code VARCHAR(100) NOT NULL,           -- e.g. 'ORDER_CONFIRMED'
    version INT NOT NULL DEFAULT 1,
    channel_type VARCHAR(20) NOT NULL,            -- SMS / EMAIL / PUSH / WHATSAPP
    subject_template VARCHAR(255),                -- For email/push title
    body_template TEXT NOT NULL,                  -- Body with placeholders
    ttl_days INT DEFAULT 90,                      -- life of this template version
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (template_code, version, channel_type),
    CONSTRAINT chk_template_channel
        CHECK (channel_type IN ('SMS', 'EMAIL', 'PUSH', 'WHATSAPP'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 2. USER PREFERENCES, DEVICES & RATE LIMITS
-- ============================================================================================

/*
TABLE: user_notification_settings
-----------------------------------------------------------------------------------------------
PURPOSE:
  Holds user-level opt-in / opt-out preferences.

WHY IT EXISTS:
  - Legal: must honor user's marketing opt-out.
  - Technical: avoid enqueuing jobs that will be dropped anyway.

NOTES:
  - One row per user_id.
*/
CREATE TABLE user_notification_settings (
    user_id CHAR(36) PRIMARY KEY,                  -- UUID from User Service / Driver Service
    marketing_enabled BOOLEAN DEFAULT TRUE,
    transactional_enabled BOOLEAN DEFAULT TRUE,    -- usually TRUE; rarely disabled
    channel_overrides JSON NULL,                   -- {"sms": false, "email": true}
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

/*
TABLE: user_device_tokens
-----------------------------------------------------------------------------------------------
PURPOSE:
  Maps users to their device push tokens (FCM/APNS/WebPush).

WHY IT EXISTS:
  - Required to send PUSH notifications reliably.
  - Required to disable dead/uninstalled app tokens gracefully.

SCALING:
  - Read pattern: find all active tokens for a user.
  - Write pattern: upserts during app login / token refresh.
*/
CREATE TABLE user_device_tokens (
    token_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    device_token VARCHAR(2048) NOT NULL,          -- long tokens for APNS/FCM v2
    platform VARCHAR(20) NOT NULL,                -- 'ANDROID', 'IOS', 'WEB'
    is_active BOOLEAN DEFAULT TRUE,
    last_failure_reason VARCHAR(255) NULL,        -- 'NotRegistered', 'InvalidToken'
    last_used_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_token_platform
        CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),

    INDEX idx_tokens_user (user_id, is_active),

    -- Helps dedupe: one user should not have the same token many times.
    UNIQUE KEY uq_user_device (user_id, device_token(255)),
    INDEX idx_device_token (device_token(255))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

/*
TABLE: notification_rate_limits
-----------------------------------------------------------------------------------------------
PURPOSE:
  Enforces per-user, per-channel daily caps (Spam & regulatory compliance).

WHY IT EXISTS:
  - SMS cap per day (TRAI/WhatsApp rules).
  - Protects from infinite loops or buggy callers.

ACCESS PATTERN:
  - Before enqueueing new job: check daily_count for (user_id, channel_type, today).
*/
CREATE TABLE notification_rate_limits (
    user_id CHAR(36) NOT NULL,
    channel_type VARCHAR(20) NOT NULL,            -- SMS / EMAIL / PUSH / WHATSAPP / IN_APP
    daily_count INT DEFAULT 0,
    reset_at DATE NOT NULL,                       -- date this counter belongs to
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, channel_type),
    INDEX idx_rate_limit_reset (reset_at),
    CONSTRAINT chk_rate_channel
        CHECK (channel_type IN ('SMS', 'EMAIL', 'PUSH', 'WHATSAPP', 'IN_APP'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 3. IN-APP INBOX (PARTITIONED TIME-SERIES)
-- ============================================================================================

/*
TABLE: in_app_notifications
-----------------------------------------------------------------------------------------------
PURPOSE:
  Powers the "notification bell" screen inside the app.

WHY PARTITIONED:
  - Very high volume at scale.
  - Retention typically 60–180 days.
  - Dropping old partitions is O(1) vs DELETE which is O(n).

ACCESS PATTERNS:
  - List latest N notifications for a user (most common).
  - Mark all as read.

PARTITION RULE:
  - created_at in PK to satisfy MySQL's partitioning rule (partition column in every PK/UNIQUE).
*/
CREATE TABLE in_app_notifications (
    notification_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id CHAR(36) NOT NULL,
    category VARCHAR(50) NOT NULL,                -- 'ORDER','PROMO','SYSTEM','ALERT'
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    image_url VARCHAR(512) NULL,
    action_link VARCHAR(512) NULL,                -- app deep link / web URL
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (notification_id, created_at),

    -- Fetch latest unread/read items per user
    INDEX idx_inapp_user (user_id, created_at),
    INDEX idx_inapp_user_unread (user_id, is_read, created_at),

    CONSTRAINT chk_inapp_category
        CHECK (category IN ('ORDER', 'PROMO', 'SYSTEM', 'ALERT'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(created_at) (
    PARTITION p_2024_01 VALUES LESS THAN ('2024-02-01 00:00:00'),
    PARTITION p_2024_02 VALUES LESS THAN ('2024-03-01 00:00:00'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- ============================================================================================
-- 4. JOB QUEUE (OUTBOX PATTERN) & LOGS
-- ============================================================================================

/*
TABLE: notification_jobs
-----------------------------------------------------------------------------------------------
PURPOSE:
  Acts as the "hot queue" of messages waiting to be sent.

WHY IT EXISTS:
  - Decouples producers (Order Service, Delivery Service, Payment Service) from actual send.
  - Supports retries, backoff, and idempotency.

IDEMPOTENCY:
  - idempotency_key ensures the same logical notification isn't inserted twice
    (e.g. replays from Kafka, HTTP retries).
*/
CREATE TABLE notification_jobs (
    job_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    user_id CHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,            -- 'ORDER_PLACED', 'DRIVER_ARRIVED', etc.
    channel_type VARCHAR(20) NOT NULL,           -- SMS/EMAIL/PUSH/WHATSAPP/IN_APP
    payload JSON NOT NULL,                       -- template variables + template code if needed
    status VARCHAR(20) DEFAULT 'PENDING',        -- PENDING,PROCESSING,FAILED,COMPLETED
    retry_count INT DEFAULT 0,
    next_retry_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uq_job_idempotency (idempotency_key),

    -- Workers scan by (status, next_retry_at <= NOW)
    INDEX idx_jobs_processing (status, next_retry_at),

    -- For user-centric debugging: "what messages are queued for this user?"
    INDEX idx_jobs_user (user_id),

    CONSTRAINT chk_job_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'FAILED', 'COMPLETED')),
    CONSTRAINT chk_job_channel
        CHECK (channel_type IN ('SMS', 'EMAIL', 'PUSH', 'WHATSAPP', 'IN_APP'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

/*
TABLE: notification_logs
-----------------------------------------------------------------------------------------------
PURPOSE:
  Immutable log of send attempts/outcomes for external channels (SMS/Email/Push/WhatsApp).

WHY PARTITIONED:
  - High volume write-only table (millions/day).
  - Needed for:
      * Vendor billing reconciliation
      * Delivery rate analytics
      * Debugging "I didn't get the message"

FK NOTE:
  - MySQL historically disallowed FKs on partitioned tables. To keep portability, we avoid
    FKs here and treat provider_id as a logical link to notification_providers.
*/
CREATE TABLE notification_logs (
    log_id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    user_id CHAR(36) NOT NULL,
    provider_id INT NOT NULL,
    status VARCHAR(20) NOT NULL,                 -- SENT,DELIVERED,FAILED,BOUNCED
    provider_response JSON NULL,                 -- raw provider payload
    cost DECIMAL(10, 6) DEFAULT 0,              -- per-message cost in provider currency
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (log_id, sent_at),

    INDEX idx_logs_user (user_id),
    INDEX idx_logs_job (job_id),
    INDEX idx_logs_provider (provider_id),
    INDEX idx_logs_status (status, sent_at),

    CONSTRAINT chk_log_status
        CHECK (status IN ('SENT', 'DELIVERED', 'FAILED', 'BOUNCED'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(sent_at) (
    PARTITION p_log_2024_01 VALUES LESS THAN ('2024-02-01 00:00:00'),
    PARTITION p_log_2024_02 VALUES LESS THAN ('2024-03-01 00:00:00'),
    PARTITION p_log_future VALUES LESS THAN (MAXVALUE)
);

/*
TABLE: notification_failures_archive
-----------------------------------------------------------------------------------------------
PURPOSE:
  Secondary store for serious failures (e.g., code bugs, provider downtime analysis).

WHY SEPARATE FROM LOGS:
  - notification_logs is hot; we keep it lean.
  - This table is used by SRE / backend team for post-mortems.

USAGE:
  - Only write for "hard" failures (unhandled exceptions, systemic issues).
*/
CREATE TABLE notification_failures_archive (
    archive_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    user_id CHAR(36) NOT NULL,
    failure_reason TEXT,
    stack_trace TEXT,
    captured_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_archive_job (job_id),
    INDEX idx_archive_user (user_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;


-- MySQL Partitioning Compliance (PASSED)

-- Rule: If a table is partitioned, the partition key must be part of the Primary Key.

-- Check:

-- in_app_notifications: PK is (notification_id, created_at). Partitioned by created_at. Pass.

-- notification_logs: PK is (log_id, sent_at). Partitioned by sent_at. Pass.

-- Scalability Guardrails (PASSED)

-- Device Tokens: You used VARCHAR(2048) for APNS/FCM tokens (necessary for newer protocols) but smartly used a Prefix Index device_token(255) for the Unique Key.

-- Why this matters: InnoDB has a max index key length of 3072 bytes. Indexing the full 2048 chars (which could be ~8KB in utf8mb4) would cause a "Index column size too large" crash. Your prefix index prevents this.

-- Rate Limiting: The notification_rate_limits table structure supports high-concurrency INSERT ... ON DUPLICATE KEY UPDATE operations, which is much faster than SELECT + UPDATE.

-- Data Integrity (PASSED)

-- Idempotency: The UNIQUE KEY uq_job_idempotency on notification_jobs is the gold standard. It pushes the guarantee down to the database level, so even if your worker crashes and restarts, it cannot double-enqueue
--a message.

-- Type Safety: You replaced ENUMs with CHECK constraints (e.g., CHECK (channel_type IN ...)). This allows you to add new channels (like 'SLACK') in the future via a simple ALTER TABLE without rewriting the entire
 --table file (which happens when modifying MySQL ENUMs).

-- Operational Maintenance (PASSED)

-- Retention: The partitioning on in_app_notifications and logs allows you to drop data older than 90 days instantly (ALTER TABLE DROP PARTITION), preventing the "Delete Lag" that kills database performance.

