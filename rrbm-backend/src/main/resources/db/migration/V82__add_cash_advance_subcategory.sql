-- =============================================================================
-- V82: Add "Cash Advance" sub-category under PERSONNEL
-- =============================================================================
-- Adds one new sub-category to the PERSONNEL primary (parent code), shown on the
-- expense page under Personnel Costs. Uses WHERE NOT EXISTS for idempotency
-- (same pattern as V49 / V77). sort_order 70 continues the Personnel sequence
-- (existing subs run 10..60).

INSERT INTO expense_categories (name, parent_id, is_system_defined, requires_receipt, is_active, sort_order)
SELECT sub.name,
       (SELECT id FROM expense_categories WHERE code = 'PERSONNEL'),
       TRUE, FALSE, TRUE, sub.ord
FROM (VALUES
    ('Cash Advance', 70)
) AS sub(name, ord)
WHERE NOT EXISTS (
    SELECT 1 FROM expense_categories ec2
    WHERE ec2.parent_id = (SELECT id FROM expense_categories WHERE code = 'PERSONNEL')
      AND ec2.name = sub.name
);
