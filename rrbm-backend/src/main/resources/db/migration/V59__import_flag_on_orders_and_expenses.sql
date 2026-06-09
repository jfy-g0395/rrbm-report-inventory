-- V59: Add import-tracking columns to orders and expenses.
--
-- is_imported = TRUE when the record was bulk-uploaded via CSV (not keyed live).
-- import_ref  = the original TEMP-DDMMYY-NNNN receipt number used in the upload file,
--               retained for traceability after a real order id is generated.
--
-- Who/when reuse the existing created_by / created_at columns (no new columns needed).
-- Upload permission is enforced in ImportController (U1).

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS is_imported BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS import_ref  TEXT;

ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS is_imported BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS import_ref  TEXT;
