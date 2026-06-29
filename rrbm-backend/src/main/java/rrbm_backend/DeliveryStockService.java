package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

            // Resolve which PO line this row fulfils, most-specific first:
            PoItem poItem = null;
            // 0a. Explicit per-line PO item — exact; lets ONE delivery receipt fulfil lines
            //     across MULTIPLE POs, including the same product on two different POs.
            if (drItem.getPoItemId() != null) {
                poItem = poItemRepository.findById(drItem.getPoItemId()).orElse(null);
            }
            // 0b. Per-line PO number — match the open line within that one PO.
            if (poItem == null && drItem.getPoNumber() != null && !drItem.getPoNumber().isBlank()) {
                PurchaseOrder linePo = purchaseOrderRepository.findByPoNumber(drItem.getPoNumber().trim()).orElse(null);
                if (linePo != null) poItem = matchOpenPoItem(linePo, prod);
            }
            // 1. Request-level linked PO (top "linked PO" dropdown) — existing behaviour.
            if (poItem == null && linkedPo != null) {
                poItem = matchOpenPoItem(linkedPo, prod);
            }
            // 2. Global FIFO fallback — only when no PO context was given at all.
            if (poItem == null && linkedPo == null
                    && drItem.getPoItemId() == null
                    && (drItem.getPoNumber() == null || drItem.getPoNumber().isBlank())) {
                poItem = fifoMatchOpenPoItem(prod);
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

    // ── Edit: apply ONLY the net change between old and new item sets ─────────────

    /**
     * Apply only the <b>difference</b> between a delivery's old and new item sets, keyed by
     * (productId, warehouse). Lines that are unchanged are left completely untouched — no stock
     * movement, no PO churn — so editing one line can never disturb a sibling line that has been
     * sold since the delivery was received. This replaces the old "reverse everything then
     * re-apply everything" edit path, whose zero-floor clamp silently corrupted untouched lines
     * that had been partly sold (see DR 208390 incident).
     *
     * <p>For each changed key: a positive delta adds stock (RESTOCK) and advances PO fulfilment;
     * a negative delta removes stock (CORRECTION_OUT, floored at 0) and rolls back PO fulfilment.
     * The delivery's PENDING payable is updated in place to the new item total (never cancelled
     * and re-created, so a partial payment history is preserved).
     */
    @Transactional
    public void applyItemEdit(DeliveryLog log, List<DeliveryLogItem> oldItems,
                              List<DeliveryLogItem> newItems, Long userId) {
        // Net received-qty delta per (productId|warehouse).
        Map<String, Integer> delta = new LinkedHashMap<>();
        Map<String, Long>    keyProduct = new HashMap<>();
        Map<String, String>  keyWarehouse = new HashMap<>();
        for (DeliveryLogItem it : oldItems) {
            if (it.getProductId() == null) continue;
            String wh = it.getWarehouse() == null ? "wh1" : it.getWarehouse();
            String k = it.getProductId() + "|" + wh;
            delta.merge(k, -receivedOf(it), Integer::sum);
            keyProduct.put(k, it.getProductId());
            keyWarehouse.put(k, wh);
        }
        for (DeliveryLogItem it : newItems) {
            if (it.getProductId() == null) continue;
            String wh = it.getWarehouse() == null ? "wh1" : it.getWarehouse();
            String k = it.getProductId() + "|" + wh;
            delta.merge(k, receivedOf(it), Integer::sum);
            keyProduct.put(k, it.getProductId());
            keyWarehouse.put(k, wh);
        }

        Set<Long> touchedPoIds = new HashSet<>();
        PurchaseOrder linkedPo = (log.getPoNumber() != null && !log.getPoNumber().isBlank())
                ? purchaseOrderRepository.findByPoNumber(log.getPoNumber()).orElse(null) : null;

        for (Map.Entry<String, Integer> e : delta.entrySet()) {
            int d = e.getValue();
            if (d == 0) continue;   // unchanged line — never touch its stock or PO
            Product product = productRepository.findById(keyProduct.get(e.getKey())).orElse(null);
            if (product == null) continue;
            String wh = keyWarehouse.get(e.getKey());

            // ── stock ──
            int cur = stockOf(product, wh);
            setStockOf(product, wh, d > 0 ? cur + d : Math.max(0, cur + d));
            productRepository.save(product);
            inventoryService.logMovement(product.getId(), d > 0 ? "RESTOCK" : "CORRECTION_OUT", wh, d,
                    log.getReceiptNumber(),
                    "Delivery edit — receipt " + log.getReceiptNumber()
                            + " (" + (d > 0 ? "+" + d : String.valueOf(d)) + " " + product.getName() + ")",
                    userId);

            // ── PO fulfilment ──
            if (d > 0) {
                PoItem poItem = linkedPo != null ? matchOpenPoItem(linkedPo, product) : fifoMatchOpenPoItem(product);
                if (poItem != null) {
                    int nf = (poItem.getFulfilledQty() != null ? poItem.getFulfilledQty() : 0) + d;
                    poItem.setFulfilledQty(nf);
                    poItem.setDrNumber(log.getReceiptNumber());
                    if (nf >= (poItem.getQuantityOrdered() != null ? poItem.getQuantityOrdered() : 1)) {
                        poItem.setIsFulfilled(true);
                    }
                    poItemRepository.save(poItem);
                    touchedPoIds.add(poItem.getPurchaseOrder().getId());
                }
            } else {
                PoItem poItem = findFulfilledPoItem(log, linkedPo, product);
                if (poItem != null) {
                    reversePoItem(poItem, -d);   // -d is the positive magnitude removed
                    poItemRepository.save(poItem);
                    touchedPoIds.add(poItem.getPurchaseOrder().getId());
                }
            }
        }
        for (Long poId : touchedPoIds) {
            purchaseOrderRepository.findByIdWithItems(poId).ifPresent(this::recalcPoStatus);
        }

        // ── payable: update the existing (non-cancelled) one in place to the new total ──
        BigDecimal newTotal = BigDecimal.ZERO;
        for (DeliveryLogItem it : newItems) {
            BigDecimal uc = it.getUnitCost() != null ? it.getUnitCost() : BigDecimal.ZERO;
            newTotal = newTotal.add(uc.multiply(BigDecimal.valueOf(receivedOf(it))));
        }
        Payable active = payableRepository.findByDeliveryLogId(log.getId()).stream()
                .filter(p -> !"CANCELLED".equalsIgnoreCase(p.getStatus()))
                .findFirst().orElse(null);
        if (active != null) {
            active.setTotalAmount(newTotal);
            payableRepository.save(active);
        } else {
            Payable p = new Payable();
            p.setDeliveryLogId(log.getId());
            p.setReceiptNumber(log.getReceiptNumber());
            p.setSupplierName(log.getSupplierName());
            p.setTotalAmount(newTotal);
            p.setStatus("PENDING");
            p.setCreatedBy(log.getEncodedByName());
            payableRepository.save(p);
        }
    }

    private int stockOf(Product p, String wh) {
        switch (wh) {
            case "wh2": return p.getStockWh2();
            case "wh3": return p.getStockWh3();
            default:    return p.getStockWh1();
        }
    }

    private void setStockOf(Product p, String wh, int val) {
        switch (wh) {
            case "wh2": p.setStockWh2(val); break;
            case "wh3": p.setStockWh3(val); break;
            default:    p.setStockWh1(val); break;
        }
    }

    /** The PO line this delivery advanced for a product (matched within the linked PO, else by DR stamp). */
    private PoItem findFulfilledPoItem(DeliveryLog log, PurchaseOrder linkedPo, Product product) {
        if (linkedPo != null) {
            PoItem m = findPoItemForProduct(linkedPo.getItems(), product);
            if (m != null) return m;
        }
        for (PoItem pi : poItemRepository.findByDrNumberOrderByIdAsc(log.getReceiptNumber())) {
            if (productMatchesPoItem(product, pi)) return pi;
        }
        return null;
    }

    private PoItem findPoItemForProduct(List<PoItem> poItems, Product prod) {
        return poItems.stream().filter(pi -> productMatchesPoItem(prod, pi)).findFirst().orElse(null);
    }

    private boolean productMatchesPoItem(Product prod, PoItem pi) {
        if (prod.getItemCode() != null && !prod.getItemCode().isBlank()
                && (prod.getItemCode().equalsIgnoreCase(pi.getItemCode())
                 || prod.getItemCode().equalsIgnoreCase(pi.getSupplierItemCode()))) return true;
        return prod.getName() != null && prod.getName().equalsIgnoreCase(pi.getItemDescription());
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
            Set<Long> handledPoItemIds = new HashSet<>();

            // Pass A — explicit per-line PO tags: roll back the EXACT line by that line's
            // received qty. Handles the same product on two POs under one DR precisely.
            for (DeliveryLogItem drItem : log.getItems()) {
                if (drItem.getPoItemId() == null) continue;
                PoItem poItem = poItemRepository.findById(drItem.getPoItemId()).orElse(null);
                if (poItem == null) continue;
                int received = receivedOf(drItem);
                if (received <= 0) continue;
                reversePoItem(poItem, received);
                poItemRepository.save(poItem);
                handledPoItemIds.add(poItem.getId());
                touchedPoIds.add(poItem.getPurchaseOrder().getId());
            }

            // Pass B — untagged lines: existing linked-PO / FIFO behaviour, skipping any
            // line already handled in Pass A. No tags present → identical to the old path.
            boolean hasUntagged = log.getItems().stream().anyMatch(i -> i.getPoItemId() == null);
            if (hasUntagged) {
                if (log.getPoNumber() != null && !log.getPoNumber().isBlank()) {
                    PurchaseOrder linkedPo = purchaseOrderRepository.findByPoNumber(log.getPoNumber()).orElse(null);
                    if (linkedPo != null) {
                        for (DeliveryLogItem drItem : log.getItems()) {
                            if (drItem.getPoItemId() != null) continue;
                            PoItem poItem = findPoItemForDrItem(drItem, linkedPo.getItems());
                            if (poItem == null || handledPoItemIds.contains(poItem.getId())) continue;
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
                        if (handledPoItemIds.contains(poItem.getId())) continue;
                        int received = resolveReceivedQtyForPoItem(poItem, log.getItems());
                        if (received <= 0) continue;
                        reversePoItem(poItem, received);
                        poItemRepository.save(poItem);
                        touchedPoIds.add(poItem.getPurchaseOrder().getId());
                    }
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

    /**
     * First OPEN (unfulfilled) line within a specific PO matching this product —
     * legacy itemCode, then supplier itemCode, then product name vs item description.
     */
    private PoItem matchOpenPoItem(PurchaseOrder po, Product prod) {
        PoItem poItem = null;
        if (prod.getItemCode() != null && !prod.getItemCode().isBlank()) {
            poItem = po.getItems().stream()
                    .filter(i -> !Boolean.TRUE.equals(i.getIsFulfilled())
                            && prod.getItemCode().equalsIgnoreCase(i.getItemCode()))
                    .findFirst().orElse(null);
        }
        if (poItem == null && prod.getItemCode() != null && !prod.getItemCode().isBlank()) {
            poItem = po.getItems().stream()
                    .filter(i -> !Boolean.TRUE.equals(i.getIsFulfilled())
                            && prod.getItemCode().equalsIgnoreCase(i.getSupplierItemCode()))
                    .findFirst().orElse(null);
        }
        if (poItem == null && prod.getName() != null) {
            poItem = po.getItems().stream()
                    .filter(i -> !Boolean.TRUE.equals(i.getIsFulfilled())
                            && prod.getName().equalsIgnoreCase(i.getItemDescription()))
                    .findFirst().orElse(null);
        }
        return poItem;
    }

    /** FIFO fallback across ALL open POs for this product (oldest open line first). */
    private PoItem fifoMatchOpenPoItem(Product prod) {
        PoItem poItem = null;
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
        return poItem;
    }

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
