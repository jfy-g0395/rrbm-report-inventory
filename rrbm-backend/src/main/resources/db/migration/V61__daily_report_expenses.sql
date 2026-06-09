-- ============================================================
-- V61 — Daily report expense tracking
-- Adds expense summary columns to daily_reports so batch-import
-- daily reports capture both sales and expense data in one snapshot.
-- ============================================================

ALTER TABLE daily_reports
    ADD COLUMN IF NOT EXISTS total_expenses DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN IF NOT EXISTS expenses_count  INT           NOT NULL DEFAULT 0;
