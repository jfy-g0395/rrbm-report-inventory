-- V22: Add description (TEXT) and item_code (VARCHAR 50) to products table
-- item_code is a separate identifier used for Purchase Orders (e.g. supplier part numbers)
-- It is optional and unique (enforced with a partial index — allows multiple NULLs)

ALTER TABLE products ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS item_code VARCHAR(50);

-- Partial unique index: enforces uniqueness only for non-NULL item_code values
CREATE UNIQUE INDEX IF NOT EXISTS idx_products_item_code
    ON products(item_code) WHERE item_code IS NOT NULL;
