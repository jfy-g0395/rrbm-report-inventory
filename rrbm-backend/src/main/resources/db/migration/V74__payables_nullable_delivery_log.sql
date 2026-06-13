-- V74: Allow payables that are not linked to a delivery_log row.
-- PO-direct receives (PATCH /purchase-orders/{id}/items/{itemId}/receive) create
-- payables without going through the delivery receipt form, so delivery_log_id
-- must be optional.
ALTER TABLE payables ALTER COLUMN delivery_log_id DROP NOT NULL;
