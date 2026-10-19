-- EPSG:4326 defines axis one as latitude and axis two as longitude.
-- The original V1 generated columns used ST_Y/ST_X in reverse.
-- ST_Latitude and ST_Longitude are SRS-aware and match the names.
ALTER TABLE saved_addresses
    MODIFY COLUMN latitude DECIMAL(10,8) GENERATED ALWAYS AS (ST_Latitude(location_point)) VIRTUAL,
    MODIFY COLUMN longitude DECIMAL(11,8) GENERATED ALWAYS AS (ST_Longitude(location_point)) VIRTUAL;
