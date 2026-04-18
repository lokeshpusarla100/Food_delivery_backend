-- ============================================================================================
-- FLYWAY MIGRATION: ANALYTICS SERVICE SCHEMA (50M+ USERS SCALE)
-- FILE: V1.0.0__init_analytics_service.sql
-- DATABASE: MySQL 8.0+ (InnoDB)
-- STATUS: HARDENED & SCALABLE ✅
--
-- ROLE OF ANALYTICS SERVICE:
--   - Serves pre-computed "Gold Layer" metrics to Restaurant App, Driver App, Ops Dashboard.
--   - Stores summarized data only -> raw clickstream stays in Snowflake/BigQuery.
--   - Supports partition pruning, fast dashboard loads, and async report generation.
-- ============================================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================================
-- 1. RESTAURANT ANALYTICS (Owner Dashboard)
-- ============================================================================================

/*
TABLE: restaurant_daily_metrics
-----------------------------------------------------------------------------------------------
PURPOSE:
   Powers daily sales graphs in Partner App.
PATTERN:
   Updated by stream aggregators (Kafka -> Flink/Spark -> MySQL Upsert).
PARTITION:
   By metric_date (yearly). Efficient deletion, fast scans.
*/
CREATE TABLE restaurant_daily_metrics (
    restaurant_id CHAR(36) NOT NULL,             -- UUID from Restaurant Service
    metric_date DATE NOT NULL,
    total_revenue BIGINT DEFAULT 0,              -- paise/cents
    total_orders INT DEFAULT 0,
    delivered_orders INT DEFAULT 0,
    cancelled_orders INT DEFAULT 0,
    avg_prep_time_sec INT DEFAULT 0,
    avg_rating DECIMAL(3,2) DEFAULT 0.00,
    unique_customers INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- PK enables partitioning + dedupe
    PRIMARY KEY (restaurant_id, metric_date),

    -- Common queries: "last 30 days"
    INDEX idx_rest_date (metric_date)
)
ENGINE=InnoDB
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(metric_date) (
    PARTITION p_2025 VALUES LESS THAN ('2026-01-01'),
    PARTITION p_2026 VALUES LESS THAN ('2027-01-01'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- ============================================================================================
-- MENU PERFORMANCE (Item-level insights)
-- ============================================================================================

/*
TABLE: menu_item_daily_performance
-----------------------------------------------------------------------------------------------
PURPOSE:
   "Top Selling Items", "Slow Moving Items"
FIXED PK:
   Must include restaurant_id because item_id may be reused across restaurants.
*/
CREATE TABLE menu_item_daily_performance (
    restaurant_id CHAR(36) NOT NULL,
    item_id CHAR(36) NOT NULL,
    metric_date DATE NOT NULL,
    quantity_sold INT DEFAULT 0,
    revenue_generated BIGINT DEFAULT 0,

    -- FIXED COMPOSITE PK
    PRIMARY KEY (restaurant_id, item_id, metric_date),

    -- Frequently queried: items of a restaurant by date
    INDEX idx_item_rest_date (restaurant_id, metric_date)
)
ENGINE=InnoDB
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(metric_date) (
    PARTITION p_2025 VALUES LESS THAN ('2026-01-01'),
    PARTITION p_2026 VALUES LESS THAN ('2027-01-01'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- ============================================================================================
-- 2. DRIVER ANALYTICS (Driver App Earnings)
-- ============================================================================================

/*
TABLE: driver_daily_metrics
-----------------------------------------------------------------------------------------------
PURPOSE:
   Powers the "Earnings", "Hours Online", and "Trips Completed" graphs.
DATA SOURCE:
   Fleet Service (shifts), Delivery Service (distance), Payment Service (earnings).
*/
CREATE TABLE driver_daily_metrics (
    driver_id CHAR(36) NOT NULL,
    metric_date DATE NOT NULL,
    total_earnings BIGINT DEFAULT 0,
    trips_completed INT DEFAULT 0,
    online_duration_sec INT DEFAULT 0,
    on_trip_duration_sec INT DEFAULT 0,
    distance_traveled_km DECIMAL(8,2) DEFAULT 0.00,
    acceptance_rate DECIMAL(5,2) DEFAULT 0.00,

    PRIMARY KEY (driver_id, metric_date),
    INDEX idx_driver_date (metric_date)
)
ENGINE=InnoDB
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(metric_date) (
    PARTITION p_2025 VALUES LESS THAN ('2026-01-01'),
    PARTITION p_2026 VALUES LESS THAN ('2027-01-01'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- ============================================================================================
-- 3. PLATFORM OPS (Zone Heatmaps)
-- ============================================================================================

/*
TABLE: zone_hourly_metrics
-----------------------------------------------------------------------------------------------
PURPOSE:
   Ops "God View" dashboard.
   Shows supply/demand mismatches per zone per hour.
FIX:
   Added composite index (zone_id, metric_hour) for heatmap queries.
*/
CREATE TABLE zone_hourly_metrics (
    zone_id INT NOT NULL,
    metric_hour TIMESTAMP NOT NULL,
    active_drivers INT DEFAULT 0,
    orders_created INT DEFAULT 0,
    orders_unfulfilled INT DEFAULT 0,
    avg_delivery_time_sec INT DEFAULT 0,
    surge_multiplier DECIMAL(3,2) DEFAULT 1.0,
    total_gmv BIGINT DEFAULT 0,

    PRIMARY KEY (zone_id, metric_hour),

    -- FIXED: Needed for heatmap scan efficiency
    INDEX idx_zone_hour_full (zone_id, metric_hour)
)
ENGINE=InnoDB
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(metric_hour) (
    PARTITION p_2025_q4 VALUES LESS THAN ('2026-01-01 00:00:00'),
    PARTITION p_2026_q1 VALUES LESS THAN ('2026-04-01 00:00:00'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- ============================================================================================
-- 4. REPORT SCHEDULER (PDF/CSV Generator)
-- ============================================================================================

/*
TABLE: report_schedules
-----------------------------------------------------------------------------------------------
PURPOSE:
   Manages recurring or one-time report generation.
USAGE:
   Background worker polls next_run_at.
*/
CREATE TABLE report_schedules (
    schedule_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    requester_id CHAR(36) NOT NULL,             -- Restaurant/Driver/Admin
    requester_role VARCHAR(20) NOT NULL,        -- 'ADMIN', 'MERCHANT', 'DRIVER'
    report_type VARCHAR(50) NOT NULL,           -- 'SALES_CSV', 'PAYOUT_SUMMARY'
    frequency VARCHAR(20) NOT NULL,             -- DAILY, WEEKLY, MONTHLY, ONCE
    email_target VARCHAR(255) NULL,
    is_active BOOLEAN DEFAULT TRUE,
    last_run_at TIMESTAMP NULL,
    next_run_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_schedules_runner (is_active, next_run_at),
    CONSTRAINT chk_freq CHECK (frequency IN ('DAILY','WEEKLY','MONTHLY','ONCE'))
)
ENGINE=InnoDB
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

/*
TABLE: generated_reports
-----------------------------------------------------------------------------------------------
PURPOSE:
   Stores the S3 URLs to generated PDF/CSV reports.
FIX:
   Added status validation to prevent garbage string values.
*/
CREATE TABLE generated_reports (
    report_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NULL,
    requester_id CHAR(36) NOT NULL,
    file_url VARCHAR(512) NOT NULL,
    file_size_bytes BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATED',
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL, -- Cleanup worker deletes expired reports

    INDEX idx_reports_requester (requester_id),
    INDEX idx_reports_expiry (expires_at),

    CONSTRAINT chk_report_status
        CHECK (status IN ('GENERATED','FAILED','EXPIRED'))
)
ENGINE=InnoDB
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;