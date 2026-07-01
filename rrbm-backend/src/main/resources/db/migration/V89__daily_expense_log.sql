-- V89: persisted per-day expense snapshot ("Expense Log").
--
-- Created/refreshed whenever a day is closed (manual daily close, import close, or the
-- Add Records "create daily report" checkbox). Lets the Expense page show the full expense
-- log for each day the same way daily reports are browsed, without recomputing on every view.

CREATE TABLE IF NOT EXISTS daily_expense_logs (
    id            BIGSERIAL PRIMARY KEY,
    report_date   DATE NOT NULL UNIQUE,
    total_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    entry_count   INTEGER NOT NULL DEFAULT 0,
    snapshot_json TEXT NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    BIGINT
);

CREATE INDEX IF NOT EXISTS idx_daily_expense_logs_date ON daily_expense_logs (report_date);
