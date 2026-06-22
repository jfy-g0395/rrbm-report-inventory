-- =============================================================================
-- V80: Cash on Hand ledger
-- =============================================================================
-- Append-only ledger of every physical cash movement. Cash on hand is the
-- running SUM(amount) over all rows -- it is NOT tied to the daily close and
-- never resets; it only changes when cash physically moves (orders paid in
-- cash, cash expenses, manual additions, bank deposits, adjustments).
--
-- entry_type describes the source of the movement; the SIGN of amount carries
-- the direction (+ inflow, - outflow). Reversals (cancelled cash order, voided
-- cash expense) are stored as new rows of the same entry_type with a negated
-- amount and an explanatory note -- the ledger stays append-only and auditable.

CREATE TABLE IF NOT EXISTS cash_ledger (
    id              BIGSERIAL     PRIMARY KEY,
    entry_type      VARCHAR(20)   NOT NULL,   -- OPENING_BALANCE | ADD_CASH | CASH_SALE |
                                              -- CASH_EXPENSE | DEPOSIT | ADJUSTMENT
    amount          NUMERIC(14,2) NOT NULL,   -- signed: + inflow, - outflow
    entry_date      DATE          NOT NULL,   -- business date (defaults today; deposit picks)
    reference_type  VARCHAR(20),              -- ORDER | EXPENSE | MANUAL
    reference_id    VARCHAR(50),              -- order id / expense id / null
    note            VARCHAR(500),
    created_by      BIGINT,
    created_by_name VARCHAR(120),
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cash_ledger_entry_date ON cash_ledger (entry_date);
CREATE INDEX IF NOT EXISTS idx_cash_ledger_ref        ON cash_ledger (reference_type, reference_id);

-- Cash-on-hand snapshot frozen onto each daily report at close time.
-- Nullable so existing closed reports remain valid.
ALTER TABLE daily_reports
    ADD COLUMN IF NOT EXISTS cash_on_hand NUMERIC(14,2);
