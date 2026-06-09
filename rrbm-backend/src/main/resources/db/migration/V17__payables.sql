-- V17: Add received_qty, rejected_qty, unit_cost to delivery_log_items
--      Create payables table for supplier invoice tracking

ALTER TABLE delivery_log_items
  ADD COLUMN IF NOT EXISTS received_qty  INTEGER      NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS rejected_qty  INTEGER      NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS unit_cost     NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  ADD COLUMN IF NOT EXISTS line_total    NUMERIC(12,2) GENERATED ALWAYS AS (received_qty * unit_cost) STORED;

-- Payables table: one row per delivery receipt
CREATE TABLE IF NOT EXISTS payables (
  id               BIGSERIAL PRIMARY KEY,
  delivery_log_id  BIGINT NOT NULL REFERENCES delivery_log(id) ON DELETE CASCADE,
  receipt_number   VARCHAR(20)    NOT NULL,
  supplier_name    VARCHAR(200),
  total_amount     NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  amount_paid      NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  balance          NUMERIC(12,2) GENERATED ALWAYS AS (total_amount - amount_paid) STORED,
  status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','PAID','PARTIAL')),
  notes            TEXT,
  paid_at          TIMESTAMP,
  paid_by          VARCHAR(100),
  created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
  created_by       VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_payables_status       ON payables(status);
CREATE INDEX IF NOT EXISTS idx_payables_delivery_log ON payables(delivery_log_id);
