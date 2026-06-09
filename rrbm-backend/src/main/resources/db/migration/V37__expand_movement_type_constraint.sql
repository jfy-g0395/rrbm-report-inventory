-- M-12 fix: add REFUND_RETURN and VOID_RETURN to the allowed movement_type values.
-- PostgreSQL does not support ALTER CHECK CONSTRAINT, so the constraint is
-- dropped and re-created as a strict superset of the original five values.
ALTER TABLE inventory_movements DROP CONSTRAINT chk_movement_type;

ALTER TABLE inventory_movements ADD CONSTRAINT chk_movement_type
    CHECK (movement_type IN (
        'ORDER_OUT',
        'CANCELLED_RETURN',
        'MANUAL_ADJUST',
        'RESTOCK',
        'TRANSFER',
        'REFUND_RETURN',
        'VOID_RETURN'
    ));
