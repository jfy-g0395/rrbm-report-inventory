-- V40: Expand chk_movement_type constraint for the void / cancel / return redesign.
--
-- PostgreSQL does not support ALTER CHECK CONSTRAINT, so the constraint is
-- dropped and re-created as a strict superset of the existing eleven values.
--
-- New types added:
--   ITEM_VOID       — item-level same-day void (new void flow); stock restored to
--                     warehouse when voiding a DELIVERED order.
--   RETURN_SELLABLE — customer return, goods confirmed sellable; stock restored.
--   RETURN_REJECTED — customer return, goods rejected / damaged; no stock change.
--                     Movement record is the audit trail only.
--   CANCEL_REJECTED — cancellation where specific items are confirmed rejected /
--                     damaged; no stock change. Complements the existing
--                     CANCELLED_RETURN type (which handles sellable restores).
--
-- Existing types retained without change:
--   ORDER_OUT, CANCELLED_RETURN, MANUAL_ADJUST, RESTOCK, TRANSFER,
--   REFUND_RETURN, VOID_RETURN

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
        'CANCEL_REJECTED'
    ));
