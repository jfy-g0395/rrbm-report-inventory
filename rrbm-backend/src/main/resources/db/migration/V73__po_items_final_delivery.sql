-- V73: Add is_final_delivery flag to po_items.
-- When a supplier cannot deliver the remaining ordered quantity, staff check
-- "Final Delivery" on the receive modal to close the PO line at the received
-- quantity rather than waiting for fulfilledQty to reach quantityOrdered.

ALTER TABLE po_items
  ADD COLUMN is_final_delivery BOOLEAN NOT NULL DEFAULT FALSE;
