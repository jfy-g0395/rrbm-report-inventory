-- =============================================================================
-- V101: Return / Replace events (unified return + replacement flow)
-- =============================================================================
-- Backs the new single "Return / Replace" flow that replaces the four scattered
-- paths (Process Return, Issue Replacement, Create Replacement, Cancel-for-
-- replacement) and the retired "Correct Recorded Item". Each submission of the
-- new modal writes ONE return_events row plus one return_event_items row per
-- affected line. This gives a clean audit ("what was returned/replaced/corrected
-- when, by whom") and — crucially — a place to track a refund that is OWED but
-- not yet paid, so the new "To Refund" tab on the Collections page can list them.
--
-- Money follows the numeric(_,5) convention (V96). event_type distinguishes a
-- physical RETURN (goods came back → restock/refund logic runs) from a data-only
-- CORRECTION (wrong item recorded, nothing physically returned). Whether a
-- replacement order was created is captured by replacement_order_id (nullable);
-- multiple replacements over time are simply multiple rows — the 1-to-many link
-- already exists via orders.original_order_id (the old single-replacement guard
-- is being removed in code, no schema change needed for that).
--
-- New tables only; NO existing data is touched and NO existing table is altered.
-- order_id / replacement_order_id are kept as plain VARCHAR (no FK), matching how
-- transactions.order_id and inventory_movements.reference_id already reference
-- orders in this schema.
-- =============================================================================

CREATE TABLE IF NOT EXISTS return_events (
    id                   BIGSERIAL     PRIMARY KEY,
    order_id             VARCHAR(20)   NOT NULL,          -- the original order acted on
    event_type          VARCHAR(20)    NOT NULL,          -- RETURN (physical) | CORRECTION (data-only)
    reason               VARCHAR(500),
    refund_owed          NUMERIC(15,5) NOT NULL DEFAULT 0,-- computed excess payment for this event
    refund_status        VARCHAR(10)   NOT NULL DEFAULT 'NONE', -- NONE | OWED | REFUNDED
    refunded_amount      NUMERIC(15,5),
    refunded_at          TIMESTAMP,
    replacement_order_id VARCHAR(20),                     -- new order created for this event, if any
    created_by           BIGINT,
    created_by_name      VARCHAR(120),
    created_at           TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_return_event_type    CHECK (event_type   IN ('RETURN','CORRECTION')),
    CONSTRAINT chk_return_refund_status CHECK (refund_status IN ('NONE','OWED','REFUNDED'))
);

CREATE TABLE IF NOT EXISTS return_event_items (
    id                BIGSERIAL    PRIMARY KEY,
    return_event_id   BIGINT       NOT NULL REFERENCES return_events(id) ON DELETE CASCADE,
    order_item_id     BIGINT,                             -- original line affected (null if replacement-only)
    product_id        BIGINT,
    product_name      VARCHAR(200) NOT NULL,
    returned_qty      INTEGER      NOT NULL DEFAULT 0,    -- units taken off the original line
    sellable_qty      INTEGER      NOT NULL DEFAULT 0,    -- of returned_qty, put back to stock
    rejected_qty      INTEGER      NOT NULL DEFAULT 0,    -- of returned_qty, scrapped (no restock)
    restock_warehouse VARCHAR(10),                        -- where sellable_qty went back
    CONSTRAINT chk_return_event_item_qty
        CHECK (returned_qty >= 0 AND sellable_qty >= 0 AND rejected_qty >= 0
               AND sellable_qty + rejected_qty <= returned_qty)
);

CREATE INDEX IF NOT EXISTS idx_return_events_order         ON return_events (order_id);
CREATE INDEX IF NOT EXISTS idx_return_events_refund_status ON return_events (refund_status);
CREATE INDEX IF NOT EXISTS idx_return_event_items_event    ON return_event_items (return_event_id);
