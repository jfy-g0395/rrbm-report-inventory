-- V60: late_imported flag for backdated batch import orders.
-- late_imported = TRUE when an order was imported for a date whose daily report
-- was already closed — staff see it as a retroactive entry.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS late_imported BOOLEAN NOT NULL DEFAULT FALSE;
