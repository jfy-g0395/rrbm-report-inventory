-- V86: Per-line PO tag for multi-PO deliveries.
-- Additive & reversible: two nullable columns on delivery_log_items so a single
-- delivery receipt can fulfil lines across multiple POs (incl. the same product on
-- two different POs). NULL = legacy/untagged line — existing auto-match is unchanged.
-- po_number width matches delivery_log.po_number (VARCHAR(30)).
ALTER TABLE delivery_log_items ADD COLUMN IF NOT EXISTS po_item_id BIGINT;
ALTER TABLE delivery_log_items ADD COLUMN IF NOT EXISTS po_number  VARCHAR(30);
