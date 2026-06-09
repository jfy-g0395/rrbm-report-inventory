-- V58: Payment tracking columns on agent_commissions (SPEC §2.5 step 4).
-- After a period is RELEASED, an authorised user records payment per agent:
-- paymentMethod, paymentReference, paymentDate → status transitions RELEASED → PAID.
ALTER TABLE agent_commissions
    ADD COLUMN IF NOT EXISTS status            VARCHAR(20)   NOT NULL DEFAULT 'RELEASED',
    ADD COLUMN IF NOT EXISTS payment_method    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS payment_reference VARCHAR(100),
    ADD COLUMN IF NOT EXISTS payment_date      DATE,
    ADD COLUMN IF NOT EXISTS paid_by           BIGINT REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS paid_at           TIMESTAMPTZ;
