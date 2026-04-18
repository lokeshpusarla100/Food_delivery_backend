-- ============================================================================================
-- FLYWAY MIGRATION: ORDER SERVICE SCHEMA (STAGE 1 - STANDARD RELATIONAL)
-- FILE: V1.0.0__init_order_service.sql
-- DATABASE: MySQL 8.0+ (InnoDB)
-- STATUS: PRODUCTION READY (FK ENABLED, NO PARTITIONING) ✅
--
-- ARCHITECTURE DECISION:
--   - We are starting with a standard relational model with Foreign Keys enabled.
--   - Partitioning is removed to simplify development and allow strict data integrity.
--   - Tables are future-proofed with UUIDs and audit fields for later scaling.
-- ============================================================================================

SET FOREIGN_KEY_CHECKS = 0;
SET sql_mode = 'STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

-- ============================================================================================
-- 1. REFERENCE TABLES
-- ============================================================================================

CREATE TABLE IF NOT EXISTS order_statuses (
    status_id SMALLINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(30) NOT NULL,
    description VARCHAR(255),
    display_order TINYINT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    cache_ttl_seconds INT NOT NULL DEFAULT 3600,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uq_order_statuses_code UNIQUE (code),
    INDEX idx_order_statuses_active (is_active)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS order_adjustment_types (
    type_id SMALLINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uq_adj_types_code UNIQUE (code),
    INDEX idx_adj_types_active (is_active)
) ENGINE=InnoDB;

-- ============================================================================================
-- 2. MASTER ORDER TABLE
-- ============================================================================================

CREATE TABLE IF NOT EXISTS orders (
    order_seq_id BIGINT NOT NULL AUTO_INCREMENT,
    order_id CHAR(36) NOT NULL DEFAULT (UUID()), -- UUID
    order_number VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NULL,

    user_id CHAR(36) NOT NULL,
    outlet_id CHAR(36) NOT NULL,
    outlet_name_at_order VARCHAR(255) NOT NULL,
    outlet_city_id CHAR(36) NOT NULL,

    delivery_partner_id CHAR(36) NULL,
    driver_assigned_at DATETIME NULL,
    delivery_address_snapshot JSON NOT NULL,

    payment_transaction_id VARCHAR(100) NULL,
    payment_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',

    current_status_id SMALLINT NOT NULL, -- FK to order_statuses
    order_type VARCHAR(20) NOT NULL DEFAULT 'ASAP',
    scheduled_for DATETIME NULL,
    instructions TEXT NULL,
    metadata JSON NULL,

    items_total DECIMAL(13,2) NOT NULL,
    adjustments_total DECIMAL(13,2) NOT NULL DEFAULT 0.00,
    subtotal DECIMAL(13,2) NOT NULL,
    tax DECIMAL(13,2) NOT NULL DEFAULT 0.00,
    delivery_fee DECIMAL(13,2) NOT NULL DEFAULT 0.00,
    promo_code_applied VARCHAR(50) NULL,
    discount_amount DECIMAL(13,2) NOT NULL DEFAULT 0.00,
    total_amount DECIMAL(13,2) NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'INR',

    partner_earning_amount DECIMAL(13,2) NULL,

    promised_delivery_time DATETIME NULL,
    actual_delivery_time DATETIME NULL,
    sla_breached BOOLEAN NOT NULL DEFAULT FALSE,

    is_cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_at DATETIME NULL,
    cancellation_reason VARCHAR(255) NULL,

    user_rating TINYINT NULL CHECK (user_rating BETWEEN 1 AND 5),
    user_feedback TEXT NULL,
    rated_at DATETIME NULL,

    retry_count TINYINT NOT NULL DEFAULT 0,
    last_error_message VARCHAR(500) NULL,

    version INT NOT NULL DEFAULT 1,
    checksum CHAR(64) NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- STANDARD KEYS
    PRIMARY KEY (order_seq_id),
    UNIQUE KEY uq_order_id (order_id),
    UNIQUE KEY uq_order_number (order_number),
    UNIQUE KEY uq_orders_user_idempotency (user_id, idempotency_key),

    -- FOREIGN KEYS RESTORED
    CONSTRAINT fk_orders_status FOREIGN KEY (current_status_id) REFERENCES order_statuses(status_id),

    CHECK (order_type IN ('ASAP', 'SCHEDULED')),
    CHECK (payment_status IN ('PENDING', 'CAPTURED', 'FAILED', 'REFUNDED')),

    INDEX idx_orders_user_created (user_id, created_at DESC),
    INDEX idx_orders_outlet_status (outlet_id, current_status_id, created_at DESC),
    INDEX idx_orders_payment_status (payment_status, created_at DESC)
) ENGINE=InnoDB;

-- ============================================================================================
-- 3. ORDER ITEMS
-- ============================================================================================

CREATE TABLE IF NOT EXISTS order_items (
    item_seq_id BIGINT NOT NULL AUTO_INCREMENT,
    order_item_id CHAR(36) NOT NULL DEFAULT (UUID()),
    order_id CHAR(36) NOT NULL, -- Logical link to orders via UUID
    order_seq_ref BIGINT NOT NULL, -- Physical FK to orders(order_seq_id)
    line_number INT NOT NULL DEFAULT 1,

    catalog_item_id CHAR(36) NULL,
    item_name VARCHAR(255) NOT NULL,
    snapshot_base_price DECIMAL(13,2) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    items_line_total DECIMAL(13,2) NOT NULL,

    instructions VARCHAR(255) NULL,
    metadata JSON NULL,

    prep_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    kds_ticket_id VARCHAR(50) NULL,
    started_preparing_at DATETIME NULL,
    finished_preparing_at DATETIME NULL,
    is_completed BOOLEAN NOT NULL DEFAULT FALSE,
    cancellation_reason VARCHAR(255) NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (item_seq_id),
    UNIQUE KEY uq_order_item_id (order_item_id),

    -- FOREIGN KEY RESTORED
    CONSTRAINT fk_items_order FOREIGN KEY (order_seq_ref) REFERENCES orders(order_seq_id) ON DELETE CASCADE,

    CHECK (prep_status IN ('NOT_STARTED', 'PREPARING', 'READY', 'CANCELLED')),

    INDEX idx_order_items_order (order_id, line_number),
    INDEX idx_order_items_prep_status (order_id, prep_status)
) ENGINE=InnoDB;

-- ============================================================================================
-- 4. ORDER ITEM MODIFIERS
-- ============================================================================================

CREATE TABLE IF NOT EXISTS order_item_modifiers (
    modifier_seq_id BIGINT NOT NULL AUTO_INCREMENT,
    modifier_detail_id CHAR(36) NOT NULL DEFAULT (UUID()),
    item_seq_ref BIGINT NOT NULL, -- Physical FK to order_items

    catalog_modifier_id CHAR(36) NULL,
    modifier_name VARCHAR(255) NOT NULL,
    snapshot_price_adjustment DECIMAL(13,2) NOT NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (modifier_seq_id),
    UNIQUE KEY uq_modifier_id (modifier_detail_id),

    -- FOREIGN KEY RESTORED
    CONSTRAINT fk_modifiers_item FOREIGN KEY (item_seq_ref) REFERENCES order_items(item_seq_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ============================================================================================
-- 5. ORDER ADJUSTMENTS
-- ============================================================================================

CREATE TABLE IF NOT EXISTS order_adjustments (
    adjustment_seq_id BIGINT NOT NULL AUTO_INCREMENT,
    adjustment_id CHAR(36) NOT NULL DEFAULT (UUID()),
    order_seq_ref BIGINT NOT NULL, -- Physical FK to orders

    type_id SMALLINT NOT NULL,
    type_code VARCHAR(50) NOT NULL,
    reason_code VARCHAR(100) NULL,

    amount DECIMAL(13,2) NOT NULL,
    description VARCHAR(512) NULL,
    created_by VARCHAR(100) NULL,
    metadata JSON NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (adjustment_seq_id),
    UNIQUE KEY uq_adjustment_id (adjustment_id),

    -- FOREIGN KEYS RESTORED
    CONSTRAINT fk_adjustments_order FOREIGN KEY (order_seq_ref) REFERENCES orders(order_seq_id) ON DELETE CASCADE,
    CONSTRAINT fk_adjustments_type FOREIGN KEY (type_id) REFERENCES order_adjustment_types(type_id),

    INDEX idx_adj_type_date (type_code, created_at DESC)
) ENGINE=InnoDB;

-- ============================================================================================
-- 6. ORDER STATUS HISTORY
-- ============================================================================================

CREATE TABLE IF NOT EXISTS order_status_history (
    history_seq_id BIGINT NOT NULL AUTO_INCREMENT,
    history_id CHAR(36) NOT NULL DEFAULT (UUID()),
    order_seq_ref BIGINT NOT NULL, -- Physical FK to orders

    from_status_id SMALLINT NULL,
    to_status_id SMALLINT NOT NULL,

    timestamp_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    initiated_by VARCHAR(100) NULL,
    initiated_by_type VARCHAR(20) NULL,
    event_reason VARCHAR(512) NULL,
    order_version_at INT NULL,
    metadata JSON NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (history_seq_id),
    UNIQUE KEY uq_history_id (history_id),

    -- FOREIGN KEYS RESTORED
    CONSTRAINT fk_history_order FOREIGN KEY (order_seq_ref) REFERENCES orders(order_seq_id) ON DELETE CASCADE,
    CONSTRAINT fk_history_from FOREIGN KEY (from_status_id) REFERENCES order_statuses(status_id),
    CONSTRAINT fk_history_to FOREIGN KEY (to_status_id) REFERENCES order_statuses(status_id),

    INDEX idx_osh_order_time (order_seq_ref, timestamp_at DESC)
) ENGINE=InnoDB;

-- ============================================================================================
-- 7. ORDER CANCELLATIONS
-- ============================================================================================

CREATE TABLE IF NOT EXISTS order_cancellations (
    cancellation_seq_id BIGINT NOT NULL AUTO_INCREMENT,
    cancellation_id CHAR(36) NOT NULL DEFAULT (UUID()),
    order_seq_ref BIGINT NOT NULL, -- Physical FK to orders

    cancelled_by_role VARCHAR(20) NOT NULL,
    cancelled_by_id CHAR(36) NULL,
    reason_code VARCHAR(100) NOT NULL,
    reason_details TEXT NULL,

    refund_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    refund_amount DECIMAL(13,2) NULL,
    refund_initiated_at DATETIME NULL,
    refund_completed_at DATETIME NULL,
    refund_txn_id VARCHAR(100) NULL,
    refund_error_message VARCHAR(500) NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (cancellation_seq_id),
    UNIQUE KEY uq_cancellation_id (cancellation_id),

    -- FOREIGN KEY RESTORED
    CONSTRAINT fk_cancellations_order FOREIGN KEY (order_seq_ref) REFERENCES orders(order_seq_id) ON DELETE CASCADE,

    CHECK (refund_status IN ('PENDING', 'INITIATED', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB;

-- ============================================================================================
-- 8. ORDER DELIVERY METRICS
-- ============================================================================================

CREATE TABLE IF NOT EXISTS order_delivery_metrics (
    metric_seq_id BIGINT NOT NULL AUTO_INCREMENT,
    order_seq_ref BIGINT NOT NULL, -- Physical FK to orders

    pickup_time DATETIME NULL,
    delivery_time DATETIME NULL,
    promised_delivery_time DATETIME NULL,
    actual_delivery_time DATETIME NULL,

    sla_breached BOOLEAN NOT NULL DEFAULT FALSE,
    sla_breach_minutes INT NULL,
    distance_meters FLOAT NULL,
    duration_seconds INT NULL,
    avg_speed_kmph FLOAT NULL,

    customer_rating TINYINT NULL CHECK (customer_rating BETWEEN 1 AND 5),
    rating_comment TEXT NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (metric_seq_id),
    UNIQUE KEY uq_metric_order (order_seq_ref),

    -- FOREIGN KEY RESTORED
    CONSTRAINT fk_metrics_order FOREIGN KEY (order_seq_ref) REFERENCES orders(order_seq_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ============================================================================================
-- 9. OUTBOX EVENTS TABLE
-- ============================================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    event_seq_id BIGINT NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) NOT NULL DEFAULT (UUID()),

    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_payload JSON NOT NULL,

    publish_destination VARCHAR(50) NOT NULL,
    kafka_partition_key CHAR(36) NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_retry_at DATETIME NULL,
    last_published_at DATETIME NULL,
    last_error_message VARCHAR(1000) NULL,
    error_code VARCHAR(50) NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    published_at DATETIME NULL,

    PRIMARY KEY (event_seq_id),
    UNIQUE KEY uq_event_id (event_id),

    CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DISCARDED')),

    INDEX idx_outbox_pending (status, next_retry_at)
) ENGINE=InnoDB;

-- ============================================================================================
-- 10. INBOX EVENTS
-- ============================================================================================

CREATE TABLE IF NOT EXISTS order_event_inbox (
    inbox_seq_id BIGINT NOT NULL AUTO_INCREMENT,
    inbox_id CHAR(36) NOT NULL DEFAULT (UUID()),

    event_id VARCHAR(100) NOT NULL,
    source_service VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,

    last_attempted_at DATETIME NULL,
    processed_at DATETIME NULL,
    last_error_message VARCHAR(1000) NULL,
    error_code VARCHAR(50) NULL,
    related_order_id CHAR(36) NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (inbox_seq_id),
    UNIQUE KEY uq_inbox_event (event_id, source_service),

    CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED', 'DISCARDED')),

    INDEX idx_inbox_pending (status, created_at)
) ENGINE=InnoDB;

-- ============================================================================================
-- 11. ORDER SUMMARY (MATERIALIZED VIEW)
-- ============================================================================================

CREATE TABLE IF NOT EXISTS order_summary (
    order_id CHAR(36) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    restaurant_id CHAR(36) NOT NULL,

    current_status_id SMALLINT NOT NULL,
    current_status_code VARCHAR(30) NOT NULL,

    total_amount DECIMAL(13,2) NOT NULL,
    tax_amount DECIMAL(13,2) NOT NULL,
    discount_amount DECIMAL(13,2) NOT NULL,
    delivery_fee DECIMAL(13,2) NOT NULL,

    created_at DATETIME NOT NULL,
    paid_at DATETIME NULL,
    delivered_at DATETIME NULL,

    is_cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    is_sla_breached BOOLEAN NOT NULL DEFAULT FALSE,
    customer_rating TINYINT NULL,
    item_count INT NOT NULL,

    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_summary_user (user_id, created_at DESC),
    INDEX idx_summary_rest (restaurant_id, created_at DESC)
) ENGINE=InnoDB;

-- ============================================================================================
-- 12. TRIGGERS
-- ============================================================================================

DELIMITER $$

CREATE TRIGGER tr_orders_after_insert AFTER INSERT ON orders
FOR EACH ROW
BEGIN
    INSERT INTO order_summary (
        order_id, user_id, restaurant_id, current_status_id, current_status_code,
        total_amount, tax_amount, discount_amount, delivery_fee,
        created_at, is_cancelled, is_sla_breached, item_count
    )
    VALUES (
        NEW.order_id, NEW.user_id, NEW.outlet_id, NEW.current_status_id,
        (SELECT COALESCE(code, 'UNKNOWN') FROM order_statuses WHERE status_id = NEW.current_status_id LIMIT 1),
        NEW.total_amount, NEW.tax, NEW.discount_amount, NEW.delivery_fee,
        NEW.created_at, NEW.is_cancelled, NEW.sla_breached, 0
    )
    ON DUPLICATE KEY UPDATE updated_at = NOW();
END$$

CREATE TRIGGER tr_orders_after_update AFTER UPDATE ON orders
FOR EACH ROW
BEGIN
    UPDATE order_summary
    SET
        current_status_id = NEW.current_status_id,
        current_status_code = (SELECT COALESCE(code, 'UNKNOWN') FROM order_statuses WHERE status_id = NEW.current_status_id LIMIT 1),
        total_amount = NEW.total_amount,
        is_cancelled = NEW.is_cancelled,
        paid_at = IF(NEW.payment_status = 'CAPTURED', NEW.updated_at, paid_at),
        updated_at = NOW()
    WHERE order_id = NEW.order_id;
END$$

DELIMITER ;

-- ============================================================================================
-- 13. SEED DATA
-- ============================================================================================

INSERT INTO order_statuses (code, description, display_order) VALUES
('CREATED', 'Order placed, awaiting payment', 1),
('PAYMENT_PENDING', 'Payment processing', 2),
('PAID', 'Payment confirmed', 3),
('ACCEPTED', 'Restaurant accepted order', 4),
('PREPARING', 'Order being prepared', 5),
('READY_FOR_PICKUP', 'Ready at outlet', 6),
('DELIVERY_ASSIGNED', 'Delivery partner assigned', 7),
('OUT_FOR_DELIVERY', 'In transit to customer', 8),
('DELIVERED', 'Successfully delivered', 9),
('CANCELLED', 'Order cancelled', 10),
('DELIVERY_FAILED', 'Delivery attempt failed', 11)
ON DUPLICATE KEY UPDATE description=VALUES(description);

INSERT INTO order_adjustment_types (code, description) VALUES
('DISCOUNT', 'Applied discount or promotional credit'),
('PROMO_CODE', 'Promo/coupon code applied'),
('DELIVERY_FEE', 'Delivery charge'),
('TAX', 'Tax (GST, VAT, etc.)'),
('RESTAURANT_COURTESY', 'Courtesy credit from restaurant'),
('SUPPORT_ADJUSTMENT', 'Support team adjustment'),
('REFUND', 'Refund adjustment'),
('PLATFORM_FEE', 'Platform service fee')
ON DUPLICATE KEY UPDATE description=VALUES(description);

SET FOREIGN_KEY_CHECKS = 1;