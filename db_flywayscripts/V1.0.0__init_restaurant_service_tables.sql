-- ============================================================================================
-- FLYWAY MIGRATION: CATALOG SERVICE SCHEMA (50M+ USERS SCALE)
-- FILE: V1.0.0__init_catalog_service_tables.sql
-- DATABASE: MySQL 8.0+ (InnoDB)
-- VALIDATION STATUS: INTEGRATION READY ✅
--
-- ROLE OF CATALOG SERVICE:
--   - Manages Brands, Outlets (Restaurants), Categories, Items, and Modifiers.
--   - Source of Truth for Menu definitions.
--   - Syncs data to Analytics and Search Service (Elasticsearch).
--
-- ARCHITECTURE NOTE - READ HEAVY:
--   - Unlike Order/Payment services, Catalog is 99% READ / 1% WRITE.
--   - Partitioning by TIME (created_at) is NOT recommended here because queries are
--     almost always "Get Menu for Outlet X" (Key-based lookup), not "Get Menus created today".
--   - Scaling Strategy:
--       1. Aggressive Caching (Redis/CDN) for Menu GETs.
--       2. Database Sharding by `brand_id` or `city` if table size exceeds 100GB.
--       3. Hash Partitioning on `outlet_item_availability` if inventory updates cause contention.
-- ============================================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================================================
-- GROUP A: IDENTITY & LOCATION TABLES
-- ============================================================================================

/*
TABLE: brands
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The "Root Tenant". All menu structures belong to a Brand (e.g., "McDonald's").
  This abstraction allows one company to manage multiple chains.

SCALABILITY:
  - Low volume table. No partitioning needed.
  - Acts as the primary Shard Key for multi-tenant architectures.
*/
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

/*
TABLE: outlets
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Represents physical store locations.
  INTEGRATION: Maps to `restaurant_id` in Analytics and `subject_id` in Feedback.

GEOSPATIAL INDEXING:
  - `location_point` uses SRID 4326 (WGS 84).
  - The SPATIAL INDEX allows lightning-fast "Restaurants within 5km" queries using `ST_Distance_Sphere`.

PARTITIONING ADVICE:
  - Usually not partitioned by range.
  - If global (US/EU/APAC), separate into different DB clusters (Shards) based on Country Code.
*/
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

-- ============================================================================================
-- GROUP B: MENU STRUCTURE TABLES
-- ============================================================================================

/*
TABLE: menu_categories
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Organizes items (e.g., "Starters", "Mains").
  Scoped to Brand, not Outlet. This allows centralized menu management (change once, update everywhere).
*/
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

/*
TABLE: menu_items
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The core product definition.
  Note: `base_price` is the default. Regional pricing happens in `outlet_item_availability`.

SCALABILITY:
  - Can grow to millions.
  - Primary access pattern is `WHERE category_id = ?` (covered by FK index).
  - If >100M rows, Shard by `brand_id`.
*/
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
-- GROUP C: MODIFIERS & ADD-ONS
-- ============================================================================================

/*
TABLE: modifier_groups & modifiers
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  Handles complexity like "Choose Size", "Extra Toppings".
  Recursion is avoided here for performance; we use a flat Group -> Modifier structure.
*/
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

/*
TABLE: outlet_item_availability
-----------------------------------------------------------------------------------------------
SIGNIFICANCE:
  The "Hot" Table. This controls what is actually visible on the user's app.
  Handles "Out of Stock" toggles and "Regional Pricing" (e.g., Airport prices).

PARTITIONING ADVICE (HASH PARTITIONING):
  - This table grows by (Num_Outlets * Num_Items).
  - 10k Outlets * 100 Items = 1 Million rows (Small).
  - 500k Outlets * 200 Items = 100 Million rows (Large).
  - IF performance degrades, use HASH PARTITIONING by `outlet_id`:
      PARTITION BY HASH(UNHEX(outlet_id)) PARTITIONS 16;
  - This keeps all data for one restaurant in the same partition, speeding up menu loads.
*/
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