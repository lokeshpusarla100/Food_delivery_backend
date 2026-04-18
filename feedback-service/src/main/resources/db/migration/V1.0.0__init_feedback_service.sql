-- ============================================================================================
-- FLYWAY MIGRATION: FEEDBACK SERVICE SCHEMA (50M+ USERS SCALE)
-- FILE: V1.0.0__init_feedback_service.sql
-- DATABASE: MySQL 8.0+ (InnoDB)
--
-- ROLE OF FEEDBACK SERVICE:
--   - Centralized rating engine for Restaurants, Drivers, Items, and Customers.
--   - Manages Review Content (Text, Tags, Images).
--   - Calculates and serves Aggregated Scores (e.g., "4.5 stars (1.2k ratings)").
--   - Moderation workflow for abusive content.
--
-- ARCHITECTURE:
--   - Polymorphic: One 'reviews' table for all entity types (Restaurant/Driver/Item).
--   - Read-Optimized: 'rating_aggregates' table is pre-calculated for fast UI loading.
-- ============================================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================================
-- 1. CONFIGURATION (TAGS & REASONS)
-- ============================================================================================

/*
TABLE: feedback_tags_master
-----------------------------------------------------------------------------------------------
PURPOSE:
  Registry of standardized tags users can select (e.g., "Late Delivery", "Cold Food", "Great Taste").
WHY:
  - Structured data is better than text comments for analytics.
  - Different entities need different tags (Driver tags != Food tags).
*/
CREATE TABLE feedback_tags_master (
    tag_id INT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(20) NOT NULL, -- 'RESTAURANT', 'DRIVER', 'ITEM', 'CUSTOMER'
    sentiment VARCHAR(10) NOT NULL,   -- 'POSITIVE', 'NEGATIVE', 'NEUTRAL'
    tag_label VARCHAR(50) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_tags_lookup (entity_type, sentiment),
    CONSTRAINT chk_tag_sentiment CHECK (sentiment IN ('POSITIVE', 'NEGATIVE', 'NEUTRAL'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 2. CORE REVIEWS (PARTITIONED)
-- ============================================================================================

/*
TABLE: reviews
-----------------------------------------------------------------------------------------------
PURPOSE:
  The master record for a single rating event.
POLYMORPHISM:
  - `subject_id` + `subject_type` allows rating ANY entity (Driver, Restaurant, Dish).
  - IDs are CHAR(36) to match User/Catalog/Fleet service UUIDs.
PARTITIONING:
  - Partitioned by `created_at` (Yearly).
  - PK must include `created_at`.
*/
CREATE TABLE reviews (
    review_id BIGINT AUTO_INCREMENT,
    order_id CHAR(36) NOT NULL,               -- Link to Order Service (Proof of purchase)
    reviewer_id CHAR(36) NOT NULL,            -- Who submitted (User/Driver ID)

    subject_id CHAR(36) NOT NULL,             -- The UUID of the Restaurant/Driver/Dish being rated
    subject_type VARCHAR(20) NOT NULL,        -- 'RESTAURANT', 'DRIVER', 'ITEM', 'CUSTOMER'

    rating_score TINYINT NOT NULL,            -- 1 to 5
    comment_text TEXT NULL,

    status VARCHAR(20) DEFAULT 'PUBLISHED',   -- 'PUBLISHED', 'PENDING_MODERATION', 'HIDDEN'
    is_verified_purchase BOOLEAN DEFAULT TRUE,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- COMPOSITE PK for Partitioning
    PRIMARY KEY (review_id, created_at),

    -- Indexes for fetching reviews
    INDEX idx_reviews_subject (subject_id, subject_type, status, created_at),
    INDEX idx_reviews_reviewer (reviewer_id),

    -- Constraint: One review per subject per order
    UNIQUE KEY uq_order_subject (order_id, subject_id, created_at),

    CONSTRAINT chk_rating_range CHECK (rating_score BETWEEN 1 AND 5),
    CONSTRAINT chk_review_status CHECK (status IN ('PUBLISHED', 'PENDING_MODERATION', 'HIDDEN', 'ARCHIVED'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(created_at) (
    PARTITION p_2025 VALUES LESS THAN ('2026-01-01 00:00:00'),
    PARTITION p_2026 VALUES LESS THAN ('2027-01-01 00:00:00'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

/*
TABLE: review_selected_tags
-----------------------------------------------------------------------------------------------
PURPOSE:
  Links a review to specific tags ("Tasty", "Spicy").
*/
CREATE TABLE review_selected_tags (
    review_id BIGINT NOT NULL,
    tag_id INT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (review_id, tag_id),
    CONSTRAINT fk_rst_tag FOREIGN KEY (tag_id) REFERENCES feedback_tags_master(tag_id)
    -- No FK to reviews because `reviews` is partitioned. Logical link only.
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

/*
TABLE: review_media
-----------------------------------------------------------------------------------------------
PURPOSE:
  Stores photos/videos attached to a review.
  Separated because media is optional and 1 review can have N photos.
*/
CREATE TABLE review_media (
    media_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_id BIGINT NOT NULL,
    media_type VARCHAR(10) NOT NULL, -- 'IMAGE', 'VIDEO'
    media_url VARCHAR(512) NOT NULL,
    is_moderated BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_media_review (review_id),
    CONSTRAINT chk_media_type CHECK (media_type IN ('IMAGE', 'VIDEO'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 3. AGGREGATIONS (FAST READ LAYER)
-- ============================================================================================

/*
TABLE: rating_aggregates
-----------------------------------------------------------------------------------------------
PURPOSE:
  Serve "4.5 Stars" instantly without scanning the `reviews` table.
UPDATE PATTERN:
  - Updates occur asynchronously via worker (Eventual Consistency).
  - Stores histograms (count of 5-stars, 4-stars) to render UI bars.
*/
CREATE TABLE rating_aggregates (
    subject_id CHAR(36) NOT NULL,      -- RestaurantID / DriverID
    subject_type VARCHAR(20) NOT NULL,

    average_rating DECIMAL(3,2) DEFAULT 0.00,
    review_count INT DEFAULT 0,

    -- Histogram for UI bars (5 star: 100, 4 star: 20...)
    count_1_star INT DEFAULT 0,
    count_2_star INT DEFAULT 0,
    count_3_star INT DEFAULT 0,
    count_4_star INT DEFAULT 0,
    count_5_star INT DEFAULT 0,

    last_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (subject_id, subject_type)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 4. MODERATION & SAFETY
-- ============================================================================================

/*
TABLE: moderation_queue
-----------------------------------------------------------------------------------------------
PURPOSE:
  Holds reviews flagged by AI (Profanity filter) or Users ("Report this review").
WORKFLOW:
  - If AI detects toxicity -> Insert here -> Set review.status = 'PENDING_MODERATION'.
  - Admin approves/rejects -> Update review.status -> Delete from queue.
*/
CREATE TABLE moderation_queue (
    queue_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_id BIGINT NOT NULL,
    flag_reason VARCHAR(50) NOT NULL, -- 'PROFANITY', 'SPAM', 'PII_LEAK'
    risk_score DECIMAL(5,2) DEFAULT 0.00,
    status VARCHAR(20) DEFAULT 'OPEN', -- 'OPEN', 'RESOLVED', 'DISMISSED'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_mod_status (status),
    CONSTRAINT chk_mod_status CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;