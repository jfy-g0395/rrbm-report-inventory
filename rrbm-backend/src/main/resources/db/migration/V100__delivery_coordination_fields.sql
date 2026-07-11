-- V100: delivery coordination fields for scheduled / order deliveries (Fix 5).
-- Captured at "confirm final order" (driver + at least one helper required) and editable
-- in the order-delivery editor. All columns are nullable/additive — no existing row is
-- affected, and none are money columns. Helpers are stored as one text blob (one name per line).
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_driver         TEXT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_helpers        TEXT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_coordinated_by TEXT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_notes          TEXT;
