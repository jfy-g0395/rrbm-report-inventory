-- V9: Add product_code column (6-char alphanumeric, replaces SKU in UI)
ALTER TABLE products ADD COLUMN IF NOT EXISTS product_code VARCHAR(6);

-- Unique index with partial condition so NULLs are allowed (existing rows have no code)
CREATE UNIQUE INDEX IF NOT EXISTS idx_products_product_code
    ON products(product_code)
    WHERE product_code IS NOT NULL;
