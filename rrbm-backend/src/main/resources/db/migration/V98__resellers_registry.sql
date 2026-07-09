-- ============================================================================
-- V98 — Resellers & Distributors registry + per-reseller price mapping
-- ============================================================================
-- A registry of reseller/distributor partners (mirrors the agents registry),
-- a per-reseller custom unit-price map per product, and a reseller_id FK on
-- orders (parallel to agent_id). Order source RESELLER/DISTRIBUTOR already
-- exists (V88); this backs the free-text name with a real record.
--
-- Money columns use numeric(13,5) to match the system-wide 5-decimal money
-- precision established in V96 (order_items.base_price, etc.).
-- ============================================================================

CREATE TABLE resellers (
    id                   BIGSERIAL PRIMARY KEY,
    reseller_code        VARCHAR(20)  NOT NULL UNIQUE,        -- RSL-YYYY-NNNN / DST-YYYY-NNNN
    type                 VARCHAR(15)  NOT NULL CHECK (type IN ('RESELLER','DISTRIBUTOR')),
    name                 VARCHAR(150) NOT NULL,               -- business/entity name (required)
    contact_person       VARCHAR(100) NOT NULL,               -- required
    contact_number       VARCHAR(50)  NOT NULL,               -- required
    address              TEXT         NOT NULL,               -- required
    notes                TEXT,                                -- optional special instructions
    delivery_days        VARCHAR(100),                        -- optional CSV of MON..SUN
    delivery_time_window VARCHAR(50),                         -- optional, e.g. '8:00 AM - 12:00 PM'
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / INACTIVE
    registration_date    DATE         NOT NULL DEFAULT CURRENT_DATE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           BIGINT       REFERENCES users(id)
);

CREATE TABLE reseller_product_prices (
    id           BIGSERIAL PRIMARY KEY,
    reseller_id  BIGINT        NOT NULL REFERENCES resellers(id) ON DELETE CASCADE,
    product_id   BIGINT        NOT NULL REFERENCES products(id),
    unit_price   NUMERIC(13,5) NOT NULL CHECK (unit_price >= 0),
    UNIQUE (reseller_id, product_id)
);

CREATE INDEX idx_reseller_prices_reseller ON reseller_product_prices(reseller_id);

-- Link an order to a registered reseller/distributor (nullable — parallel to agent_id).
ALTER TABLE orders ADD COLUMN reseller_id BIGINT REFERENCES resellers(id);
CREATE INDEX idx_orders_reseller ON orders(reseller_id);
