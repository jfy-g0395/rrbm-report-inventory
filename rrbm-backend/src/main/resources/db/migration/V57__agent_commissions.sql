CREATE TABLE agent_commissions (
    id              BIGSERIAL PRIMARY KEY,
    agent_id        BIGINT NOT NULL REFERENCES agents(id),
    period_id       BIGINT NOT NULL REFERENCES commission_periods(id),
    period_code     VARCHAR(30)    NOT NULL,
    start_date      DATE           NOT NULL,
    end_date        DATE           NOT NULL,
    total_op        NUMERIC(10,2)  NOT NULL DEFAULT 0,
    total_bonus     NUMERIC(10,2)  NOT NULL DEFAULT 0,
    total_deduction NUMERIC(10,2)  NOT NULL DEFAULT 0,
    net_commission  NUMERIC(10,2)  NOT NULL DEFAULT 0,
    released_at     TIMESTAMPTZ    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    UNIQUE (agent_id, period_id)
);
