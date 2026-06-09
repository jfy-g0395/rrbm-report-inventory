-- V48: Add CANCELLED to the payables status check constraint.
--
-- The original constraint was created with only PENDING, PAID, PARTIAL.
-- The DR cancel endpoint needs to write CANCELLED when a delivery is reversed.
-- Drop and recreate the constraint to include the new value.
--
-- V47 is reserved for the item_code drop (deferred to a later phase).

ALTER TABLE payables
    DROP CONSTRAINT IF EXISTS payables_status_check;

ALTER TABLE payables
    ADD CONSTRAINT payables_status_check
        CHECK (status IN ('PENDING', 'PAID', 'PARTIAL', 'CANCELLED'));
