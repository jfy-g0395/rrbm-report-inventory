-- Purchase Orders module

CREATE TABLE IF NOT EXISTS purchase_orders (
    id                   BIGSERIAL    PRIMARY KEY,
    po_number            VARCHAR(11)  NOT NULL,
    vendor_name          VARCHAR(255) NOT NULL,
    vendor_contact       VARCHAR(255),
    vendor_address       TEXT,
    ship_to_name         VARCHAR(255),
    ship_to_contact      VARCHAR(255),
    ship_to_address      TEXT,
    notes                TEXT,
    vat_type             VARCHAR(20)  NOT NULL DEFAULT 'EXCLUSIVE',
    shipping_arrangement VARCHAR(100),
    status               VARCHAR(20)  NOT NULL DEFAULT 'INCOMPLETE',
    created_by           VARCHAR(255),
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    total_amount         NUMERIC(15,2)         DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_po_number ON purchase_orders(po_number);

CREATE TABLE IF NOT EXISTS po_items (
    id               BIGSERIAL    PRIMARY KEY,
    po_id            BIGINT       NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    item_code        VARCHAR(50),
    item_description VARCHAR(500) NOT NULL,
    quantity_ordered INTEGER      NOT NULL DEFAULT 1,
    unit_price       NUMERIC(15,2)         DEFAULT 0,
    line_total       NUMERIC(15,2)         DEFAULT 0,
    fulfilled_qty    INTEGER               DEFAULT 0,
    dr_number        VARCHAR(100),
    is_fulfilled     BOOLEAN               DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_po_items_po_id     ON po_items(po_id);
CREATE INDEX IF NOT EXISTS idx_po_items_item_code ON po_items(item_code) WHERE item_code IS NOT NULL;
