-- A4: Commission bonus/deduction adjustment layer

CREATE TABLE commission_adjustments (
    id               BIGSERIAL PRIMARY KEY,
    period_id        BIGINT NOT NULL REFERENCES commission_periods(id),
    agent_id         BIGINT NOT NULL REFERENCES agents(id),
    adjustment_type  VARCHAR(20) NOT NULL,  -- BONUS / DEDUCTION
    amount           NUMERIC(10,2) NOT NULL,
    reason           TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       BIGINT REFERENCES users(id)
);
