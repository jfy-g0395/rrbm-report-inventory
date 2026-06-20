-- V76: Add CORRECTION_IN and CORRECTION_OUT to chk_movement_type constraint.
--
-- These movement types are written by the "Correct Recorded Item" failsafe
-- (POST /api/orders/{id}/correct-item):
--   CORRECTION_IN  — recorded (wrong) item's stock restored to its origin warehouse
--   CORRECTION_OUT — replacement item's stock deducted from the chosen warehouse
--
-- Existing types retained without change (all 12 from V41):
--   ORDER_OUT, CANCELLED_RETURN, MANUAL_ADJUST, RESTOCK, TRANSFER,
--   REFUND_RETURN, VOID_RETURN, ITEM_VOID, RETURN_SELLABLE,
--   RETURN_REJECTED, CANCEL_REJECTED, VOID_REJECTED

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
        'VOID_REJECTED',
        'CORRECTION_IN',
        'CORRECTION_OUT'
    ));
