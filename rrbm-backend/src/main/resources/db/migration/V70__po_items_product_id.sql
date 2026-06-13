-- V70: Add product_id to po_items so PO receipts can update inventory.
-- Nullable — existing PO items created before this migration have no product link.
ALTER TABLE po_items ADD COLUMN IF NOT EXISTS product_id BIGINT REFERENCES products(id) ON DELETE SET NULL;
