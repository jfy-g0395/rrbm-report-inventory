-- V84: "Recording only" flag for batch imports.
-- Additive & reversible: two boolean columns defaulting to false, so every
-- existing row keeps today's behavior. When true, the import recorded the
-- order/expense for reporting & commissions but did NOT deduct inventory
-- stock or move cash-on-hand (used for old historical back-records).
ALTER TABLE orders   ADD COLUMN IF NOT EXISTS recording_only BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS recording_only BOOLEAN NOT NULL DEFAULT false;
