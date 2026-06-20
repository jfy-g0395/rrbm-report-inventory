-- Daily report: total pizza boxes sold for the day.
-- Snapshot column populated at close time from order_items joined to products
-- where category = 'Pizza Box'. Nullable-safe default for existing rows.

ALTER TABLE daily_reports
    ADD COLUMN IF NOT EXISTS total_pizza_boxes INTEGER NOT NULL DEFAULT 0;

-- Backfill every already-closed report from the live order data.
-- The order id prefix is DDMMYY (matching the report_date), so derive it with to_char.
UPDATE daily_reports dr
SET total_pizza_boxes = COALESCE((
    SELECT SUM(oi.quantity)
    FROM order_items oi
    JOIN orders   o ON oi.order_id = o.id
    JOIN products p ON oi.product_id = p.id
    WHERE o.id LIKE to_char(dr.report_date, 'DDMMYY') || '-%'
      AND o.status NOT IN ('CANCELLED', 'PENDING', 'PENDING_COLLECTION')
      AND p.category = 'Pizza Box'
), 0);
