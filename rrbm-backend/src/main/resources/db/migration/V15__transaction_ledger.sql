-- ============================================================
-- V15 — Transaction Ledger (Accounting Architecture)
-- Adds an immutable transaction log as the source of truth
-- for all financial reporting.  Orders remain untouched.
-- ============================================================

-- 1. Core accounting table ---------------------------------
CREATE TABLE transactions (
    id               BIGSERIAL      PRIMARY KEY,
    transaction_code VARCHAR(80)    NOT NULL UNIQUE,
    order_id         VARCHAR(20)    REFERENCES orders(id),
    transaction_type VARCHAR(20)    NOT NULL,   -- SALE | REFUND | RETURN | VOID | DISCOUNT | ADJUSTMENT
    amount           NUMERIC(12,2)  NOT NULL,   -- positive = revenue; negative = reversal
    reference_type   VARCHAR(30),               -- ORDER | EXPENSE | MANUAL
    reference_id     VARCHAR(50),
    notes            TEXT,
    created_by       BIGINT         REFERENCES users(id) ON DELETE SET NULL,
    created_at       TIMESTAMP      NOT NULL DEFAULT NOW(),
    effective_date   DATE           NOT NULL    -- accounting date (≠ created_at for post-close entries)
);

CREATE INDEX idx_txn_order_id       ON transactions(order_id);
CREATE INDEX idx_txn_effective_date ON transactions(effective_date);
CREATE INDEX idx_txn_type           ON transactions(transaction_type);

-- 2. Extend daily_reports with accounting snapshot fields --
-- All nullable so existing closed reports stay valid.
ALTER TABLE daily_reports
    ADD COLUMN IF NOT EXISTS gross_sales        NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS refunds_total      NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS adjustments_total  NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS net_sales          NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS total_transactions INTEGER;

-- 3. Backfill SALE transactions for historical orders ------
-- Creates one SALE per non-cancelled order that does not
-- already have a SALE transaction.  Idempotent (safe to re-run).
INSERT INTO transactions (
    transaction_code,
    order_id,
    transaction_type,
    amount,
    reference_type,
    reference_id,
    notes,
    created_by,
    created_at,
    effective_date
)
SELECT
    'SALE-' || o.id,
    o.id,
    'SALE',
    COALESCE(o.total, 0),
    'ORDER',
    o.id,
    'Backfilled from historical order',
    o.created_by,
    o.created_at,
    CAST(o.created_at AS DATE)
FROM orders o
WHERE o.status NOT IN ('CANCELLED')
  AND NOT EXISTS (
      SELECT 1
      FROM   transactions t
      WHERE  t.order_id = o.id
        AND  t.transaction_type = 'SALE'
  );
