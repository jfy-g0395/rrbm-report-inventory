-- V25: Add po_number column to delivery_log.
-- Allows admins to explicitly link a delivery receipt to a specific Purchase Order.
-- Column is optional (NULL = unlinked delivery).
ALTER TABLE delivery_log ADD COLUMN IF NOT EXISTS po_number VARCHAR(30);
