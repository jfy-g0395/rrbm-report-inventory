-- Daily report: redefine total_pizza_boxes from "sold" to "DISPATCHED".
-- Execs want the total quantity of pizza boxes dispatched within the day — including orders that
-- are still PENDING or PENDING_COLLECTION — excluded only when the order was cancelled/voided
-- (a full void sets orders.status = 'CANCELLED'). Runtime queries change in DailyReportService;
-- this migration re-backfills every already-closed report so history is consistent.
--
-- Supersedes the V75 backfill. Reporting column only — no financial/ledger fields are touched.

UPDATE daily_reports dr
SET total_pizza_boxes = COALESCE((
    SELECT SUM(oi.quantity)
    FROM order_items oi
    JOIN orders   o ON oi.order_id = o.id
    JOIN products p ON oi.product_id = p.id
    WHERE o.id LIKE to_char(dr.report_date, 'DDMMYY') || '-%'
      AND o.status <> 'CANCELLED'
      AND p.category = 'Pizza Box'
), 0);
