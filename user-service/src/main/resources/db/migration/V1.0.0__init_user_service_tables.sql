-- ============================================================================================
-- FLYWAY MIGRATION: USER SERVICE SCHEMA (PRODUCTION-READY)
-- FILE: V1.0.0__init_user_service_tables.sql
-- DATABASE: MySQL 8.0+ (InnoDB)
-- VALIDATION STATUS: PRODUCTION READY ✅
--
-- ROLE OF USER SERVICE:
--   - Identity Provider (Auth, Profile, Security).
--   - Address Book Management (Geospatial).
--   - Session & Device Management.
--   - Security monitoring and audit trail.
--
-- CHANGELOG (Production Updates):
--   - ADDED login_attempts table for rate limiting and brute-force protection
--   - ADDED user_activity_log table for audit trail and compliance
--   - ADDED user_preferences table for app settings and personalization
-- ============================================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================================
-- 1. USERS (CORE IDENTITY)
-- ============================================================================================

CREATE TABLE IF NOT EXISTS users (
    user_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),

    -- Canonical normalized values (trim, lower-case, E.164)
    email VARCHAR(255) NOT NULL,
    phone_number VARCHAR(20) NOT NULL,

    password_hash VARCHAR(255) NOT NULL,  -- bcrypt/argon2/SCrypt hash
    full_name VARCHAR(100),
    is_verified BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,       -- Soft delete
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_phone UNIQUE (phone_number),
    INDEX idx_users_created_at (created_at)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 2. SAVED ADDRESSES (GEOSPATIAL)
-- ============================================================================================

CREATE TABLE IF NOT EXISTS saved_addresses (
    address_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id CHAR(36) NOT NULL,

    label VARCHAR(50),             -- e.g., "Home", "Work"
    street_line1 VARCHAR(255),
    street_line2 VARCHAR(255),
    locality VARCHAR(255),
    city VARCHAR(100),
    state VARCHAR(100),
    zip_code VARCHAR(20),
    country VARCHAR(100) DEFAULT 'India',

    -- Canonical geospatial column (Required NOT NULL for Spatial Index)
    location_point POINT NOT NULL SRID 4326,

    -- Generated columns for backward compatibility / easy reading
    latitude DECIMAL(10,8) AS (ST_Y(location_point)) VIRTUAL,
    longitude DECIMAL(11,8) AS (ST_X(location_point)) VIRTUAL,

    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_saved_addresses_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT uq_saved_addresses_user_label UNIQUE (user_id, label)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE SPATIAL INDEX spidx_saved_addresses_location ON saved_addresses (location_point);
CREATE INDEX idx_saved_addresses_user_id ON saved_addresses (user_id);
CREATE INDEX idx_saved_addresses_user_default ON saved_addresses (user_id, is_default);

-- ============================================================================================
-- 3. SECURITY & VERIFICATION
-- ============================================================================================

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    token_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id CHAR(36) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_prt_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    INDEX idx_prt_token_hash (token_hash)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS email_phone_verification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id CHAR(36) NOT NULL,
    channel VARCHAR(10) NOT NULL,         -- EMAIL, SMS
    code_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'SENT',    -- SENT, VERIFIED, EXPIRED, FAILED
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_epv_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT chk_epv_channel CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT chk_epv_status CHECK (status IN ('SENT', 'VERIFIED', 'EXPIRED', 'FAILED')),

    INDEX idx_epv_user_id (user_id),
    INDEX idx_epv_expires_at (expires_at)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- NEW: LOGIN ATTEMPTS (Rate Limiting & Brute-Force Protection)
-- ============================================================================================

CREATE TABLE IF NOT EXISTS login_attempts (
    attempt_id BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- Can be email, phone, or user_id (for tracking across identifiers)
    identifier VARCHAR(255) NOT NULL,
    identifier_type VARCHAR(20) NOT NULL, -- EMAIL, PHONE, USER_ID, IP_ADDRESS

    attempt_status VARCHAR(20) NOT NULL, -- SUCCESS, FAILED, BLOCKED
    failure_reason VARCHAR(100) NULL, -- WRONG_PASSWORD, ACCOUNT_LOCKED, INVALID_USER

    ip_address VARCHAR(45) NULL, -- Supports IPv6
    user_agent TEXT NULL,

    attempted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_login_identifier_type CHECK (identifier_type IN ('EMAIL', 'PHONE', 'USER_ID', 'IP_ADDRESS')),
    CONSTRAINT chk_login_attempt_status CHECK (attempt_status IN ('SUCCESS', 'FAILED', 'BLOCKED'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Index for rate limiting queries: "How many failed attempts in last 15 minutes?"
CREATE INDEX idx_login_attempts_identifier_time ON login_attempts (identifier, attempted_at DESC);
CREATE INDEX idx_login_attempts_ip_time ON login_attempts (ip_address, attempted_at DESC);
CREATE INDEX idx_login_attempts_status ON login_attempts (attempt_status, attempted_at DESC);

-- ============================================================================================
-- NEW: USER ACTIVITY LOG (Audit Trail & Compliance)
-- ============================================================================================

CREATE TABLE IF NOT EXISTS user_activity_log (
    log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id CHAR(36) NOT NULL,

    activity_type VARCHAR(50) NOT NULL, -- LOGIN, LOGOUT, PASSWORD_CHANGE, EMAIL_CHANGE, etc.
    activity_description VARCHAR(500) NULL,

    ip_address VARCHAR(45) NULL,
    user_agent TEXT NULL,

    metadata JSON NULL, -- Additional context (e.g., device info, location)

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_activity_log_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    INDEX idx_activity_log_user_time (user_id, created_at DESC),
    INDEX idx_activity_log_type (activity_type, created_at DESC)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 4. SESSIONS (REFRESH TOKENS)
-- ============================================================================================

CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id CHAR(36) NOT NULL,
    device_id VARCHAR(255),
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    INDEX idx_rt_user_id (user_id),
    INDEX idx_rt_token (token_hash)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- NEW: USER PREFERENCES (App Settings & Personalization)
-- ============================================================================================

CREATE TABLE IF NOT EXISTS user_preferences (
    preference_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id CHAR(36) NOT NULL,

    -- Notification preferences
    email_notifications BOOLEAN DEFAULT TRUE,
    sms_notifications BOOLEAN DEFAULT TRUE,
    push_notifications BOOLEAN DEFAULT TRUE,

    -- App preferences
    language VARCHAR(10) DEFAULT 'en',
    theme VARCHAR(20) DEFAULT 'light', -- light, dark, auto
    currency VARCHAR(3) DEFAULT 'INR',

    -- Marketing preferences (GDPR/Privacy compliance)
    marketing_emails BOOLEAN DEFAULT FALSE,
    data_sharing_consent BOOLEAN DEFAULT FALSE,

    -- Additional preferences stored as JSON for flexibility
    custom_preferences JSON NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_preferences_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT uq_preferences_user UNIQUE (user_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 5. OPERATIONAL METADATA
-- ============================================================================================

CREATE TABLE IF NOT EXISTS user_service_meta (
    key_name VARCHAR(100) PRIMARY KEY,
    key_value VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;