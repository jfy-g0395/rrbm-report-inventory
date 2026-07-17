-- V102 — Remove the legacy "Item Code" columns.
--
-- Purchase Orders and stock-receiving now identify products by product_id
-- (item_code removal, Sessions 2–3). The 6-char product_code remains the SKU
-- used across the system and CSV import; supplier_item_code (on po_items and
-- supplier_product_mapping) is a DIFFERENT column and is NOT touched here.
--
-- Read-only pre-check (2026-07-16): all po_items rows carry a non-null product_id,
-- so no open PO line loses its identity. Reversible by restoring the pre-deploy
-- pg_dump backup (the columns held no data that is derived from anywhere else).

ALTER TABLE products  DROP COLUMN IF EXISTS item_code;
ALTER TABLE po_items  DROP COLUMN IF EXISTS item_code;
