-- V34: Add status to delivery_log (RECEIVED / CANCELLED) and warehouse to delivery_log_items
ALTER TABLE delivery_log ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED';
ALTER TABLE delivery_log_items ADD COLUMN warehouse VARCHAR(10) NOT NULL DEFAULT 'wh1';
