-- =============================================================================
-- V93: Deferred (scheduled) order delivery
-- =============================================================================
-- Backs the new "Order Deliveries" card under the Delivery Schedule tab. An
-- order can be created as SCHEDULED_DELIVERY: it records NOTHING (no stock
-- deduction, no SALE, no commission, no cash) until it resolves to exactly one
-- terminal state:
--   * DELIVERED  — recorded on the delivery day (stock/sale/commission/cash), or
--   * CANCELLED  — dropped, nothing ever recorded.
-- Reschedule (move the date) is repeatable and never records anything.
--
-- Additive / reversible: three new nullable columns + a widened chk_status.
-- No existing rows are touched (all new columns default NULL; the widened CHECK
-- is a strict superset of the previous allowed set).

ALTER TABLE orders ADD COLUMN IF NOT EXISTS scheduled_delivery_date DATE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_change_log     TEXT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivered_at            TIMESTAMPTZ;

-- Widen the status CHECK to include SCHEDULED_DELIVERY (see V4 / V31 for history).
ALTER TABLE orders DROP CONSTRAINT IF EXISTS chk_status;
ALTER TABLE orders ADD CONSTRAINT chk_status
    CHECK (status IN ('ACTIVE','PENDING','CANCELLED','CLOSED','DELIVERED','PENDING_COLLECTION','SCHEDULED_DELIVERY'));
