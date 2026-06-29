-- V85: "Amended" marker for daily reports.
-- Additive & reversible. When a backdated "Add Records" entry lands on a date whose
-- daily report was already closed, the report is recomputed from the ledger and stamped
-- amended (who/when) — the original closed_by / closed_at are preserved for audit.
-- Existing rows default to amended=false, so nothing changes for already-closed reports.
ALTER TABLE daily_reports ADD COLUMN IF NOT EXISTS amended    BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE daily_reports ADD COLUMN IF NOT EXISTS amended_at TIMESTAMPTZ;
ALTER TABLE daily_reports ADD COLUMN IF NOT EXISTS amended_by BIGINT;
