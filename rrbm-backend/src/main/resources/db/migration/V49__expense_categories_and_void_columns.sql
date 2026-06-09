-- V49: Expense categories + void-audit columns on expenses
-- ─────────────────────────────────────────────────────────────────────────────
-- Part 1: New expense_categories table
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS expense_categories (
    id                BIGSERIAL    PRIMARY KEY,
    -- Code is set only for primary (system) categories; sub-categories have NULL code.
    -- UNIQUE in PostgreSQL allows multiple NULLs, so this is safe.
    code              VARCHAR(30)  UNIQUE,
    name              VARCHAR(100) NOT NULL,
    -- NULL = top-level primary category; set = sub-category whose parent has that id.
    parent_id         BIGINT       REFERENCES expense_categories(id) ON DELETE RESTRICT,
    is_system_defined BOOLEAN      NOT NULL DEFAULT TRUE,
    requires_receipt  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_expense_categories_parent
    ON expense_categories(parent_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Part 2: Add category_id (nullable) to expense_items
-- Nullable so pre-existing free-text rows remain valid; required for new rows.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE expense_items
    ADD COLUMN IF NOT EXISTS category_id BIGINT REFERENCES expense_categories(id) ON DELETE SET NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Part 3: Add void / audit columns to expenses
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS is_voided   BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS voided_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS voided_by   BIGINT    REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS void_reason TEXT;

-- ─────────────────────────────────────────────────────────────────────────────
-- Part 4: Seed primary categories (8 rows)
-- sort_order multiples of 10 so new primaries can be inserted between them later.
-- ON CONFLICT DO NOTHING makes the seed idempotent.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO expense_categories (code, name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
VALUES
    ('FACILITY',   'Facility Costs',           NULL, TRUE, FALSE, TRUE,  10),
    ('UTILITY',    'Utilities',                NULL, TRUE, FALSE, TRUE,  20),
    ('SUPPLY',     'Supplies',                 NULL, TRUE, FALSE, TRUE,  30),
    ('INVENTORY',  'Inventory Replenishment',  NULL, TRUE, FALSE, TRUE,  40),
    ('OPERATIONS', 'Operational',              NULL, TRUE, FALSE, TRUE,  50),
    ('PERSONNEL',  'Personnel Costs',          NULL, TRUE, FALSE, TRUE,  60),
    ('SERVICES',   'Professional Services',    NULL, TRUE, FALSE, TRUE,  70),
    ('MISC',       'Miscellaneous',            NULL, TRUE, FALSE, TRUE,  80)
ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- Part 5: Seed sub-categories (32 rows, grouped by parent)
-- Parent is resolved at seed time via subselect on code.
-- Sub-categories have no code (NULL), only a name.
-- ─────────────────────────────────────────────────────────────────────────────

-- FACILITY (3 sub-categories)
INSERT INTO expense_categories (name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
SELECT sub.name,
       (SELECT id FROM expense_categories WHERE code = 'FACILITY'),
       TRUE, FALSE, TRUE, sub.ord
FROM (VALUES
    ('Monthly Office Rent',   10),
    ('Building Maintenance',  20),
    ('Parking Fees',          30)
) AS sub(name, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories ec2
    WHERE ec2.parent_id = (SELECT id FROM expense_categories WHERE code = 'FACILITY')
      AND ec2.name = sub.name
);

-- UTILITY (3 sub-categories)
INSERT INTO expense_categories (name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
SELECT sub.name,
       (SELECT id FROM expense_categories WHERE code = 'UTILITY'),
       TRUE, FALSE, TRUE, sub.ord
FROM (VALUES
    ('Electric Bill',        10),
    ('Internet Bill (ISP)',  20),
    ('Water Utility Bill',   30)
) AS sub(name, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories ec2
    WHERE ec2.parent_id = (SELECT id FROM expense_categories WHERE code = 'UTILITY')
      AND ec2.name = sub.name
);

-- SUPPLY (3 sub-categories)
INSERT INTO expense_categories (name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
SELECT sub.name,
       (SELECT id FROM expense_categories WHERE code = 'SUPPLY'),
       TRUE, FALSE, TRUE, sub.ord
FROM (VALUES
    ('Office Supplies',   10),
    ('Cleaning Supplies', 20),
    ('Kitchen Supplies',  30)
) AS sub(name, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories ec2
    WHERE ec2.parent_id = (SELECT id FROM expense_categories WHERE code = 'SUPPLY')
      AND ec2.name = sub.name
);

-- INVENTORY (5 sub-categories)
INSERT INTO expense_categories (name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
SELECT sub.name,
       (SELECT id FROM expense_categories WHERE code = 'INVENTORY'),
       TRUE, FALSE, TRUE, sub.ord
FROM (VALUES
    ('Packaging Tapes',  10),
    ('Bubble Wrap',      20),
    ('Boxes/Cartons',    30),
    ('Stickers/Labels',  40),
    ('Shrink Wrap',      50)
) AS sub(name, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories ec2
    WHERE ec2.parent_id = (SELECT id FROM expense_categories WHERE code = 'INVENTORY')
      AND ec2.name = sub.name
);

-- OPERATIONS (5 sub-categories)
INSERT INTO expense_categories (name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
SELECT sub.name,
       (SELECT id FROM expense_categories WHERE code = 'OPERATIONS'),
       TRUE, FALSE, TRUE, sub.ord
FROM (VALUES
    ('Delivery Vehicle Maintenance', 10),
    ('Gas Allowance',                20),
    ('Delivery Budget',              30),
    ('Shipping Fee',                 40),
    ('Fuel Reimbursement',           50)
) AS sub(name, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories ec2
    WHERE ec2.parent_id = (SELECT id FROM expense_categories WHERE code = 'OPERATIONS')
      AND ec2.name = sub.name
);

-- PERSONNEL (5 sub-categories)
INSERT INTO expense_categories (name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
SELECT sub.name,
       (SELECT id FROM expense_categories WHERE code = 'PERSONNEL'),
       TRUE, FALSE, TRUE, sub.ord
FROM (VALUES
    ('Employee Salary',     10),
    ('Food Allowance',      20),
    ('Daily Food Expense',  30),
    ('Overtime Pay',        40),
    ('Bonuses/Incentives',  50)
) AS sub(name, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories ec2
    WHERE ec2.parent_id = (SELECT id FROM expense_categories WHERE code = 'PERSONNEL')
      AND ec2.name = sub.name
);

-- SERVICES (4 sub-categories)
INSERT INTO expense_categories (name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
SELECT sub.name,
       (SELECT id FROM expense_categories WHERE code = 'SERVICES'),
       TRUE, FALSE, TRUE, sub.ord
FROM (VALUES
    ('Accounting/Bookkeeping Fees', 10),
    ('Professional Services',       20),
    ('Legal Fees',                  30),
    ('Software/Subscriptions',      40)
) AS sub(name, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories ec2
    WHERE ec2.parent_id = (SELECT id FROM expense_categories WHERE code = 'SERVICES')
      AND ec2.name = sub.name
);

-- MISC (4 sub-categories)
INSERT INTO expense_categories (name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
SELECT sub.name,
       (SELECT id FROM expense_categories WHERE code = 'MISC'),
       TRUE, FALSE, TRUE, sub.ord
FROM (VALUES
    ('Petty Cash',           10),
    ('Contingency Fund',     20),
    ('Bank Charges',         30),
    ('Miscellaneous Expense', 40)
) AS sub(name, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories ec2
    WHERE ec2.parent_id = (SELECT id FROM expense_categories WHERE code = 'MISC')
      AND ec2.name = sub.name
);
