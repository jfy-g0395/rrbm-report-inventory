-- V45: Purchase order enhancements for the supplier-aware PO redesign.
--   1. Widen po_number from VARCHAR(11) to VARCHAR(20)
--      (auto-generated format PO-MMDDYY-XXXXX = 15 chars; current limit causes hard 500s)
--   2. Add supplier_id FK — nullable to preserve historical records with no supplier link
--   3. Add vendor_reference — supplier's own document/reference number

ALTER TABLE purchase_orders
    ALTER COLUMN po_number TYPE VARCHAR(20);

ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS supplier_id       BIGINT      REFERENCES suppliers(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS vendor_reference  VARCHAR(50);

CREATE INDEX IF NOT EXISTS idx_po_supplier_id
    ON purchase_orders(supplier_id)
    WHERE supplier_id IS NOT NULL;
