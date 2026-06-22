-- =============================================================================
-- V79: Manual rejected items
-- =============================================================================
-- Some rejected items arrive "unrecorded" (not tied to a delivery receipt or an
-- order void/return). Accounting / super-admin can log them manually here so they
-- show up in the Rejected Items report. These are RECORDS ONLY — they do not
-- change inventory stock. New table only; no existing data is touched.

CREATE TABLE IF NOT EXISTS manual_rejected_items (
    id           BIGSERIAL PRIMARY KEY,
    report_date  DATE         NOT NULL,
    product_id   BIGINT,
    product_name VARCHAR(255) NOT NULL,
    rejected_qty INTEGER      NOT NULL,
    reason       VARCHAR(500),
    created_by   VARCHAR(120),
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_manual_rejected_report_date ON manual_rejected_items (report_date);
