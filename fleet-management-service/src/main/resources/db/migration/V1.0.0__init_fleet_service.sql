-- ============================================================================================
-- FLYWAY MIGRATION: FLEET SERVICE SCHEMA (50M+ USERS SCALE)
-- FILE: V1.0.0__init_fleet_service.sql
-- DATABASE: MySQL 8.0+ (InnoDB)
-- VALIDATION STATUS: INTEGRATION READY ✅
--
-- ROLE OF FLEET SERVICE:
--   - Source of truth for drivers, vehicles, fleets (3PL agencies)
--   - Compliance: documents, expiries, suspensions
--   - Fraud surface: devices, risk tracking
--   - Labor tracking: shifts, online time, incentives
--
-- CHANGELOG:
--   - CHANGED IDs to CHAR(36) (UUID) to match User/Order/Payment services.
--   - UPDATED Partition ranges to 2025/2026.
--   - ADDED CHECK constraints for strict status validation.
-- ============================================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================================
-- 1. FLEET AGENCIES (3PL LAYER)
-- ============================================================================================

/*
TABLE: fleets
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Represents 3rd-party logistics (3PL) agencies. In many markets, drivers are not independent
  contractors but employees of a Fleet Agency.
  
DESIGN:
  - `payout_config` stores the commercial terms (JSON) for how the agency is paid.
  - Acts as a tenant root for drivers and vehicles belonging to this agency.
*/
CREATE TABLE IF NOT EXISTS fleets (
    fleet_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    name VARCHAR(150) NOT NULL,
    legal_name VARCHAR(200),
    tax_id VARCHAR(50),
    contact_email VARCHAR(150),
    contact_phone VARCHAR(20),
    payout_config JSON,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_fleets_name (name),
    INDEX idx_fleets_tax_id (tax_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 2. DRIVERS (WORKFORCE IDENTITY)
-- ============================================================================================

/*
TABLE: drivers
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The core identity of the worker.
  - `fleet_id` links them to an agency (NULL if Independent/Freelance).
  - `home_zone_id` helps in supply planning (where does this driver usually work?).

STATE MACHINE:
  - ONBOARDING -> ACTIVE -> SUSPENDED -> BANNED.
  - Enforced via CHECK constraints to prevents invalid states.
*/
CREATE TABLE IF NOT EXISTS drivers (
    driver_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    fleet_id CHAR(36) NULL,                     -- NULL for independent drivers
    external_ref VARCHAR(100) NULL,             -- ID from external HRMS systems
    full_name VARCHAR(150) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    email VARCHAR(150) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ONBOARDING',
    rating_avg DECIMAL(3,2) DEFAULT 5.00,
    rating_count INT DEFAULT 0,
    total_trips INT DEFAULT 0,
    home_lat DECIMAL(10,8) NULL,
    home_lng DECIMAL(11,8) NULL,
    home_zone_id INT NULL,
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_drivers_fleet FOREIGN KEY (fleet_id) REFERENCES fleets(fleet_id),
    CONSTRAINT chk_driver_status CHECK (status IN ('ONBOARDING', 'ACTIVE', 'SUSPENDED', 'BANNED', 'OFFBOARDED')),

    UNIQUE KEY uq_drivers_phone (phone),
    INDEX idx_drivers_status (status),
    INDEX idx_drivers_fleet (fleet_id),
    INDEX idx_drivers_home_zone (home_zone_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 3. VEHICLES (ASSETS)
-- ============================================================================================

/*
TABLE: vehicles
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Registry of physical assets.
  - Payouts differ by vehicle type (Cars get paid more than Bikes).
  - Compliance tracks registration expiry independently of the driver.
*/
CREATE TABLE IF NOT EXISTS vehicles (
    vehicle_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    fleet_id CHAR(36) NULL,
    plate_number VARCHAR(20) NOT NULL,
    vehicle_type VARCHAR(30) NOT NULL,    -- BIKE, EV_SCOOTER, CAR
    brand VARCHAR(50) NULL,
    model VARCHAR(50) NULL,
    color VARCHAR(30) NULL,
    registration_number VARCHAR(50) NULL,
    registration_expiry DATE NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_vehicles_fleet FOREIGN KEY (fleet_id) REFERENCES fleets(fleet_id),
    CONSTRAINT chk_vehicle_type CHECK (vehicle_type IN ('BIKE', 'EV_SCOOTER', 'CAR', 'BICYCLE')),

    UNIQUE KEY uq_vehicles_plate (plate_number),
    INDEX idx_vehicles_type (vehicle_type),
    INDEX idx_vehicles_reg_expiry (registration_expiry)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 4. DRIVER–VEHICLE ASSIGNMENTS
-- ============================================================================================

/*
TABLE: driver_vehicle_assignments
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Temporal mapping of "Who drove what".
  - Critical for accident liability (Who was driving Bike X at 2 PM?).
  - Supports "Shift-based" vehicle sharing in EV fleets.
*/
CREATE TABLE IF NOT EXISTS driver_vehicle_assignments (
    assignment_id BIGINT PRIMARY KEY AUTO_INCREMENT, -- Internal ID, fine as BIGINT
    driver_id CHAR(36) NOT NULL,
    vehicle_id CHAR(36) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    unassigned_at TIMESTAMP NULL,

    CONSTRAINT fk_dva_driver FOREIGN KEY (driver_id) REFERENCES drivers(driver_id),
    CONSTRAINT fk_dva_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(vehicle_id),

    INDEX idx_dva_driver_active (driver_id, is_active),
    INDEX idx_dva_vehicle_active (vehicle_id, is_active),
    INDEX idx_dva_assigned_at (assigned_at)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 5. DRIVER DOCUMENTS (COMPLIANCE)
-- ============================================================================================

/*
TABLE: driver_documents
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The "Gatekeeper". Drivers cannot go online if critical documents (License, Insurance) 
  are expired or rejected.
  - Background jobs scan `expiry_date` daily to auto-suspend drivers.
*/
CREATE TABLE IF NOT EXISTS driver_documents (
    document_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    driver_id CHAR(36) NOT NULL,
    document_type VARCHAR(40) NOT NULL,
    document_number VARCHAR(100) NULL,
    file_url VARCHAR(500) NOT NULL,
    issued_country VARCHAR(50) NULL,
    expiry_date DATE NULL,
    verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verified_at TIMESTAMP NULL,
    rejected_reason VARCHAR(255) NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_dd_driver FOREIGN KEY (driver_id) REFERENCES drivers(driver_id),
    CONSTRAINT chk_doc_status CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED')),

    INDEX idx_dd_driver (driver_id),
    INDEX idx_dd_type (document_type),
    INDEX idx_dd_status (verification_status),
    INDEX idx_dd_expiry (expiry_date)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 6. DRIVER DEVICES (FRAUD)
-- ============================================================================================

/*
TABLE: driver_devices
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Fraud Prevention.
  - Prevents "Device Farming" (One person running 5 accounts on 5 phones).
  - Prevents Account Sharing (One account logged in on 5 phones).
*/
CREATE TABLE IF NOT EXISTS driver_devices (
    device_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    driver_id CHAR(36) NOT NULL,
    device_fingerprint VARCHAR(255) NOT NULL,
    platform VARCHAR(20) NULL,
    app_version VARCHAR(30) NULL,
    first_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_blocked BOOLEAN DEFAULT FALSE,
    block_reason VARCHAR(255) NULL,
    risk_score DECIMAL(5,2) DEFAULT 0.00,

    CONSTRAINT fk_dev_driver FOREIGN KEY (driver_id) REFERENCES drivers(driver_id),

    INDEX idx_dev_driver (driver_id),
    INDEX idx_dev_fingerprint (device_fingerprint),
    INDEX idx_dev_blocked (is_blocked)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 7. SERVICE ZONES (GEOGRAPHIC)
-- ============================================================================================

/*
TABLE: service_zones
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Defines operational boundaries (Geofences).
  - Used by Delivery Service to check if a drop location is served.
  - Used by Fleet Service to track where a driver is "logged in".
  - `polygon_geojson` allows complex shapes, handled by app logic or GIS libraries.
*/
CREATE TABLE IF NOT EXISTS service_zones (
    zone_id INT PRIMARY KEY AUTO_INCREMENT,
    zone_name VARCHAR(150) NOT NULL,
    city VARCHAR(100) NOT NULL,
    country VARCHAR(100) DEFAULT 'India',
    polygon_geojson TEXT NULL,        
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_zones_city (city),
    INDEX idx_zones_active (is_active)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 8. SHIFTS (LABOR TRACKING - PARTITIONED)
-- ============================================================================================

/*
TABLE: shifts
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Tracks "Online Hours".
  - Essential for labor law compliance (Maximum working hours).
  - Used for "Minimum Guarantee" incentive calculations (e.g., "Online for 10h = $50 guaranteed").

PARTITIONING:
  - Range Partitioned by `started_at` (Monthly).
  - High volume: Every driver creates at least 1-2 shift records per day.
*/
CREATE TABLE IF NOT EXISTS shifts (
    shift_id BIGINT NOT NULL AUTO_INCREMENT,
    driver_id CHAR(36) NOT NULL,
    zone_id INT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP NULL,
    total_seconds INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (shift_id, started_at),
    CONSTRAINT chk_shift_status CHECK (status IN ('OPEN', 'CLOSED', 'FORCED_CLOSED')),

    INDEX idx_shifts_driver_started (driver_id, started_at),
    INDEX idx_shifts_zone_started (zone_id, started_at),
    INDEX idx_shifts_status (status)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(started_at) (
    PARTITION p_2025_01 VALUES LESS THAN ('2025-02-01 00:00:00'),
    PARTITION p_2025_02 VALUES LESS THAN ('2025-03-01 00:00:00'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- ============================================================================================
-- 9. DRIVER SUSPENSIONS
-- ============================================================================================

/*
TABLE: driver_suspensions
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Audit trail for enforcement actions.
  - Keeps history of bans/suspensions for legal defense.
  - `is_active` flag determines if the driver can currently log in.
*/
CREATE TABLE IF NOT EXISTS driver_suspensions (
    suspension_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    driver_id CHAR(36) NOT NULL,
    source VARCHAR(20) NOT NULL,        -- SYSTEM, MANUAL, COMPLIANCE
    reason_code VARCHAR(50) NOT NULL,
    reason_text VARCHAR(255) NULL,
    is_active BOOLEAN DEFAULT TRUE,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_susp_driver FOREIGN KEY (driver_id) REFERENCES drivers(driver_id),

    INDEX idx_susp_driver_active (driver_id, is_active),
    INDEX idx_susp_started (started_at)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =========================================================================================
-- 10. DRIVER INCENTIVES
-- =========================================================================================

/*
TABLE: driver_incentives
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Stores non-delivery earnings (Bonuses).
  - Examples: "Weekly Quest", "Rainy Day Bonus", "Referral Bonus".
  - These are aggregated by the Payment Service for the final payout batch.
*/
CREATE TABLE driver_incentives (
    incentive_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    driver_id CHAR(36) NOT NULL,
    incentive_type VARCHAR(50) NOT NULL, 
    amount BIGINT NOT NULL,              
    calculated_for_date DATE NOT NULL,   
    delivery_count INT DEFAULT 0,
    contributed_shift_hours INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (driver_id) REFERENCES drivers(driver_id),

    INDEX idx_incent_driver (driver_id),
    INDEX idx_incent_type (incentive_type),
    INDEX idx_incent_calc_date (calculated_for_date)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 11. DRIVER RATINGS HISTORY
-- ============================================================================================

/*
TABLE: driver_ratings_history
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Time-series of performance.
  - Used to calculate the rolling average rating.
  - Helps detect sudden drops in performance (e.g., a driver suddenly getting 1-star reviews).
*/
CREATE TABLE IF NOT EXISTS driver_ratings_history (
    rating_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    driver_id CHAR(36) NOT NULL,
    rating TINYINT NOT NULL,           
    rating_source VARCHAR(20) NOT NULL, 
    context JSON NULL,                 
    rated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_rh_driver FOREIGN KEY (driver_id) REFERENCES drivers(driver_id),

    INDEX idx_rh_driver_time (driver_id, rated_at),
    INDEX idx_rh_rating (rating)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================================================
-- 12. DRIVER PAYOUT SETTINGS
-- ============================================================================================

/*
TABLE: driver_payout_settings
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Stores banking/financial routing info.
  - The Payment Service reads this to know *where* to send the money.
  - Tokenized account numbers for security (PCI/PII compliance).
*/
CREATE TABLE IF NOT EXISTS driver_payout_settings (
    driver_id CHAR(36) PRIMARY KEY,
    payout_type VARCHAR(20) NOT NULL,     
    account_holder_name VARCHAR(150) NULL,
    account_number_token VARCHAR(255) NULL, 
    ifsc_code VARCHAR(20) NULL,
    upi_vpa VARCHAR(100) NULL,
    is_verified BOOLEAN DEFAULT FALSE,
    verified_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_payout_driver FOREIGN KEY (driver_id) REFERENCES drivers(driver_id),
    CONSTRAINT chk_payout_type CHECK (payout_type IN ('BANK', 'UPI', 'WALLET')),

    INDEX idx_payout_type (payout_type),
    INDEX idx_payout_verified (is_verified)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =========================================================================================
-- 13. DRIVER LOCATION HISTORY (PARTITIONED)
-- =========================================================================================

/*
TABLE: driver_location_history
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Downsampled Breadcrumbs (Not Real-Time).
  - Used for historical route replay, distance verification, and fraud analysis.
  - REAL-TIME location for dispatch lives in Redis, NOT here.
  
PARTITIONING:
  - Range Partitioned by `recorded_at` (Monthly).
  - This is the highest volume table in the service (Millions of rows per day).
*/
CREATE TABLE IF NOT EXISTS driver_location_history (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    driver_id CHAR(36) NOT NULL,
    lat DECIMAL(10,8) NOT NULL,
    lng DECIMAL(11,8) NOT NULL,
    accuracy_m INT NULL,
    speed_kmph DECIMAL(6,2) NULL,
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (event_id, recorded_at),

    INDEX idx_dlh_driver_time (driver_id, recorded_at)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(recorded_at) (
    PARTITION p_2025_01 VALUES LESS THAN ('2025-02-01 00:00:00'),
    PARTITION p_2025_02 VALUES LESS THAN ('2025-03-01 00:00:00'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- =========================================================================================
-- 14. DRIVER EARNINGS (PARTITIONED)
-- =========================================================================================

/*
TABLE: driver_earnings
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The Financial Source of Truth for every delivery.
  - Immutable record of what a driver earned for a specific job.
  - Aggregated by Payment Service for payouts.
  
PARTITIONING:
  - Range Partitioned by `created_at` (Monthly).
*/
CREATE TABLE IF NOT EXISTS driver_earnings (
    earning_id BIGINT NOT NULL AUTO_INCREMENT,
    driver_id CHAR(36) NOT NULL,
    delivery_id BIGINT NOT NULL, -- Kept as BIGINT to match Delivery Service ID
    distance_km DECIMAL(6,2) NOT NULL,
    base_amount BIGINT NOT NULL,
    peak_bonus BIGINT DEFAULT 0,
    wait_time_bonus BIGINT DEFAULT 0,
    total_amount BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (earning_id, created_at),

    INDEX idx_de_driver (driver_id),
    INDEX idx_de_delivery (delivery_id),
    INDEX idx_de_created (created_at)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS(created_at) (
    PARTITION p_2025_01 VALUES LESS THAN ('2025-02-01 00:00:00'),
    PARTITION p_2025_02 VALUES LESS THAN ('2025-03-01 00:00:00'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

-- =========================================================================================
-- 15. FLEET PAYOUT RULES
-- =========================================================================================

/*
TABLE: fleet_payout_rules
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Commercial Contracting Engine.
  - Defines the "Cut" the Fleet Agency takes vs. the Driver.
  - Effective Dates (`effective_from/to`) allow contract changes without breaking history.
*/
CREATE TABLE fleet_payout_rules (
    rule_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    fleet_id CHAR(36) NOT NULL,
    base_commission_pct DECIMAL(5,2) NOT NULL,
    distance_payout_per_km INT NOT NULL,       
    incentive_share_pct DECIMAL(5,2) DEFAULT 100, 
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (fleet_id) REFERENCES fleets(fleet_id),
    INDEX idx_payout_rules_fleet (fleet_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;