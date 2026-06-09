-- A2: Per-unit O.P. tracking fields on order_items.
-- All nullable — existing rows remain valid.
ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS base_price  NUMERIC(10,2),  -- company cost / base price per unit
    ADD COLUMN IF NOT EXISTS op_rate     NUMERIC(5,4),   -- O.P. rate, e.g. 0.15 = 15 %
    ADD COLUMN IF NOT EXISTS op_amount   NUMERIC(10,2);  -- base_price * op_rate * quantity (stored for history)
