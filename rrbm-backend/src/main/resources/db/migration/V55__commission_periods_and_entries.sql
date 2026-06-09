-- A3: Commission engine schema

CREATE TABLE commission_periods (
    id           BIGSERIAL PRIMARY KEY,
    period_code  VARCHAR(30) NOT NULL UNIQUE,
    start_date   DATE NOT NULL,
    end_date     DATE NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    notes        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by   BIGINT REFERENCES users(id),
    closed_at    TIMESTAMPTZ,
    closed_by    BIGINT REFERENCES users(id),
    released_at  TIMESTAMPTZ,
    released_by  BIGINT REFERENCES users(id)
);

CREATE TABLE commission_entries (
    id            BIGSERIAL PRIMARY KEY,
    period_id     BIGINT NOT NULL REFERENCES commission_periods(id),
    agent_id      BIGINT NOT NULL REFERENCES agents(id),
    order_id      VARCHAR(20) REFERENCES orders(id),
    order_item_id BIGINT REFERENCES order_items(id),
    order_date    DATE NOT NULL,
    product_name  VARCHAR(200),
    quantity      INT NOT NULL DEFAULT 1,
    base_price    NUMERIC(10,2),
    op_rate       NUMERIC(5,4),
    op_amount     NUMERIC(10,2) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
