-- V27: Collections (PENDING_COLLECTION order status) + force-close daily sales override
-- Adds tracking columns to orders for when an order is deferred to the collection page,
-- and when it is eventually collected. Also adds unfulfilled stats to daily_reports.

-- Track when an order was pushed to pending collection (forced daily close override)
ALTER TABLE orders ADD COLUMN IF NOT EXISTS pending_collection_at TIMESTAMPTZ;

-- Track when a pending-collection order was eventually collected (payment received)
ALTER TABLE orders ADD COLUMN IF NOT EXISTS collected_at   TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS collected_by   VARCHAR(100);

-- Daily report: count + total of orders left uncollected at force-close time
ALTER TABLE daily_reports ADD COLUMN IF NOT EXISTS unfulfilled_orders  INT           NOT NULL DEFAULT 0;
ALTER TABLE daily_reports ADD COLUMN IF NOT EXISTS unfulfilled_amount  DECIMAL(14,2) NOT NULL DEFAULT 0.00;
