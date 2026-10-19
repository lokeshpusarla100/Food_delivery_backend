-- Additive migration; never edit the original V1.0.0 Flyway checksum.
ALTER TABLE brands ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE outlets ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata';
ALTER TABLE outlets ADD COLUMN is_accepting_orders BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE outlets ADD COLUMN prep_minutes SMALLINT NOT NULL DEFAULT 30;
ALTER TABLE outlets ADD COLUMN delivery_radius_km DECIMAL(6,2) NOT NULL DEFAULT 5.00;
ALTER TABLE outlets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE outlets ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE menu_categories ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE menu_items ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE menu_items ADD COLUMN is_vegetarian BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE menu_items ADD COLUMN allergens VARCHAR(255) NULL;
ALTER TABLE menu_items ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE menu_items ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- MySQL SRID 4326 is geographic: use the SRS-aware latitude/longitude functions.
ALTER TABLE outlets MODIFY COLUMN latitude DECIMAL(10,8) GENERATED ALWAYS AS (ST_Latitude(location_point)) VIRTUAL;
ALTER TABLE outlets MODIFY COLUMN longitude DECIMAL(11,8) GENERATED ALWAYS AS (ST_Longitude(location_point)) VIRTUAL;
CREATE INDEX idx_outlets_lat_lon ON outlets(latitude,longitude);
CREATE INDEX idx_outlets_brand_active ON outlets(brand_id,is_active);

CREATE TABLE restaurant_staff (
  brand_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  role VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (brand_id,user_id),
  CONSTRAINT fk_restaurant_staff_brand FOREIGN KEY(brand_id) REFERENCES brands(brand_id),
  CONSTRAINT chk_restaurant_role CHECK(role IN ('OWNER','MANAGER','EDITOR')),
  INDEX idx_restaurant_staff_user (user_id,brand_id)
) ENGINE=InnoDB;

CREATE TABLE outlet_weekly_hours (
  outlet_id CHAR(36) NOT NULL,
  day_of_week TINYINT NOT NULL,
  opens_at TIME NOT NULL,
  closes_at TIME NOT NULL,
  is_closed BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (outlet_id,day_of_week),
  CONSTRAINT fk_weekly_hours_outlet FOREIGN KEY(outlet_id) REFERENCES outlets(outlet_id),
  CONSTRAINT chk_weekly_day CHECK(day_of_week BETWEEN 1 AND 7)
) ENGINE=InnoDB;

CREATE TABLE outlet_blackouts (
  blackout_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  outlet_id CHAR(36) NOT NULL,
  starts_at_utc DATETIME(6) NOT NULL,
  ends_at_utc DATETIME(6) NOT NULL,
  reason VARCHAR(255),
  CONSTRAINT fk_blackouts_outlet FOREIGN KEY(outlet_id) REFERENCES outlets(outlet_id),
  CONSTRAINT chk_blackout_bounds CHECK (ends_at_utc > starts_at_utc),
  INDEX idx_blackouts_current(outlet_id,starts_at_utc,ends_at_utc)
) ENGINE=InnoDB;

CREATE TABLE restaurant_outbox (
  event_id CHAR(36) PRIMARY KEY,
  aggregate_id CHAR(36) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  published_at DATETIME(6),
  attempt_count INT NOT NULL DEFAULT 0,
  CONSTRAINT chk_outbox_status CHECK(status IN ('PENDING','PUBLISHED','FAILED')),
  INDEX idx_rest_outbox_pending (status,created_at)
) ENGINE=InnoDB;
