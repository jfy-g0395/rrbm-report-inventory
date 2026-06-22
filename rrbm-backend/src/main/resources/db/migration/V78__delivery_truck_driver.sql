-- =============================================================================
-- V78: Add truck plate + driver name to delivery_log
-- =============================================================================
-- Delivery receipts (Receive Stocks) now record the delivering truck's plate
-- number and the driver's name for the delivery reports. Both nullable/additive
-- so existing delivery_log rows are untouched (idempotent ADD COLUMN IF NOT EXISTS).

ALTER TABLE delivery_log ADD COLUMN IF NOT EXISTS truck_plate VARCHAR(40);
ALTER TABLE delivery_log ADD COLUMN IF NOT EXISTS driver_name VARCHAR(120);
