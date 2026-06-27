package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for the side effects of a delivery receipt on
 * inventory stock, purchase-order fulfillment, and supplier payables.
 *
 * Two operations, exact inverses of each other:
 *   - {@link #applyEffects}   — add warehouse stock, match/advance PO items, create a PENDING payable.
 *   - {@link #reverseEffects} — subtract stock, roll back PO fulfillment, cancel the payable.
 *
 * Used by:
 *   - delivery creation  (ProductController.processDelivery)  → applyEffects
 *   - delivery cancel    (DeliveryReportController.cancel)    → reverseEffects
 *   - delivery item edit (DeliveryReportController.edit)      → reverseEffects then applyEffects
 *
 * Centralizing here guarantees an edit (reverse old items → re-apply new items) stays
 * consistent with how deliveries are originally received and cancelled.
 */
@Service
public class DeliveryStockService {

    private final ProductRepository       productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PoItemRepository        poItemRepository;
    private final PayableRepository       payableRepository;
    private final InventoryService        inventoryService;

    public DeliveryStockService(ProductRepository productRepository,
                                PurchaseOrderRepository purchaseOrderRepository,
                                PoItemRepository poItemRepository,
                                PayableRepository payableRepository,
                                InventoryService inventoryService) {
        this.productRepository       = productRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.poItemRepository        = poItemRepository;
        this.inventoryService        = inventoryService;
        this.payableRepository       = payableRepository;
    }

    /** Received units for a line: actual received if recorded, else the ordered quantity. */
    private static int receivedOf(DeliveryLogItem item) {
        return item.getReceivedQty() > 0 ? item.getReceivedQty() : item.getQuantity();
    }

    // ── Apply: add stock, advance PO fulfillment, create payable ─────────────────

    /**
     * Apply a delivery's effects from its persisted line items. Mirrors the original
     * receive flow: per-item warehouse stock add (+ RESTOCK movement), PO auto-match
     * (linked PO when a poNumber is set, else FIFO across open PO items), PO status
     * recompute, and a fresh PENDING payable for the delivery's total cost.
     */
    @Transactional
    public void applyEffects(DeliveryLog log, Long userId) {
        BigDecimal totalCost = BigDecimal.ZERO;

        // ── 1. Stock add + movement log ──────────────────────────────────────
        for (DeliveryLogItem item : log.getItems()) {
            if (item.getProductId() == null) continue;
            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product == null) continue;
            int received = receivedOf(item);
            if (received <= 0) continue;
            String wh = item.getWarehouse() != null ? item.getWarehouse().toLowerCase() : "wh1";
            switch (wh) {
                case "wh1": product.setStockWh1(product.getStockWh1() + received); break;
                case "wh2": product.setStockWh2(product.getStockWh2() + received); break;
                case "wh3": product.setStockWh3(product.getStockWh3() + received); break;
            }
            productRepository.save(product);
            inventoryService.logMovement(product.getId(), "RESTOCK", wh, received,
                    log.getReceiptNumber(),
                    "Delivery receipt " + log.getReceiptNumber() + " — " + log.getSupplierName(),
                    userId);

            BigDecimal uc = item.getUnitCost() != null ? item.getUnitCost() : BigDecimal.ZERO;
            totalCost = totalCost.add(uc.multiply(BigDecimal.valueOf(received)));
        }

        // ── 2. PO auto-match (linked PO when set, else FIFO) ─────────────────
        Set<Long> touchedPoIds = new HashSet<>();
        PurchaseOrder linkedPo = (log.getPoNumber() != null && !log.getPoNumber().isBlank())
                ? purchaseOrderRepository.findByPoNumber(log.getPoNumber()).orElse(null)
                : null;

        for (DeliveryLogItem drItem : log.getItems()) {
            if (drItem.getProductId() == null) continue;
            Product prod = productRepository.findById(drItem.getProductId()).orElse(null);
            if (prod == null) continue;

            PoItem poItem = null;
            if (linkedPo != null) {
                if (prod.getItemCode() != null && !prod.getItemCode().isBlank()) {
                    poItem = linkedPo.getItems().stream()
                            .filter(i -> !Boolean.TRUE.equals(i.getIsFulfilled())
                                    && prod.getItemCode().equalsIgnoreCase(i.getItemCode()))
                            .findFirst().orElse(null);
                }
                if (poItem == null && prod.getItemCode() != null && !prod.getItemCode().isBlank()) {
                    poItem = linkedPo.getItems().stream()
                            .filter(i -> !Boolean.TRUE.equals(i.getIsFulfilled())
                                    && prod.getItemCode().equalsIgnoreCase(i.getSupplierItemCode()))
                            .findFirst().orElse(null);
                }
                if (poItem == null && prod.getName() != null) {
                    poItem = linkedPo.getItems().stream()
                            .filter(i -> !Boolean.TRUE.equals(i.getIsFulfilled())
                                    && prod.getName().equalsIgnoreCase(i.getItemDescription()))
                            .findFirst().orElse(null);
                }
            } else {
                if (prod.getItemCode() != null && !prod.getItemCode().isBlank()) {
                    List<PoItem> open = poItemRepository.findByItemCodeAndIsFulfilledFalseOrderByIdAsc(prod.getItemCode());
                    if (!open.isEmpty()) poItem = open.get(0);
                }
                if (poItem == null && prod.getItemCode() != null && !prod.getItemCode().isBlank()) {
                    List<PoItem> open = poItemRepository.findBySupplierItemCodeAndIsFulfilledFalseOrderByIdAsc(prod.getItemCode());
                    if (!open.isEmpty()) poItem = open.get(0);
                }
                if (poItem == null && prod.getName() != null) {
                    List<PoItem> open = poItemRepository.findByItemDescriptionIgnoreCaseAndIsFulfilledFalseOrderByIdAsc(prod.getName());
                    if (!open.isEmpty()) poItem = open.get(0);
                }
            }
            if (poItem == null) continue;

            int received = receivedOf(drItem);
            if (received <= 0) continue;
            int newFulfilled = (poItem.getFulfilledQty() != null ? poItem.getFulfilledQty() : 0) + received;
            poItem.setFulfilledQty(newFulfilled);
            poItem.setDrNumber(log.getReceiptNumber());
            if (newFulfilled >= (poItem.getQuantityOrdered() != null ? poItem.getQuantityOrdered() : 1)) {
                poItem.setIsFulfilled(true);
            }
            poItemRepository.save(poItem);
            touchedPoIds.add(poItem.getPurchaseOrder().getId());
        }

        for (Long poId : touchedPoIds) {
            purchaseOrderRepository.findByIdWithItems(poId).ifPresent(this::recalcPoStatus);
        }

        // ── 3. Fresh PENDING payable for this delivery ───────────────────────
        Payable payable = new Payable();
        payable.setDeliveryLogId(log.getId());
        payable.setReceiptNumber(log.getReceiptNumber());
        payable.setSupplierName(log.getSupplierName());
        payable.setTotalAmount(totalCost);
        payable.setStatus("PENDING");
        payable.setCreatedBy(log.getEncodedByName());
        payableRepository.save(payable);
    }

    // ── Reverse: subtract stock, roll back PO fulfillment, cancel payable ────────

    /**
     * Reverse a delivery's effects (used by cancel, and as the first half of an edit).
     * Subtracts the received stock (floored at 0), rolls back the matching PO items'
     * fulfilledQty / isFulfilled / drNumber, recomputes PO status, and marks the
     * delivery's payable(s) CANCELLED. Idempotent-safe: caps at current values.
     */
    @Transactional
    public void reverseEffects(DeliveryLog log, Long userId) {
        // ── 1. Reverse warehouse stock (+ movement log) ──────────────────────
        for (DeliveryLogItem item : log.getItems()) {
            if (item.getProductId() == null) continue;
            Product p = productRepository.findById(item.getProductId()).orElse(null);
            if (p == null) continue;
            int qty = receivedOf(item);
            if (qty <= 0) continue;
            String wh = item.getWarehouse() != null ? item.getWarehouse() : "wh1";
            switch (wh) {
                case "wh1": p.setStockWh1(Math.max(0, p.getStockWh1() - qty)); break;
                case "wh2": p.setStockWh2(Math.max(0, p.getStockWh2() - qty)); break;
                case "wh3": p.setStockWh3(Math.max(0, p.getStockWh3() - qty)); break;
            }
            productRepository.save(p);
            // CORRECTION_OUT is the allowed movement type for a stock-reducing correction
            // (see chk_movement_type). Reversing a received delivery removes the stock it added.
            inventoryService.logMovement(p.getId(), "CORRECTION_OUT", wh, -qty,
                    log.getReceiptNumber(),
                    "Reversal of delivery receipt " + log.getReceiptNumber(),
                    userId);
        }

        // ── 2. Reverse PO item fulfillment ───────────────────────────────────
        try {
            Set<Long> touchedPoIds = new HashSet<>();
            if (log.getPoNumber() != null && !log.getPoNumber().isBlank()) {
                PurchaseOrder linkedPo = purchaseOrderRepository.findByPoNumber(log.getPoNumber()).orElse(null);
                if (linkedPo != null) {
                    for (DeliveryLogItem drItem : log.getItems()) {
                        PoItem poItem = findPoItemForDrItem(drItem, linkedPo.getItems());
                        if (poItem == null) continue;
                        int received = receivedOf(drItem);
                        if (received <= 0) continue;
                        reversePoItem(poItem, received);
                        poItemRepository.save(poItem);
                        touchedPoIds.add(linkedPo.getId());
                    }
                }
            } else {
                List<PoItem> fifoMatched = poItemRepository.findByDrNumberOrderByIdAsc(log.getReceiptNumber());
                for (PoItem poItem : fifoMatched) {
                    int received = resolveReceivedQtyForPoItem(poItem, log.getItems());
                    if (received <= 0) continue;
                    reversePoItem(poItem, received);
                    poItemRepository.save(poItem);
                    touchedPoIds.add(poItem.getPurchaseOrder().getId());
                }
            }
            for (Long poId : touchedPoIds) {
                purchaseOrderRepository.findByIdWithItems(poId).ifPresent(this::recalcPoStatus);
            }
        } catch (Exception e) {
            System.err.println("Warning: PO item reversal failed for DR "
                    + log.getReceiptNumber() + ": " + e.getMessage());
        }

        // ── 3. Cancel the associated payable(s) ──────────────────────────────
        try {
            payableRepository.findByDeliveryLogId(log.getId()).forEach(payable -> {
                payable.setStatus("CANCELLED");
                payableRepository.save(payable);
            });
        } catch (Exception e) {
            System.err.println("Warning: payable cancellation failed for DR "
                    + log.getReceiptNumber() + ": " + e.getMessage());
        }
    }

    /** True if the delivery's payable is already settled (PAID) — edits/cancels must be blocked. */
    public boolean hasSettledPayable(DeliveryLog log) {
        return payableRepository.findByDeliveryLogId(log.getId()).stream()
                .anyMatch(p -> "PAID".equalsIgnoreCase(p.getStatus()));
    }

    // ── Helpers (ported from DeliveryReportController) ───────────────────────────

    private PoItem findPoItemForDrItem(DeliveryLogItem drItem, List<PoItem> poItems) {
        if (drItem.getProductId() == null) return null;
        Product prod = productRepository.findById(drItem.getProductId()).orElse(null);
        if (prod == null) return null;
        if (prod.getItemCode() != null && !prod.getItemCode().isBlank()) {
            PoItem match = poItems.stream()
                    .filter(i -> prod.getItemCode().equalsIgnoreCase(i.getItemCode()))
                    .findFirst().orElse(null);
            if (match != null) return match;
            match = poItems.stream()
                    .filter(i -> prod.getItemCode().equalsIgnoreCase(i.getSupplierItemCode()))
                    .findFirst().orElse(null);
            if (match != null) return match;
        }
        if (prod.getName() != null) {
            return poItems.stream()
                    .filter(i -> prod.getName().equalsIgnoreCase(i.getItemDescription()))
                    .findFirst().orElse(null);
        }
        return null;
    }

    private int resolveReceivedQtyForPoItem(PoItem poItem, List<DeliveryLogItem> drItems) {
        for (DeliveryLogItem drItem : drItems) {
            if (drItem.getProductId() == null) continue;
            Product prod = productRepository.findById(drItem.getProductId()).orElse(null);
            if (prod == null) continue;
            boolean byCode = prod.getItemCode() != null && !prod.getItemCode().isBlank()
                    && prod.getItemCode().equalsIgnoreCase(poItem.getItemCode());
            boolean bySupplierCode = prod.getItemCode() != null && !prod.getItemCode().isBlank()
                    && prod.getItemCode().equalsIgnoreCase(poItem.getSupplierItemCode());
            boolean byName = prod.getName() != null
                    && prod.getName().equalsIgnoreCase(poItem.getItemDescription());
            if (byCode || bySupplierCode || byName) return receivedOf(drItem);
        }
        return 0;
    }

    private void reversePoItem(PoItem poItem, int qty) {
        int newFulfilled = Math.max(0,
                (poItem.getFulfilledQty() != null ? poItem.getFulfilledQty() : 0) - qty);
        poItem.setFulfilledQty(newFulfilled);
        poItem.setIsFulfilled(newFulfilled > 0
                && newFulfilled >= (poItem.getQuantityOrdered() != null ? poItem.getQuantityOrdered() : 1));
        if (newFulfilled == 0) poItem.setDrNumber(null);
    }

    private void recalcPoStatus(PurchaseOrder po) {
        boolean allFulfilled = po.getItems().stream()
                .allMatch(i -> Boolean.TRUE.equals(i.getIsFulfilled()));
        boolean anyReceived = po.getItems().stream()
                .anyMatch(i -> i.getFulfilledQty() != null && i.getFulfilledQty() > 0);
        if (allFulfilled) {
            po.setStatus("COMPLETE");
        } else if (anyReceived) {
            po.setStatus("PARTIALLY_RECEIVED");
        } else {
            po.setStatus("INCOMPLETE");
        }
        purchaseOrderRepository.save(po);
    }
}
