-- ========================================================================
-- RRBM Backend — Initial Database Schema
-- Migration V1: Create all core tables
-- ========================================================================
-- ========================================================================
-- USERS TABLE (Employees with login credentials)
-- ========================================================================
CREATE TABLE users (
  id              BIGSERIAL PRIMARY KEY,
  email           VARCHAR(120) UNIQUE NOT NULL,
  password_hash   VARCHAR(255) NOT NULL,
  full_name       VARCHAR(120) NOT NULL,
  role            VARCHAR(20) NOT NULL DEFAULT 'STAFF',
  status          VARCHAR(20) DEFAULT 'ACTIVE',
  last_login_at   TIMESTAMPTZ,
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  updated_at      TIMESTAMPTZ DEFAULT NOW(),
  
  CONSTRAINT chk_role CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'STAFF')),
  CONSTRAINT chk_status CHECK (status IN ('ACTIVE', 'AWAY', 'DISABLED'))
);

CREATE INDEX idx_users_email ON users(email);

-- ========================================================================
-- PRODUCTS TABLE (Inventory catalog)
-- ========================================================================
CREATE TABLE products (
  id                   BIGSERIAL PRIMARY KEY,
  sku                  VARCHAR(50) UNIQUE,
  name                 VARCHAR(200) NOT NULL,
  category             VARCHAR(80),
  selling_tag          VARCHAR(20) DEFAULT 'SELLING',
  unit_price           NUMERIC(10, 2) NOT NULL DEFAULT 0,
  unit_cost            NUMERIC(10, 2) DEFAULT 0,
  threshold_critical   INT DEFAULT 1000,
  threshold_low        INT DEFAULT 0,
  stock_wh1            INT NOT NULL DEFAULT 0,
  stock_wh2            INT NOT NULL DEFAULT 0,
  stock_wh3            INT NOT NULL DEFAULT 0,
  active               BOOLEAN DEFAULT TRUE,
  created_at           TIMESTAMPTZ DEFAULT NOW(),
  updated_at           TIMESTAMPTZ DEFAULT NOW(),
  
  CONSTRAINT chk_selling_tag CHECK (selling_tag IN ('HOT', 'SELLING', 'SLOW')),
  CONSTRAINT chk_stock_positive CHECK (stock_wh1 >= 0 AND stock_wh2 >= 0 AND stock_wh3 >= 0)
);

CREATE INDEX idx_products_category ON products(category);
CREATE INDEX idx_products_active ON products(active);

-- ========================================================================
-- ORDERS TABLE (Order headers)
-- ========================================================================
CREATE TABLE orders (
  id                    VARCHAR(13) PRIMARY KEY,
  customer_name         VARCHAR(200) NOT NULL DEFAULT 'Walk-in',
  source                VARCHAR(30) NOT NULL DEFAULT 'WALK_IN',
  agent_name            VARCHAR(120),
  fb_page               VARCHAR(120),
  ecommerce_platform    VARCHAR(20),
  payment_mode          VARCHAR(30) NOT NULL DEFAULT 'CASH',
  subtotal              NUMERIC(12, 2) NOT NULL DEFAULT 0,
  discount              NUMERIC(12, 2) DEFAULT 0,
  total                 NUMERIC(12, 2) NOT NULL DEFAULT 0,
  status                VARCHAR(20) DEFAULT 'ACTIVE',
  notes                 TEXT,
  created_by            BIGINT REFERENCES users(id),
  created_at            TIMESTAMPTZ DEFAULT NOW(),
  cancelled_at          TIMESTAMPTZ,
  cancelled_by          BIGINT REFERENCES users(id),
  cancellation_reason   VARCHAR(255),
  
  CONSTRAINT chk_source CHECK (source IN ('WALK_IN', 'AGENT', 'ECOMMERCE', 'FACEBOOK_PAGE')),
  CONSTRAINT chk_payment CHECK (payment_mode IN ('CASH', 'GCASH', 'PAYMAYA', 'BANK_TRANSFER', 'BANK_DEPOSIT', 'ONLINE')),
  CONSTRAINT chk_ecom CHECK (ecommerce_platform IN ('SHOPEE', 'TIKTOK', 'LAZADA') OR ecommerce_platform IS NULL),
  CONSTRAINT chk_status CHECK (status IN ('ACTIVE', 'PENDING', 'CANCELLED', 'CLOSED'))
);

CREATE INDEX idx_orders_created_at ON orders(created_at DESC);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_customer ON orders(customer_name);

-- ========================================================================
-- ORDER_ITEMS TABLE (Line items for each order)
-- ========================================================================
CREATE TABLE order_items (
  id             BIGSERIAL PRIMARY KEY,
  order_id       VARCHAR(13) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id     BIGINT REFERENCES products(id),
  product_name   VARCHAR(200) NOT NULL,
  quantity       INT NOT NULL,
  unit_price     NUMERIC(10, 2) NOT NULL,
  subtotal       NUMERIC(12, 2) NOT NULL,
  warehouse      VARCHAR(10) DEFAULT 'wh1',
  
  CONSTRAINT chk_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id);

-- ========================================================================
-- INVENTORY_MOVEMENTS TABLE (Audit trail for stock changes)
-- ========================================================================
CREATE TABLE inventory_movements (
  id             BIGSERIAL PRIMARY KEY,
  product_id     BIGINT NOT NULL REFERENCES products(id),
  movement_type  VARCHAR(30) NOT NULL,
  warehouse      VARCHAR(10) NOT NULL,
  quantity       INT NOT NULL,
  reference_id   VARCHAR(50),
  reason         VARCHAR(255),
  user_id        BIGINT REFERENCES users(id),
  created_at     TIMESTAMPTZ DEFAULT NOW(),
  
  CONSTRAINT chk_movement_type CHECK (movement_type IN ('ORDER_OUT', 'CANCELLED_RETURN', 'MANUAL_ADJUST', 'RESTOCK', 'TRANSFER'))
);

CREATE INDEX idx_movements_product ON inventory_movements(product_id);
CREATE INDEX idx_movements_date ON inventory_movements(created_at DESC);

-- ========================================================================
-- ORDER_ID_COUNTER TABLE (Generates sequential Order IDs per day)
-- ========================================================================
CREATE TABLE order_id_counter (
  date_key       VARCHAR(6) PRIMARY KEY,
  last_number    INT NOT NULL DEFAULT 0
);

-- ========================================================================
-- SETTINGS TABLE (System configuration key-value store)
-- ========================================================================
CREATE TABLE settings (
  key_name       VARCHAR(80) PRIMARY KEY,
  value          VARCHAR(500),
  description    VARCHAR(255),
  updated_at     TIMESTAMPTZ DEFAULT NOW(),
  updated_by     BIGINT REFERENCES users(id)
);

-- ========================================================================
-- AUDIT_LOG TABLE (Security and compliance trail)
-- ========================================================================
CREATE TABLE audit_log (
  id             BIGSERIAL PRIMARY KEY,
  user_id        BIGINT REFERENCES users(id),
  action         VARCHAR(80) NOT NULL,
  entity_type    VARCHAR(40),
  entity_id      VARCHAR(40),
  details        TEXT,
  ip_address     VARCHAR(45),
  created_at     TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_audit_user ON audit_log(user_id);
CREATE INDEX idx_audit_action ON audit_log(action);
CREATE INDEX idx_audit_date ON audit_log(created_at DESC);