-- =============================================================================
-- V94: Scheduled inter-warehouse stock transfers
-- =============================================================================
-- Backs the new "Stock Moves" card under the Delivery Schedule tab. A transfer
-- is REQUESTED (multiple product lines, each from→to warehouse), then an
-- approver (SUPER_ADMIN / ADMINISTRATOR / DELIVERY_MANAGEMENT) APPROVES it, and
-- finally COMPLETES it on arrival — only COMPLETE actually moves stock (two
-- TRANSFER rows per line in inventory_movements). Reject/reschedule/cancel are
-- terminal or repeatable state changes that never touch stock.
--
-- New tables only; no existing data is touched. movement_type='TRANSFER' is
-- already allowed by chk_movement_type (V1/V37/V40/V41) — no movement migration.

CREATE TABLE IF NOT EXISTS stock_transfers (
    id                BIGSERIAL   PRIMARY KEY,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    scheduled_date    DATE,
    requested_by      BIGINT,
    requested_by_name VARCHAR(120),
    approved_by       BIGINT,
    approved_by_name  VARCHAR(120),
    approved_at       TIMESTAMP,
    completed_at      TIMESTAMP,
    reject_reason     VARCHAR(500),
    notes             VARCHAR(500),
    change_log        TEXT,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_stock_transfer_status
        CHECK (status IN ('PENDING','APPROVED','COMPLETED','REJECTED','CANCELLED'))
);

CREATE TABLE IF NOT EXISTS stock_transfer_items (
    id             BIGSERIAL   PRIMARY KEY,
    transfer_id    BIGINT      NOT NULL REFERENCES stock_transfers(id) ON DELETE CASCADE,
    product_id     BIGINT,
    product_code   VARCHAR(50),
    product_name   VARCHAR(255) NOT NULL,
    from_warehouse VARCHAR(10) NOT NULL,
    to_warehouse   VARCHAR(10) NOT NULL,
    quantity       INTEGER     NOT NULL,
    CONSTRAINT chk_stock_transfer_item_qty CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_stock_transfers_status   ON stock_transfers (status);
CREATE INDEX IF NOT EXISTS idx_stock_transfer_items_txn ON stock_transfer_items (transfer_id);
