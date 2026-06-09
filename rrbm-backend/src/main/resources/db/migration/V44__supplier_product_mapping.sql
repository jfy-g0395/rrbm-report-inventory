-- V44: Supplier–product mapping table.
-- Links suppliers to products they can supply, with their own item codes,
-- descriptions, and unit costs. A product can have multiple supplier mappings;
-- at most one per supplier is marked is_preferred.

CREATE TABLE supplier_product_mapping (
    id                   BIGSERIAL       PRIMARY KEY,
    supplier_id          BIGINT          NOT NULL REFERENCES suppliers(id)  ON DELETE CASCADE,
    product_id           BIGINT          NOT NULL REFERENCES products(id)   ON DELETE CASCADE,
    supplier_item_code   VARCHAR(50),
    supplier_description TEXT,
    unit_cost            NUMERIC(10,2),
    is_preferred         BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP       NOT NULL DEFAULT now(),
    CONSTRAINT uq_supplier_product UNIQUE (supplier_id, product_id)
);

CREATE INDEX idx_spm_supplier_id ON supplier_product_mapping(supplier_id);
CREATE INDEX idx_spm_product_id  ON supplier_product_mapping(product_id);
