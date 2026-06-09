-- V14: Expenses module + role-based page access
-- ───────────────────────────────────────────────────────────────────

-- ── Expenses ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS expenses (
    id           BIGSERIAL     PRIMARY KEY,
    date         DATE          NOT NULL,
    admin_id     BIGINT        REFERENCES users(id) ON DELETE SET NULL,
    admin_name   VARCHAR(150)  NOT NULL,
    total_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at   TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS expense_items (
    id               BIGSERIAL     PRIMARY KEY,
    expense_id       BIGINT        NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
    item_description VARCHAR(300)  NOT NULL,
    amount           NUMERIC(10,2) NOT NULL DEFAULT 0
);

-- ── Role-based page access ───────────────────────────────────────────
-- Stored as a JSON array, e.g. '["orders","inventory","reports"]'
-- NULL means unrestricted (Super Admin bypass handled in code)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS allowed_pages TEXT DEFAULT NULL;

-- Default: all existing users get access to all pages
UPDATE users
SET allowed_pages = '["orders","inventory","receive-stocks","reports","delivery-reports","activity-log","employees","expenses"]'
WHERE allowed_pages IS NULL
  AND role != 'SUPER_ADMIN';
