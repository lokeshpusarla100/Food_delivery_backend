-- ============================================================================================
-- FLYWAY MIGRATION: CATALOG SERVICE SCHEMA (PRODUCTION-READY)
-- FILE: V1.0.0__init_catalog_service_tables.sql
-- DATABASE: MySQL 8.0+ (InnoDB)
-- VALIDATION STATUS: PRODUCTION READY ✅
--
-- ROLE OF CATALOG SERVICE:
--   - Manages Brands, Outlets (Restaurants), Categories, Items, and Modifiers.
--   - Source of Truth for Menu definitions.
--   - Syncs data to Analytics and Search Service (Elasticsearch).
--
-- ARCHITECTURE NOTE - READ HEAVY:
--   - Unlike Order/Payment services, Catalog is 99% READ / 1% WRITE.
--   - Queries are almost always "Get Menu for Outlet X" (Key-based lookup), not time-based.
--   - Scaling Strategy:
--       1. Aggressive Caching (Redis/CDN) for Menu GETs.
--       2. Database Sharding by `brand_id` or `city` if table size exceeds 100GB.
--       3. Hash Partitioning on `outlet_item_availability` if inventory updates cause contention.
--
-- CHANGELOG (Production Updates):
--   - ADDED price_history table for audit trail and analytics
--   - ADDED outlet_operating_hours table for business logic
--   - ADDED composite index on outlets(is_active, city) for common queries
-- ============================================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================================
-- GROUP A: IDENTITY & LOCATION TABLES
-- ============================================================================================

CREATE TABLE IF NOT EXISTS brands (
    brand_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    name VARCHAR(100) NOT NULL,
    logo_url VARCHAR(255),
    corporate_phone VARCHAR(20),
    cuisine_type VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_brands_name UNIQUE (name)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_brands_name ON brands(name);

CREATE TABLE IF NOT EXISTS outlets (
    outlet_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    brand_id CHAR(36) NOT NULL,

    -- Structured address fields (normalized)
    street VARCHAR(255),
    locality VARCHAR(255),
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100),
    postal_code VARCHAR(20),
    country VARCHAR(100) DEFAULT 'India',

    location_name VARCHAR(100),

    -- Canonical geospatial column (Required NOT NULL for Spatial Index)
    location_point POINT NOT NULL SRID 4326,

    -- Generated lat/lng for compatibility
    latitude DECIMAL(10,8) AS (ST_Y(location_point)) VIRTUAL,
    longitude DECIMAL(11,8) AS (ST_X(location_point)) VIRTUAL,

    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_outlets_brand FOREIGN KEY (brand_id) REFERENCES brands(brand_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE SPATIAL INDEX spidx_outlets_location ON outlets (location_point);
CREATE INDEX idx_outlets_brand_id ON outlets (brand_id);
CREATE INDEX idx_outlets_city ON outlets (city);
CREATE INDEX idx_outlets_postal_code ON outlets (postal_code);

-- PRODUCTION FIX: Composite index for common "active restaurants in city" queries
CREATE INDEX idx_outlets_active_city ON outlets (is_active, city);

-- ============================================================================================
-- NEW: OUTLET OPERATING HOURS
-- ============================================================================================

CREATE TABLE IF NOT EXISTS outlet_operating_hours (
    hours_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id CHAR(36) NOT NULL,

    day_of_week TINYINT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6), -- 0=Sunday, 6=Saturday
    opens_at TIME NOT NULL,
    closes_at TIME NOT NULL,

    is_closed BOOLEAN DEFAULT FALSE, -- For holidays/special closures
    effective_from DATE NULL, -- For temporary schedule changes
    effective_until DATE NULL,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_ooh_outlet FOREIGN KEY (outlet_id) REFERENCES outlets(outlet_id) ON DELETE CASCADE,
    CONSTRAINT uq_outlet_day UNIQUE (outlet_id, day_of_week, effective_from)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_ooh_outlet_day ON outlet_operating_hours (outlet_id, day_of_week);

-- ============================================================================================
-- GROUP B: MENU STRUCTURE TABLES
-- ============================================================================================

CREATE TABLE IF NOT EXISTS menu_categories (
    category_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    brand_id CHAR(36) NOT NULL,
    name VARCHAR(50) NOT NULL,
    display_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_menu_categories_brand FOREIGN KEY (brand_id) REFERENCES brands(brand_id),
    CONSTRAINT uq_menu_categories_brand_name UNIQUE (brand_id, name)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_menu_categories_brand ON menu_categories (brand_id);

CREATE TABLE IF NOT EXISTS menu_items (
    item_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    category_id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    base_price DECIMAL(10,2) NOT NULL,
    image_url VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_menu_items_category FOREIGN KEY (category_id) REFERENCES menu_categories(category_id),
    CONSTRAINT uq_menu_items_category_name UNIQUE (category_id, name)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_menu_items_category_id ON menu_items (category_id);
CREATE INDEX idx_menu_items_name ON menu_items (name);

-- ============================================================================================
-- NEW: PRICE HISTORY (Audit Trail & Analytics)
-- ============================================================================================

CREATE TABLE IF NOT EXISTS menu_item_price_history (
    history_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    item_id CHAR(36) NOT NULL,

    old_price DECIMAL(10,2) NOT NULL,
    new_price DECIMAL(10,2) NOT NULL,

    changed_by VARCHAR(100) NULL, -- User/Admin who made the change
    change_reason VARCHAR(255) NULL, -- "Seasonal adjustment", "Cost increase", etc.

    effective_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_price_history_item FOREIGN KEY (item_id) REFERENCES menu_items(item_id) ON DELETE CASCADE
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_price_history_item_date ON menu_item_price_history (item_id, effective_from DESC);

-- ============================================================================================
-- GROUP C: MODIFIERS & ADD-ONS
-- ============================================================================================

CREATE TABLE IF NOT EXISTS modifier_groups (
    group_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    brand_id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    min_selection INT DEFAULT 0,
    max_selection INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_modifier_groups_brand FOREIGN KEY (brand_id) REFERENCES brands(brand_id),
    CONSTRAINT uq_modifier_groups_brand_name UNIQUE (brand_id, name)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_modifier_groups_brand_id ON modifier_groups (brand_id);

CREATE TABLE IF NOT EXISTS modifiers (
    modifier_id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    group_id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    price_adjustment DECIMAL(10,2) DEFAULT 0.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_modifiers_group FOREIGN KEY (group_id) REFERENCES modifier_groups(group_id),
    CONSTRAINT uq_modifiers_group_name UNIQUE (group_id, name)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_modifiers_group_id ON modifiers (group_id);
CREATE INDEX idx_modifiers_name ON modifiers (name);

CREATE TABLE IF NOT EXISTS item_modifier_mappings (
    item_id CHAR(36) NOT NULL,
    group_id CHAR(36) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (item_id, group_id),
    CONSTRAINT fk_imm_item FOREIGN KEY (item_id) REFERENCES menu_items(item_id),
    CONSTRAINT fk_imm_group FOREIGN KEY (group_id) REFERENCES modifier_groups(group_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_imm_group_id ON item_modifier_mappings (group_id);

-- ============================================================================================
-- GROUP D: OVERRIDES & AVAILABILITY (Inventory Filter)
-- ============================================================================================

CREATE TABLE IF NOT EXISTS outlet_item_availability (
    outlet_id CHAR(36) NOT NULL,
    item_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL, -- AVAILABLE, OUT_OF_STOCK, DISABLED
    override_price DECIMAL(10,2) NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (outlet_id, item_id),
    CONSTRAINT fk_oia_outlet FOREIGN KEY (outlet_id) REFERENCES outlets(outlet_id),
    CONSTRAINT fk_oia_item FOREIGN KEY (item_id) REFERENCES menu_items(item_id),

    CONSTRAINT chk_item_status CHECK (status IN ('AVAILABLE', 'OUT_OF_STOCK', 'DISABLED'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_oia_item_id ON outlet_item_availability (item_id);
CREATE INDEX idx_oia_status ON outlet_item_availability (status);

CREATE TABLE IF NOT EXISTS outlet_modifier_availability (
    outlet_id CHAR(36) NOT NULL,
    modifier_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL, -- AVAILABLE, OUT_OF_STOCK
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (outlet_id, modifier_id),
    CONSTRAINT fk_oma_outlet FOREIGN KEY (outlet_id) REFERENCES outlets(outlet_id),
    CONSTRAINT fk_oma_modifier FOREIGN KEY (modifier_id) REFERENCES modifiers(modifier_id),

    CONSTRAINT chk_mod_status CHECK (status IN ('AVAILABLE', 'OUT_OF_STOCK'))
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE INDEX idx_oma_modifier_id ON outlet_modifier_availability (modifier_id);

SET FOREIGN_KEY_CHECKS = 1;