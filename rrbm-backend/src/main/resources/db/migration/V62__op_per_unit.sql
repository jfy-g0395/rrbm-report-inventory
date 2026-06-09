-- U15: Replace percentage-based op_rate with flat-amount op_per_unit.
-- op_rate (NUMERIC(5,4)) stays for backward compat but is no longer used
-- by new orders/imports. Going forward, commission = op_per_unit * qty.

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS op_per_unit NUMERIC(10,2);

ALTER TABLE commission_entries
    ADD COLUMN IF NOT EXISTS op_per_unit NUMERIC(10,2);
