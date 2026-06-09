-- V46: Add supplier-resolved fields to PO line items.
-- supplier_item_code and supplier_description are snapshot columns — they store the
-- supplier's own code and description as resolved at PO creation time from
-- supplier_product_mapping. The existing item_code column is preserved as-is (legacy).

ALTER TABLE po_items
    ADD COLUMN IF NOT EXISTS supplier_item_code   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS supplier_description TEXT;
