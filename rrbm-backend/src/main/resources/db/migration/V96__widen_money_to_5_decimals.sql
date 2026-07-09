-- ============================================================================
-- V96 — Widen all monetary columns from scale 2 to scale 5
-- ============================================================================
-- Business need: amounts (unit prices/costs and everything derived from them)
-- must retain full precision — e.g. 45.12589 — instead of being rounded to two
-- decimals by the database. Previously every money column was numeric(p,2), so
-- PostgreSQL rounded any 3rd+ decimal on write, causing computation discrepancies.
--
-- This migration is ADDITIVE and NON-DESTRUCTIVE: widening a numeric column's
-- scale never loses existing data — stored values simply gain trailing zeros
-- (13.00 -> 13.00000). Precision is bumped by +3 alongside scale +3 so the
-- integer-digit capacity of every column is unchanged.
--
-- Rate columns (op_rate numeric(5,4)) are intentionally LEFT ALONE — they are
-- multipliers, not currency amounts.
--
-- Generated columns (delivery_log_items.line_total, payables.balance) cannot be
-- altered while their base columns change, so they are dropped, the base columns
-- widened, then the generated columns re-added at the new scale. They are
-- computed (STORED) so dropping loses no source data, and neither is mapped as a
-- JPA field, so the entity layer is unaffected.
-- ============================================================================

-- ── (10,2) -> (13,5) ────────────────────────────────────────────────────────
ALTER TABLE agent_commissions       ALTER COLUMN net_commission     TYPE numeric(13,5);
ALTER TABLE agent_commissions       ALTER COLUMN total_bonus        TYPE numeric(13,5);
ALTER TABLE agent_commissions       ALTER COLUMN total_deduction    TYPE numeric(13,5);
ALTER TABLE agent_commissions       ALTER COLUMN total_op           TYPE numeric(13,5);

ALTER TABLE commission_adjustments  ALTER COLUMN amount             TYPE numeric(13,5);

ALTER TABLE commission_entries      ALTER COLUMN base_price         TYPE numeric(13,5);
ALTER TABLE commission_entries      ALTER COLUMN op_amount          TYPE numeric(13,5);
ALTER TABLE commission_entries      ALTER COLUMN op_per_unit        TYPE numeric(13,5);

ALTER TABLE expense_items           ALTER COLUMN amount             TYPE numeric(13,5);

ALTER TABLE order_items             ALTER COLUMN base_price         TYPE numeric(13,5);
ALTER TABLE order_items             ALTER COLUMN op_amount          TYPE numeric(13,5);
ALTER TABLE order_items             ALTER COLUMN op_per_unit        TYPE numeric(13,5);
ALTER TABLE order_items             ALTER COLUMN unit_price         TYPE numeric(13,5);

ALTER TABLE orders                  ALTER COLUMN delivery_fee       TYPE numeric(13,5);
ALTER TABLE orders                  ALTER COLUMN voided_amount      TYPE numeric(13,5);

ALTER TABLE products                ALTER COLUMN agent_base_price   TYPE numeric(13,5);
ALTER TABLE products                ALTER COLUMN unit_cost          TYPE numeric(13,5);
ALTER TABLE products                ALTER COLUMN unit_price         TYPE numeric(13,5);

ALTER TABLE supplier_product_mapping ALTER COLUMN unit_cost         TYPE numeric(13,5);

-- ── (12,2) -> (15,5) ────────────────────────────────────────────────────────
ALTER TABLE daily_expense_logs      ALTER COLUMN total_amount       TYPE numeric(15,5);

ALTER TABLE daily_reports           ALTER COLUMN adjustments_total  TYPE numeric(15,5);
ALTER TABLE daily_reports           ALTER COLUMN cancelled_amount   TYPE numeric(15,5);
ALTER TABLE daily_reports           ALTER COLUMN gross_sales        TYPE numeric(15,5);
ALTER TABLE daily_reports           ALTER COLUMN net_sales          TYPE numeric(15,5);
ALTER TABLE daily_reports           ALTER COLUMN refunds_total      TYPE numeric(15,5);
ALTER TABLE daily_reports           ALTER COLUMN total_revenue      TYPE numeric(15,5);

ALTER TABLE expenses                ALTER COLUMN total_amount       TYPE numeric(15,5);

ALTER TABLE order_items             ALTER COLUMN subtotal           TYPE numeric(15,5);

ALTER TABLE orders                  ALTER COLUMN discount           TYPE numeric(15,5);
ALTER TABLE orders                  ALTER COLUMN subtotal           TYPE numeric(15,5);
ALTER TABLE orders                  ALTER COLUMN total              TYPE numeric(15,5);

ALTER TABLE transactions            ALTER COLUMN amount             TYPE numeric(15,5);

-- ── (14,2) -> (17,5) ────────────────────────────────────────────────────────
ALTER TABLE cash_ledger             ALTER COLUMN amount             TYPE numeric(17,5);

ALTER TABLE daily_reports           ALTER COLUMN cash_on_hand       TYPE numeric(17,5);
ALTER TABLE daily_reports           ALTER COLUMN total_expenses     TYPE numeric(17,5);
ALTER TABLE daily_reports           ALTER COLUMN unfulfilled_amount TYPE numeric(17,5);

-- ── (15,2) -> (18,5) ────────────────────────────────────────────────────────
ALTER TABLE po_items                ALTER COLUMN line_total         TYPE numeric(18,5);
ALTER TABLE po_items                ALTER COLUMN unit_price         TYPE numeric(18,5);

ALTER TABLE purchase_orders         ALTER COLUMN total_amount       TYPE numeric(18,5);

-- ── Generated columns: drop, widen base columns, re-add at new scale ─────────
-- delivery_log_items.line_total = received_qty * unit_cost
ALTER TABLE delivery_log_items DROP COLUMN line_total;
ALTER TABLE delivery_log_items ALTER COLUMN unit_cost TYPE numeric(15,5);
ALTER TABLE delivery_log_items
    ADD COLUMN line_total numeric(18,5)
    GENERATED ALWAYS AS (((received_qty)::numeric * unit_cost)) STORED;

-- payables.balance = total_amount - amount_paid
ALTER TABLE payables DROP COLUMN balance;
ALTER TABLE payables ALTER COLUMN total_amount TYPE numeric(15,5);
ALTER TABLE payables ALTER COLUMN amount_paid  TYPE numeric(15,5);
ALTER TABLE payables
    ADD COLUMN balance numeric(15,5)
    GENERATED ALWAYS AS ((total_amount - amount_paid)) STORED;
