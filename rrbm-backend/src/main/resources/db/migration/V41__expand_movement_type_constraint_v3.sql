-- V41: Add VOID_REJECTED to chk_movement_type constraint.
--
-- VOID_REJECTED is the movement type written when an item is voided with
-- disposition=REJECTED. Previously written as ITEM_VOID (same as sellable
-- voids), which made rejected and sellable void movements indistinguishable
-- in reporting queries. Using a distinct type fixes that.
--
-- Existing types retained without change (all 11 from V40):
--   ORDER_OUT, CANCELLED_RETURN, MANUAL_ADJUST, RESTOCK, TRANSFER,
--   REFUND_RETURN, VOID_RETURN, ITEM_VOID, RETURN_SELLABLE,
--   RETURN_REJECTED, CANCEL_REJECTED

ALTER TABLE inventory_movements DROP CONSTRAINT chk_movement_type;

ALTER TABLE inventory_movements ADD CONSTRAINT chk_movement_type
    CHECK (movement_type IN (
        'ORDER_OUT',
        'CANCELLED_RETURN',
        'MANUAL_ADJUST',
        'RESTOCK',
        'TRANSFER',
        'REFUND_RETURN',
        'VOID_RETURN',
        'ITEM_VOID',
        'RETURN_SELLABLE',
        'RETURN_REJECTED',
        'CANCEL_REJECTED',
        'VOID_REJECTED'
    ));
