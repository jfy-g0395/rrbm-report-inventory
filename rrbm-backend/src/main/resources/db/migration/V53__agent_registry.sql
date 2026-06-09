-- V53: Create agents table; add agent_id FK to orders.
--
-- agent_code is generated in the service layer (AGENT-YYYY-NNNN).
-- agent_id on orders is nullable — existing orders keep agent_name as plain text;
-- only new orders created via A2 will populate this FK.

CREATE TABLE agents (
    id                BIGSERIAL       PRIMARY KEY,
    agent_code        VARCHAR(20)     NOT NULL UNIQUE,
    full_name         VARCHAR(150)    NOT NULL,
    contact_number    VARCHAR(50)     NOT NULL,
    email             VARCHAR(150),
    territory         VARCHAR(100)    NOT NULL,
    status            VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    registration_date DATE            NOT NULL DEFAULT CURRENT_DATE,
    notes             TEXT,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by        BIGINT          REFERENCES users(id)
);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS agent_id BIGINT REFERENCES agents(id);
