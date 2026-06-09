-- V39: Schema additions for the void / cancel-for-replacement / return flow redesign.
--
-- order_items:
--   voided_quantity  — cumulative units voided per line item.
--                      Original quantity column is NEVER modified.
--                      Effective quantity per line = quantity - voided_quantity.
--
-- orders:
--   voided_amount          — running monetary total of all voids applied to this order.
--                            Effective order value = total - voided_amount.
--   replacement_order_id   — FK to the replacement order created after a
--                            cancel-for-replacement. Null on standard orders.
--   original_order_id      — FK back to the original cancelled order on the
--                            replacement order itself. Null on standard orders.
--   cancellation_type      — NULL until cancelled; then 'STANDARD' or 'REPLACEMENT'.
--                            Distinct from cancellation_reason (free text, human-readable).

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS voided_quantity INTEGER NOT NULL DEFAULT 0;

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS voided_amount         NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS replacement_order_id  VARCHAR(20)    NULL,
    ADD COLUMN IF NOT EXISTS original_order_id     VARCHAR(20)    NULL,
    ADD COLUMN IF NOT EXISTS cancellation_type     VARCHAR(20)    NULL;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_replacement_order
        FOREIGN KEY (replacement_order_id) REFERENCES orders (id) ON DELETE SET NULL;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_original_order
        FOREIGN KEY (original_order_id) REFERENCES orders (id) ON DELETE SET NULL;
