package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/delivery-reports")
@CrossOrigin(origins = "*")
public class DeliveryReportController {

    private final DeliveryLogRepository    repo;
    private final ProductRepository        productRepository;
    private final MasterKeyService         masterKeyService;
    private final PurchaseOrderRepository  purchaseOrderRepository;
    private final PoItemRepository         poItemRepository;
    private final PayableRepository        payableRepository;

    public DeliveryReportController(DeliveryLogRepository repo,
                                    ProductRepository productRepository,
                                    MasterKeyService masterKeyService,
                                    PurchaseOrderRepository purchaseOrderRepository,
                                    PoItemRepository poItemRepository,
                                    PayableRepository payableRepository) {
        this.repo                    = repo;
        this.productRepository       = productRepository;
        this.masterKeyService        = masterKeyService;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.poItemRepository        = poItemRepository;
        this.payableRepository       = payableRepository;
    }

    @GetMapping
    public List<DeliveryLog> getAll() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return repo.findById(id)
                .map(r -> ResponseEntity.ok((Object) r))
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<?> cancelDelivery(@PathVariable Long id,
                                            @RequestBody Map<String, String> body) {

        // ── 1. Master key validation ───────────────────────────────────────
        String masterKey = body != null ? body.get("masterKey") : null;
        if (masterKey == null || masterKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Master key is required to cancel a delivery"));
        }
        if (!masterKeyService.validateMasterKey(masterKey)) {
            return ResponseEntity.status(403)
                    .body(Map.of("message", "Invalid master key"));
        }

        // ── 2. Load delivery log ───────────────────────────────────────────
        DeliveryLog log = repo.findById(id).orElse(null);
        if (log == null) return ResponseEntity.notFound().build();
        if ("CANCELLED".equals(log.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Delivery is already cancelled"));

        // ── 3. Reverse warehouse stock ─────────────────────────────────────
        for (DeliveryLogItem item : log.getItems()) {
            if (item.getProductId() == null) continue;
            Product p = productRepository.findById(item.getProductId()).orElse(null);
            if (p == null) continue;
            int qty = item.getReceivedQty() > 0 ? item.getReceivedQty() : item.getQuantity();
            String wh = item.getWarehouse() != null ? item.getWarehouse() : "wh1";
            switch (wh) {
                case "wh1": p.setStockWh1(Math.max(0, p.getStockWh1() - qty)); break;
                case "wh2": p.setStockWh2(Math.max(0, p.getStockWh2() - qty)); break;
                case "wh3": p.setStockWh3(Math.max(0, p.getStockWh3() - qty)); break;
            }
            productRepository.save(p);
        }

        // ── 4. Reverse PO item fulfillment ────────────────────────────────
        try {
            Set<Long> touchedPoIds = new HashSet<>();

            if (log.getPoNumber() != null && !log.getPoNumber().isBlank()) {
                // ── 4a. Linked DR: match against the specific PO ──────────
                PurchaseOrder linkedPo = purchaseOrderRepository
                        .findByPoNumber(log.getPoNumber()).orElse(null);

                if (linkedPo != null) {
                    for (DeliveryLogItem drItem : log.getItems()) {
                        PoItem poItem = findPoItemForDrItem(drItem, linkedPo.getItems());
                        if (poItem == null) continue;
                        int received = drItem.getReceivedQty() > 0
                                ? drItem.getReceivedQty() : drItem.getQuantity();
                        if (received <= 0) continue;
                        reversePoItem(poItem, received);
                        poItemRepository.save(poItem);
                        touchedPoIds.add(linkedPo.getId());
                    }
                }
            } else {
                // ── 4b. Unlinked DR: find any PO items whose drNumber matches
                //        this receipt (FIFO auto-match stamped them on receive)
                List<PoItem> fifoMatched = poItemRepository.findByDrNumberOrderByIdAsc(log.getReceiptNumber());

                for (PoItem poItem : fifoMatched) {
                    // Determine how much this DR received for the matching product
                    int received = resolveReceivedQtyForPoItem(poItem, log.getItems());
                    if (received <= 0) continue;
                    reversePoItem(poItem, received);
                    poItemRepository.save(poItem);
                    touchedPoIds.add(poItem.getPurchaseOrder().getId());
                }
            }

            // Re-evaluate status for every touched PO
            for (Long poId : touchedPoIds) {
                purchaseOrderRepository.findByIdWithItems(poId).ifPresent(this::recalcPoStatus);
            }

        } catch (Exception e) {
            System.err.println("Warning: PO item reversal failed for DR "
                    + log.getReceiptNumber() + ": " + e.getMessage());
        }

        // ── 5. Cancel the associated payable ─────────────────────────────
        try {
            payableRepository.findByDeliveryLogId(log.getId()).forEach(payable -> {
                payable.setStatus("CANCELLED");
                payableRepository.save(payable);
            });
        } catch (Exception e) {
            System.err.println("Warning: payable cancellation failed for DR "
                    + log.getReceiptNumber() + ": " + e.getMessage());
        }

        // ── 6. Mark delivery as cancelled ─────────────────────────────────
        log.setStatus("CANCELLED");
        repo.save(log);
        return ResponseEntity.ok(Map.of("message", "Delivery cancelled — stock, PO items, and payable reversed"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Find the PoItem on a given PO that corresponds to a delivery log item.
     *  Three-attempt order: (1) product itemCode vs PO item's legacy itemCode,
     *  (2) product itemCode vs PO item's supplierItemCode,
     *  (3) product name vs PO item description. */
    private PoItem findPoItemForDrItem(DeliveryLogItem drItem, List<PoItem> poItems) {
        if (drItem.getProductId() == null) return null;
        Product prod = productRepository.findById(drItem.getProductId()).orElse(null);
        if (prod == null) return null;

        // Attempt 1: product itemCode vs PO item's legacy itemCode
        if (prod.getItemCode() != null && !prod.getItemCode().isBlank()) {
            PoItem match = poItems.stream()
                    .filter(i -> prod.getItemCode().equalsIgnoreCase(i.getItemCode()))
                    .findFirst().orElse(null);
            if (match != null) return match;
        }
        // Attempt 2: product itemCode vs PO item's supplierItemCode
        if (prod.getItemCode() != null && !prod.getItemCode().isBlank()) {
            PoItem match = poItems.stream()
                    .filter(i -> prod.getItemCode().equalsIgnoreCase(i.getSupplierItemCode()))
                    .findFirst().orElse(null);
            if (match != null) return match;
        }
        // Attempt 3: product name vs PO item description (case-insensitive)
        if (prod.getName() != null) {
            return poItems.stream()
                    .filter(i -> prod.getName().equalsIgnoreCase(i.getItemDescription()))
                    .findFirst().orElse(null);
        }
        return null;
    }

    /** For an unlinked DR's FIFO-matched PoItem, find how many units were
     *  received by scanning the DR's line items for a product that matches
     *  the PoItem's itemCode or description. */
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
            if (byCode || bySupplierCode || byName) {
                return drItem.getReceivedQty() > 0
                        ? drItem.getReceivedQty() : drItem.getQuantity();
            }
        }
        return 0;
    }

    /** Subtract qty from a PoItem's fulfilledQty, resetting isFulfilled and
     *  drNumber as needed. Does not call save — caller is responsible. */
    private void reversePoItem(PoItem poItem, int qty) {
        int newFulfilled = Math.max(0,
                (poItem.getFulfilledQty() != null ? poItem.getFulfilledQty() : 0) - qty);
        poItem.setFulfilledQty(newFulfilled);
        poItem.setIsFulfilled(newFulfilled > 0
                && newFulfilled >= (poItem.getQuantityOrdered() != null ? poItem.getQuantityOrdered() : 1));
        if (newFulfilled == 0) poItem.setDrNumber(null);
    }

    /** Re-derive and persist a PO's status from its current item states. */
    private void recalcPoStatus(PurchaseOrder po) {
        boolean allFulfilled = po.getItems().stream()
                .allMatch(i -> Boolean.TRUE.equals(i.getIsFulfilled()));
        boolean anyReceived  = po.getItems().stream()
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
