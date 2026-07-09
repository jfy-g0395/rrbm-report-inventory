-- ============================================================================
-- V97 — Record the product's running stock total after each inventory movement
-- ============================================================================
-- Adds a nullable balance_after column to inventory_movements. Every stock
-- change (orders, set-component deductions, cancels, voids, returns,
-- corrections, transfers, restocks, manual adjusts) funnels through
-- InventoryService.logMovement, which now stamps the product's grand total
-- (wh1 + wh2 + wh3) as of immediately after the movement. This lets us trace
-- exactly how a product's total changed on every movement — including
-- background/system deductions — so phantom deductions become auditable.
--
-- Nullable and additive: existing historical rows keep NULL (their balance at
-- the time was not captured); only new movements carry the running total.
-- ============================================================================

ALTER TABLE inventory_movements ADD COLUMN balance_after INTEGER;
