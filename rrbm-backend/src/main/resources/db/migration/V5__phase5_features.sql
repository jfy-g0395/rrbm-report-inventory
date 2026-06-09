-- V5: Phase 5 - Daily Reports, Activity Log upgrade, Delivery Log

-- DAILY REPORTS
CREATE TABLE daily_reports (
    id               BIGSERIAL PRIMARY KEY,
    report_date      DATE UNIQUE NOT NULL,
    total_orders     INT NOT NULL DEFAULT 0,
    total_revenue    NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_cancelled  INT NOT NULL DEFAULT 0,
    cancelled_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_items_sold INT NOT NULL DEFAULT 0,
    top_product      VARCHAR(255),
    top_product_qty  INT DEFAULT 0,
    walk_in_count    INT DEFAULT 0,
    agent_count      INT DEFAULT 0,
    ecommerce_count  INT DEFAULT 0,
    fb_page_count    INT DEFAULT 0,
    closed_by        BIGINT REFERENCES users(id),
    closed_at        TIMESTAMPTZ,
    notes            TEXT,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);

-- Upgrade activity_log from V7's minimal schema to the full schema the app needs.
-- V7 created: id, user_id (NOT NULL), action, details, created_at
ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS user_name  VARCHAR(120);
ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS entity_type VARCHAR(50);
ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS entity_id   VARCHAR(100);
ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS report_date DATE DEFAULT CURRENT_DATE;
ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS is_closed   BOOLEAN DEFAULT FALSE;
ALTER TABLE activity_log DROP COLUMN IF EXISTS details;
ALTER TABLE activity_log ALTER COLUMN user_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_activity_log_date ON activity_log(report_date);
CREATE INDEX IF NOT EXISTS idx_activity_log_user ON activity_log(user_id);

-- DELIVERY LOG
CREATE TABLE delivery_log (
    id                 BIGSERIAL PRIMARY KEY,
    receipt_number     VARCHAR(10) UNIQUE NOT NULL,
    received_by        VARCHAR(120) NOT NULL,
    verified_by        VARCHAR(120),
    encoded_by_user_id BIGINT REFERENCES users(id),
    encoded_by_name    VARCHAR(120),
    total_items        INT NOT NULL DEFAULT 0,
    total_quantity     INT NOT NULL DEFAULT 0,
    notes              TEXT,
    report_date        DATE NOT NULL DEFAULT CURRENT_DATE,
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE delivery_log_items (
    id              BIGSERIAL PRIMARY KEY,
    delivery_log_id BIGINT REFERENCES delivery_log(id) ON DELETE CASCADE,
    product_id      BIGINT,
    product_name    VARCHAR(255) NOT NULL,
    quantity        INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_delivery_log_date ON delivery_log(report_date);
