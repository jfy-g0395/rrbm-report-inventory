-- Session 40: Set / Bundle Products
-- Adds is_set flag to products and a join table that maps a set product to its components.

-- Mark products that are "set products" (composed of multiple inventory items)
ALTER TABLE products ADD COLUMN IF NOT EXISTS is_set BOOLEAN NOT NULL DEFAULT FALSE;

-- Maps a set product → its component products with ratio per set
CREATE TABLE IF NOT EXISTS product_set_components (
    id                   BIGSERIAL PRIMARY KEY,
    set_product_id       BIGINT  NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    component_product_id BIGINT  NOT NULL REFERENCES products(id),
    quantity_per_set     INTEGER NOT NULL DEFAULT 1,
    UNIQUE (set_product_id, component_product_id)
);

CREATE INDEX IF NOT EXISTS idx_psc_set_id       ON product_set_components(set_product_id);
CREATE INDEX IF NOT EXISTS idx_psc_component_id ON product_set_components(component_product_id);
