-- V4__add_delivered_status.sql
-- Adds 'DELIVERED' as a valid order status.
-- Status flow: ACTIVE → DELIVERED (completed)
--              ACTIVE → PENDING (on hold for changes)
--              PENDING → ACTIVE (changes done, resume)
--              ACTIVE/PENDING → CANCELLED (cancelled with master key)

-- Drop the old constraint and create a new one with DELIVERED included
ALTER TABLE orders DROP CONSTRAINT chk_status;
ALTER TABLE orders ADD CONSTRAINT chk_status 
    CHECK (status IN ('ACTIVE', 'PENDING', 'CANCELLED', 'CLOSED', 'DELIVERED'));
