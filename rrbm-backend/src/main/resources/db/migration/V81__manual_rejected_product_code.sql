-- =============================================================================
-- V81: Add product_code to manual_rejected_items
-- =============================================================================
-- Manual rejected items now reference a real inventory product (picked via the
-- smart search), so we snapshot its product code alongside the name. Nullable +
-- additive (IF NOT EXISTS) — existing rows are untouched.

ALTER TABLE manual_rejected_items ADD COLUMN IF NOT EXISTS product_code VARCHAR(64);
