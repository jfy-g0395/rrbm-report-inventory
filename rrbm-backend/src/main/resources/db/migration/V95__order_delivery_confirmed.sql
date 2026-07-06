-- =============================================================================
-- V95: Final-order confirmation for scheduled deliveries
-- =============================================================================
-- Adds a "confirm the final order before delivery" gate to the deferred-delivery
-- flow (V93). A SCHEDULED_DELIVERY order's line items can be edited before it is
-- delivered; once the final list is agreed it is CONFIRMED, and only then may it
-- be fulfilled ("Mark Delivered"). Editing items again clears the confirmation
-- (re-confirmation required). The order stays SCHEDULED_DELIVERY throughout, so
-- every existing report/inventory exclusion still applies — no chk_status change.
--
-- Additive / reversible: two new columns. delivery_confirmed defaults FALSE, so
-- existing scheduled orders start unconfirmed and must be confirmed before
-- delivery (the intended new behaviour). No existing rows are otherwise touched.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_confirmed    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_confirmed_at TIMESTAMPTZ;
