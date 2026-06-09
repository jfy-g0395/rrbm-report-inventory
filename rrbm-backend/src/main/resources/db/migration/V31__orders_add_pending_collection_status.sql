-- V31: Add PENDING_COLLECTION to the orders status check constraint.
-- V27 introduced this status value but forgot to update the constraint,
-- causing a check constraint violation on force-close.

ALTER TABLE orders DROP CONSTRAINT IF EXISTS chk_status;

ALTER TABLE orders ADD CONSTRAINT chk_status
    CHECK (status IN ('ACTIVE', 'PENDING', 'CANCELLED', 'CLOSED', 'DELIVERED', 'PENDING_COLLECTION'));
