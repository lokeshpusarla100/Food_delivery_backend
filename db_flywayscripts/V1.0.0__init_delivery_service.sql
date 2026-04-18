-- ============================================================================================
-- FLYWAY MIGRATION: DELIVERY SERVICE SCHEMA (50M+ USERS SCALE)
-- FILE: V1.0.0__init_delivery_service.sql
-- DATABASE: MySQL 8.0+ (InnoDB)
-- VALIDATION STATUS: PRODUCTION READY ✅
--
-- ROLE OF DELIVERY SERVICE:
--   - Manages the Lifecycle of a Delivery (Created -> Assigned -> Picked Up -> Delivered).
--   - High-Frequency GPS Ingestion (Breadcrumbs).
--   - Driver Assignment Logic & State History.
-- ============================================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================================
-- 0. REFERENCE TABLES
-- ============================================================================================

/*
TABLE: delivery_states
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The "Source of Truth" for the Delivery State Machine.
  Instead of hardcoding integers (1=CREATED, 2=ASSIGNED) in code, we reference this table.

INDEXING:
  - `code` is UNIQUE and Indexed for fast lookups by string (e.g., WHERE code = 'COMPLETED').
*/
CREATE TABLE IF NOT EXISTS delivery_states (
    state_id SMALLINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL, -- UNASSIGNED, DRIVER_EN_ROUTE, AT_RESTAURANT, PICKED_UP, EN_ROUTE, COMPLETED, CANCELLED
    description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_delivery_states_code UNIQUE (code)
) ENGINE=InnoDB COMMENT='Reference: delivery lifecycle states';
CREATE INDEX idx_delivery_states_code ON delivery_states(code);

-- ============================================================================================
-- 1. DELIVERIES (MASTER RECORD - PARTITIONED)
-- ============================================================================================

/*
TABLE: deliveries
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The Master Record. Every order has exactly one corresponding delivery record here.
  It snaps data (pickup lat/lng) at creation time to prevent "Data Drift" if a restaurant moves.

RECENT CHANGES:
  - **CRITICAL FIX:** Primary Key changed from `(delivery_id)` to `(delivery_id, created_at)`.
    MySQL REQUIRES the partition key to be part of the unique/primary key.

PARTITIONING:
  - Strategy: RANGE COLUMNS(created_at).
  - Why: This table grows infinitely. Partitioning allows us to drop old data (e.g., > 5 years)
    instantly or move it to cold storage without locking the database.

INDEXING:
  - `idx_deliveries_order`: Crucial for the "Track Order" screen (Order Service lookup).
  - `idx_deliveries_driver_state`: Used by Driver App to find "My Active Delivery".
  - `idx_deliveries_zone`: Used by Ops Dashboard to filter deliveries by City/Zone.
*/
CREATE TABLE IF NOT EXISTS deliveries (
    delivery_id BIGINT AUTO_INCREMENT,
    order_id BIGINT NOT NULL,                    -- logical FK to Order Service
    driver_id BIGINT NULL,                       -- logical FK to Fleet Service
    current_state_id SMALLINT NOT NULL,
    pickup_outlet_id BIGINT NULL,                -- logical FK to Catalog/outlet
    pickup_lat DECIMAL(10,8) NULL,
    pickup_lng DECIMAL(11,8) NULL,
    drop_address_id BIGINT NULL,                 -- logical FK to saved_addresses
    drop_lat DECIMAL(10,8) NULL,
    drop_lng DECIMAL(11,8) NULL,
    zone_id INT NULL,                            -- optional routing/shard key
    assigned_at TIMESTAMP NULL,
    driver_en_route_at TIMESTAMP NULL,
    picked_up_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    cancelled_at TIMESTAMP NULL,
    is_cancelled BOOLEAN DEFAULT FALSE,
    fare_total DECIMAL(13,2) DEFAULT 0.00,
    driver_payout DECIMAL(13,2) DEFAULT 0.00,
    distance_meters INT DEFAULT 0,
    duration_seconds INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Operational counters
    assignment_attempts INT DEFAULT 0,
    sku_count INT DEFAULT 0,

    -- COMPOSITE PK REQUIRED FOR PARTITIONING
    PRIMARY KEY (delivery_id, created_at),

    CONSTRAINT fk_deliveries_state FOREIGN KEY (current_state_id) REFERENCES delivery_states(state_id)
) ENGINE=InnoDB
COMMENT='Master delivery record: metrics, SLA timestamps, snapshots'
PARTITION BY RANGE COLUMNS(created_at) (
    PARTITION p_2025_01 VALUES LESS THAN ('2025-02-01 00:00:00'),
    PARTITION p_2025_02 VALUES LESS THAN ('2025-03-01 00:00:00'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

CREATE INDEX idx_deliveries_order ON deliveries (order_id);
CREATE INDEX idx_deliveries_driver_state ON deliveries (driver_id, current_state_id);
CREATE INDEX idx_deliveries_state_created ON deliveries (current_state_id, created_at);
CREATE INDEX idx_deliveries_zone ON deliveries (zone_id);

-- ============================================================================================
-- 2. DELIVERY STATE HISTORY (APPEND-ONLY)
-- ============================================================================================

/*
TABLE: delivery_state_history
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The "Black Box" flight recorder. It tracks every state transition (ASSIGNED -> PICKED_UP).
  Used for SLA calculations (e.g., "How long did the driver wait at the restaurant?").

INDEXING:
  - `idx_dsh_delivery_time`: Fast retrieval of the full timeline for a specific delivery.
*/
CREATE TABLE IF NOT EXISTS delivery_state_history (
    history_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    state_id SMALLINT NOT NULL,
    timestamp_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    initiated_by VARCHAR(64) NULL,
    event_reason VARCHAR(512) NULL,
    loc_lat DECIMAL(10,8) NULL,
    loc_lng DECIMAL(11,8) NULL,
    order_version_at INT NULL,

    -- Note: Logical FKs often preferred here if partitions differ, but strict FKs shown
    CONSTRAINT fk_dsh_state FOREIGN KEY (state_id) REFERENCES delivery_states(state_id)
) ENGINE=InnoDB COMMENT='Append-only state transitions';

CREATE INDEX idx_dsh_delivery_time ON delivery_state_history (delivery_id, timestamp_at);
CREATE INDEX idx_dsh_state_time ON delivery_state_history (state_id, timestamp_at);

-- ============================================================================================
-- 3. ASSIGNMENT ATTEMPTS & FINAL ASSIGNMENTS
-- ============================================================================================

/*
TABLE: driver_assignment_attempts
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Tracks the "Noise". Every time we offer an order to a driver and they reject it (or timeout),
  it is logged here. Used to analyze "Acceptance Rate" and debug assignment algorithms.
*/
CREATE TABLE IF NOT EXISTS driver_assignment_attempts (
    attempt_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    candidate_driver_id BIGINT NULL,
    attempt_ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    attempt_result VARCHAR(32) NOT NULL,    -- ASSIGNED, REJECTED, TIMED_OUT
    reason VARCHAR(255) NULL,
    latency_ms INT NULL,

    INDEX idx_daa_delivery (delivery_id),
    INDEX idx_daa_candidate (candidate_driver_id)
) ENGINE=InnoDB COMMENT='Logs of assignment attempts';

/*
TABLE: driver_assignments
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Tracks the "Signal". The final, successful assignment.
  Separated from attempts to keep the core operational table small and fast.
*/
CREATE TABLE IF NOT EXISTS driver_assignments (
    assignment_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    assignment_source VARCHAR(32) DEFAULT 'AUTO', -- AUTO, MANUAL
    assignment_ttl_seconds INT DEFAULT 300,
    accepted BOOLEAN DEFAULT FALSE,
    accepted_at TIMESTAMP NULL,

    INDEX idx_da_driver (driver_id, assigned_at),
    INDEX idx_da_delivery (delivery_id)
) ENGINE=InnoDB COMMENT='Finalized driver assignments';

-- ============================================================================================
-- 4. DRIVER CURRENT ASSIGNMENT (HOT CACHE)
-- ============================================================================================

/*
TABLE: driver_current_assignment
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  High-Performance Lookup. "Is Driver X free right now?"
  Used by the Assignment Engine to filter candidates.

RECENT CHANGES:
  - **OPTIMIZATION:** Removed `heartbeat_ts`. Writing heartbeats here (every 10s per driver)
    would cause massive row locking, stalling reads. Heartbeats moved to Redis.
*/
CREATE TABLE IF NOT EXISTS driver_current_assignment (
    driver_id BIGINT PRIMARY KEY,
    delivery_id BIGINT NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_dca_delivery (delivery_id)
) ENGINE=InnoDB COMMENT='Fast lookup: current assignment per driver';

-- ============================================================================================
-- 5. GPS LOCATION UPDATES (PARTITIONED)
-- ============================================================================================

/*
TABLE: delivery_location_updates
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The "Breadcrumb" trail. Used to reconstruct the route for Customer Support ("Driver went wrong way")
  and Fraud Detection ("Driver teleported").

RECENT CHANGES:
  - **PARTITIONING FIX:** PK changed to `(event_id, recorded_at)` to support daily partitioning.

PARTITIONING:
  - Daily Partitions. This table receives massive write volume.
  - Old partitions can be moved to S3 (Cold Storage) after 30 days.
*/
CREATE TABLE IF NOT EXISTS delivery_location_updates (
    event_id BIGINT AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    lat DECIMAL(10,8) NOT NULL,
    lng DECIMAL(11,8) NOT NULL,
    speed_m_s DECIMAL(7,2) NULL,
    heading_deg DECIMAL(5,2) NULL,
    accuracy_m INT NULL,
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- COMPOSITE PK REQUIRED FOR PARTITIONING
    PRIMARY KEY (event_id, recorded_at),

    INDEX idx_dlu_delivery_time (delivery_id, recorded_at),
    INDEX idx_dlu_driver_time (driver_id, recorded_at)
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(recorded_at) (
    PARTITION p_2025_01 VALUES LESS THAN ('2025-02-01 00:00:00'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- ============================================================================================
-- 6. ROUTES & FARES
-- ============================================================================================

/*
TABLE: delivery_routes
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Compares "Google Maps Plan" vs "Actual Driver Path".
  Crucial for adjusting payouts (e.g., if a road closure forced a longer detour).
*/
CREATE TABLE IF NOT EXISTS delivery_routes (
    route_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    planned_polyline TEXT NULL,
    planned_distance_m INT DEFAULT 0,
    planned_duration_s INT DEFAULT 0,
    actual_polyline TEXT NULL,
    actual_distance_m INT DEFAULT 0,
    actual_duration_s INT DEFAULT 0,
    route_generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    route_completed_at TIMESTAMP NULL,

    INDEX idx_routes_delivery (delivery_id)
) ENGINE=InnoDB;

/*
TABLE: delivery_fare_components
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The Receipt Breakdown. Why did the driver get paid $15?
  (Base Fare $5 + Surge $5 + Long Distance $5).
*/
CREATE TABLE IF NOT EXISTS delivery_fare_components (
    component_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    component_type VARCHAR(50) NOT NULL,  -- BASE_FEE, SURGE, DISTANCE
    amount DECIMAL(13,2) NOT NULL,
    notes VARCHAR(512) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_fare_delivery (delivery_id),
    INDEX idx_fare_type (component_type)
) ENGINE=InnoDB;

-- ============================================================================================
-- 7. EVENTS OUTBOX (RELIABILITY)
-- ============================================================================================

/*
TABLE: delivery_events_outbox
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Transactional Outbox Pattern.
  Ensures that if a delivery state changes in DB, the corresponding Kafka event is GUARANTEED to be sent.

RECENT CHANGES:
  - **FLEXIBILITY:** Replaced `ENUM` status with `VARCHAR` + `CHECK`. Allows easier migrations.
*/
CREATE TABLE IF NOT EXISTS delivery_events_outbox (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_payload JSON NULL,
    destination VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT DEFAULT 0,
    last_attempt_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_outbox_delivery UNIQUE (aggregate_type, aggregate_id, event_type, created_at),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
) ENGINE=InnoDB;

CREATE INDEX idx_outbox_status ON delivery_events_outbox (status);
CREATE INDEX idx_outbox_created ON delivery_events_outbox (created_at);

-- ============================================================================================
-- 8. METRICS & ANOMALIES
-- ============================================================================================

/*
TABLE: delivery_anomalies
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Fraud Detection Log.
  Examples: "Driver marked DELIVERED but GPS shows 5km away".
*/
CREATE TABLE IF NOT EXISTS delivery_anomalies (
    anomaly_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id BIGINT NULL,
    driver_id BIGINT NULL,
    anomaly_type VARCHAR(64) NOT NULL,
    severity SMALLINT NOT NULL DEFAULT 1,
    detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    details JSON NULL,

    INDEX idx_anom_delivery (delivery_id),
    INDEX idx_anom_type_time (anomaly_type, detected_at)
) ENGINE=InnoDB;

/*
TABLE: delivery_metrics
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Post-Delivery Analytics. Calculated AFTER the order is done.
  Used to rank drivers (Efficiency Score).
*/
CREATE TABLE IF NOT EXISTS delivery_metrics (
    metric_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id BIGINT NOT NULL,
    efficiency_score DECIMAL(5,2) DEFAULT 0.00,
    route_efficiency_pct DECIMAL(5,2) DEFAULT 0.00,
    straight_line_distance_m INT DEFAULT 0,
    computed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_metrics_delivery (delivery_id)
) ENGINE=InnoDB;

-- ============================================================================================
-- 9. CACHE TABLES
-- ============================================================================================

/*
TABLE: driver_status_cache
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Read-optimized snapshot of driver location.
  Populated by the Fleet Service / Redis Heartbeats.
  Allows the Delivery Service to find "Nearest Drivers" without joining huge tables.
*/
CREATE TABLE IF NOT EXISTS driver_status_cache (
    driver_id BIGINT PRIMARY KEY,
    is_online BOOLEAN DEFAULT FALSE,
    last_heartbeat TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    current_zone_id INT NULL,
    current_lat DECIMAL(10,8) NULL,
    current_lng DECIMAL(11,8) NULL,

    INDEX idx_dsc_zone (current_zone_id)
) ENGINE=InnoDB COMMENT='Snapshot only. Not source of truth.';

SET FOREIGN_KEY_CHECKS = 1;

--------------------------------------------------------------------------------
-- Operational notes (concise)
--------------------------------------------------------------------------------
/*
- Seed delivery_states before enabling traffic.
- Partition delivery_location_updates and delivery_state_history aggressively; archive older partitions to cold storage.
- Shard writes by region_id/zone_id or driver_id hash if single-node DB cannot sustain peak TPS.
- Use outbox pattern (delivery_events_outbox) to publish events to message brokers; consumers must be idempotent.
- Maintain driver_current_assignment atomically (use DB transactions or distributed locks).
- For claiming work, use SELECT ... FOR UPDATE SKIP LOCKED to avoid races.
- Use Redis or in-memory caches for hot driver availability queries; persist authoritative state in DB.
- Monitor slow-query log and index usage; add indexes only after testing to avoid write amplification.
- Implement retention policies and automated partition maintenance scripts.
*/

SET FOREIGN_KEY_CHECKS = 1;
