package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderRepository poRepository;
    private final PoItemRepository        poItemRepository;
    private final ActivityLogService      activityLogService;
    private final JwtUtil                 jwtUtil;
    private final UserRepository          userRepository;
    private final PurchaseOrderService             poService;
    private final SupplierProductMappingRepository mappingRepository;
    private final ProductRepository                productRepository;
    private final InventoryService                 inventoryService;
    private final PayableRepository                payableRepository;

    public PurchaseOrderController(PurchaseOrderRepository poRepository,
                                   PoItemRepository poItemRepository,
                                   ActivityLogService activityLogService,
                                   JwtUtil jwtUtil,
                                   UserRepository userRepository,
                                   PurchaseOrderService poService,
                                   SupplierProductMappingRepository mappingRepository,
                                   ProductRepository productRepository,
                                   InventoryService inventoryService,
                                   PayableRepository payableRepository) {
        this.poRepository       = poRepository;
        this.poItemRepository   = poItemRepository;
        this.activityLogService = activityLogService;
        this.jwtUtil            = jwtUtil;
        this.userRepository     = userRepository;
        this.poService          = poService;
        this.mappingRepository  = mappingRepository;
        this.productRepository  = productRepository;
        this.inventoryService   = inventoryService;
        this.payableRepository  = payableRepository;
    }

    /** Extract userId from Bearer token; returns null if header absent or invalid. */
    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    /** Resolve display name from JWT userId; falls back to bodyValue if userId is null or user not found. */
    private String actorName(Long userId, String bodyValue) {
        if (userId != null) {
            return userRepository.findById(userId)
                    .map(User::getFullName)
                    .orElse(bodyValue);
        }
        return bodyValue;
    }

    // ── List all POs with items ────────────────────────────────────────────
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getAllPurchaseOrders() {
        List<PurchaseOrder> orders = poRepository.findAllWithItems();
        return ResponseEntity.ok(orders.stream().map(this::toMap).collect(Collectors.toList()));
    }

    // ── Get single PO with items ───────────────────────────────────────────
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getPurchaseOrder(@PathVariable Long id) {
        return poRepository.findByIdWithItems(id)
                .map(po -> ResponseEntity.ok((Object) toMap(po)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Create PO with items ───────────────────────────────────────────────
    @PostMapping
    @Transactional
    public ResponseEntity<?> createPurchaseOrder(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = userIdFromHeader(authHeader);
        // PO number is entered manually by the user (no longer auto-generated).
        String poNumber = body.get("poNumber") != null ? body.get("poNumber").toString().trim() : "";
        if (poNumber.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "PO number is required"));
        }
        if (poRepository.findByPoNumber(poNumber).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("message", "PO number " + poNumber + " already exists"));
        }
        String vendorName = body.getOrDefault("vendorName", "").toString().trim();
        if (vendorName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Vendor name is required"));
        }

        PurchaseOrder po = new PurchaseOrder();
        po.setPoNumber(poNumber);
        po.setVendorName(vendorName);
        po.setVendorContact(strOrNull(body.get("vendorContact")));
        po.setVendorAddress(strOrNull(body.get("vendorAddress")));
        po.setShipToName(strOrNull(body.get("shipToName")));
        po.setShipToContact(strOrNull(body.get("shipToContact")));
        po.setShipToAddress(strOrNull(body.get("shipToAddress")));
        po.setNotes(strOrNull(body.get("notes")));
        po.setVatType(body.getOrDefault("vatType", "EXCLUSIVE").toString());
        po.setShippingArrangement(strOrNull(body.get("shippingArrangement")));
        // Supplier linkage — nullable; historical POs without a supplier are still valid
        Long supplierId = body.get("supplierId") != null
                ? ((Number) body.get("supplierId")).longValue() : null;
        po.setSupplierId(supplierId);
        po.setVendorReference(strOrNull(body.get("vendorReference")));
        String creator = actorName(userId, strOrNull(body.get("createdBy")));
        po.setCreatedBy(creator);
        po.setCreatedAt(LocalDateTime.now());
        po.setStatus("INCOMPLETE");

        // Process items
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems =
                (List<Map<String, Object>>) body.getOrDefault("items", Collections.emptyList());

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> ri : rawItems) {
            String desc = ri.getOrDefault("itemDescription", "").toString().trim();
            if (desc.isBlank()) continue;

            int qty = ri.get("quantityOrdered") != null
                    ? ((Number) ri.get("quantityOrdered")).intValue() : 1;

            // Unit price: explicit value from request, or resolved from supplier mapping below
            BigDecimal up = (ri.get("unitPrice") != null
                    && !ri.get("unitPrice").toString().isBlank()
                    && new BigDecimal(ri.get("unitPrice").toString()).compareTo(BigDecimal.ZERO) > 0)
                    ? new BigDecimal(ri.get("unitPrice").toString()) : null;

            PoItem item = new PoItem();
            item.setPurchaseOrder(po);
            item.setItemDescription(desc);
            item.setQuantityOrdered(qty);
            item.setFulfilledQty(0);
            item.setIsFulfilled(false);

            // Supplier snapshot resolution — runs when the PO has a supplierId and the
            // line item carries a productId that matches an existing supplier mapping.
            Long productId = ri.get("productId") != null
                    ? ((Number) ri.get("productId")).longValue() : null;
            item.setProductId(productId);
            if (supplierId != null && productId != null) {
                mappingRepository.findBySupplierIdAndProductId(supplierId, productId)
                        .ifPresent(mapping -> {
                            item.setSupplierItemCode(mapping.getSupplierItemCode());
                            item.setSupplierDescription(mapping.getSupplierDescription());
                            // Fill unit price from mapping cost only when caller provided none
                            if (up == null && mapping.getUnitCost() != null) {
                                item.setUnitPrice(mapping.getUnitCost());
                            }
                        });
            }

            // Explicit caller price always takes precedence over mapping cost
            if (up != null) item.setUnitPrice(up);
            // PoItem.unitPrice defaults to ZERO — no null risk here

            BigDecimal lt = item.getUnitPrice().multiply(BigDecimal.valueOf(qty));
            item.setLineTotal(lt);
            po.getItems().add(item);
            totalAmount = totalAmount.add(lt);
        }

        if (po.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "At least one item is required"));
        }

        po.setTotalAmount(totalAmount);
        PurchaseOrder saved = poRepository.save(po);
        activityLogService.log(userId, creator, "CREATE_PURCHASE_ORDER",
            "Created PO " + poNumber + " for " + vendorName
            + " | " + po.getItems().size() + " items | ₱" + totalAmount,
            "PURCHASE_ORDER", String.valueOf(saved.getId()));
        // Re-fetch with items so the response is complete
        return poRepository.findByIdWithItems(saved.getId())
                .map(full -> ResponseEntity.ok((Object) toMap(full)))
                .orElse(ResponseEntity.ok((Object) toMap(saved)));
    }

    // ── Edit an existing PO (header always; items with received-line guards) ──
    // Header fields (vendor / ship-to / supplier / VAT / shipping / notes / ref) are always
    // editable. Line items: unreceived lines can be edited or removed and new lines added, but
    // a line that already has received goods (fulfilledQty > 0) is LOCKED — it cannot be changed
    // or removed, protecting the warehouse stock + supplier payable created at receiving. The PO
    // number is the identifier used by receiving and is not editable here.
    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updatePurchaseOrder(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = userIdFromHeader(authHeader);
        PurchaseOrder po = poRepository.findByIdWithItems(id).orElse(null);
        if (po == null) return ResponseEntity.notFound().build();

        String vendorName = body.getOrDefault("vendorName", "").toString().trim();
        if (vendorName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Vendor name is required"));
        }

        // ── Header (always editable) ─────────────────────────────────────────
        po.setVendorName(vendorName);
        po.setVendorContact(strOrNull(body.get("vendorContact")));
        po.setVendorAddress(strOrNull(body.get("vendorAddress")));
        po.setShipToName(strOrNull(body.get("shipToName")));
        po.setShipToContact(strOrNull(body.get("shipToContact")));
        po.setShipToAddress(strOrNull(body.get("shipToAddress")));
        po.setVatType(body.getOrDefault("vatType", "EXCLUSIVE").toString());
        po.setShippingArrangement(strOrNull(body.get("shippingArrangement")));
        po.setNotes(strOrNull(body.get("notes")));
        po.setVendorReference(strOrNull(body.get("vendorReference")));
        Long supplierId = body.get("supplierId") != null
                ? ((Number) body.get("supplierId")).longValue() : null;
        po.setSupplierId(supplierId);

        // ── Items reconciliation ─────────────────────────────────────────────
        Map<Long, PoItem> existing = new LinkedHashMap<>();
        for (PoItem i : po.getItems()) existing.put(i.getId(), i);
        Set<Long> keptIds = new HashSet<>();
        List<PoItem> newItems = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems =
                (List<Map<String, Object>>) body.getOrDefault("items", Collections.emptyList());

        for (Map<String, Object> ri : rawItems) {
            Long itemId = ri.get("id") != null ? ((Number) ri.get("id")).longValue() : null;
            String desc = ri.getOrDefault("itemDescription", "").toString().trim();
            Long productId = ri.get("productId") != null ? ((Number) ri.get("productId")).longValue() : null;
            int qty = ri.get("quantityOrdered") != null ? ((Number) ri.get("quantityOrdered")).intValue() : 1;
            BigDecimal up = (ri.get("unitPrice") != null && !ri.get("unitPrice").toString().isBlank())
                    ? new BigDecimal(ri.get("unitPrice").toString()) : null;

            if (itemId != null && existing.containsKey(itemId)) {
                PoItem item = existing.get(itemId);
                keptIds.add(itemId);
                int fulfilled = item.getFulfilledQty() != null ? item.getFulfilledQty() : 0;
                if (fulfilled > 0) continue;   // received line is locked — keep exactly as-is
                if (desc.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Item description is required"));
                }
                item.setItemDescription(desc);
                item.setQuantityOrdered(qty);
                item.setProductId(productId);
                resolveSupplierSnapshot(item, supplierId, productId, up);
                item.setLineTotal(item.getUnitPrice().multiply(BigDecimal.valueOf(qty)));
            } else {
                if (desc.isBlank()) continue;   // skip blank new rows
                PoItem item = new PoItem();
                item.setPurchaseOrder(po);
                item.setItemDescription(desc);
                item.setQuantityOrdered(qty);
                item.setProductId(productId);
                item.setFulfilledQty(0);
                item.setIsFulfilled(false);
                resolveSupplierSnapshot(item, supplierId, productId, up);
                item.setLineTotal(item.getUnitPrice().multiply(BigDecimal.valueOf(qty)));
                newItems.add(item);
            }
        }

        // Removals: existing lines not present in the request. A received line cannot be removed.
        for (PoItem old : existing.values()) {
            if (keptIds.contains(old.getId())) continue;
            int fulfilled = old.getFulfilledQty() != null ? old.getFulfilledQty() : 0;
            if (fulfilled > 0) {
                return ResponseEntity.badRequest().body(Map.of("message",
                    "Cannot remove \"" + old.getItemDescription() + "\" — it already has received goods."));
            }
            po.getItems().remove(old);   // orphanRemoval deletes it
        }
        po.getItems().addAll(newItems);

        if (po.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "At least one item is required"));
        }

        // Totals + status recompute (fulfillment can shift when lines are added/removed).
        BigDecimal totalAmount = po.getItems().stream()
                .map(i -> i.getLineTotal() != null ? i.getLineTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        po.setTotalAmount(totalAmount);
        boolean allFulfilled = po.getItems().stream().allMatch(i -> Boolean.TRUE.equals(i.getIsFulfilled()));
        boolean anyReceived  = po.getItems().stream().anyMatch(i -> i.getFulfilledQty() != null && i.getFulfilledQty() > 0);
        po.setStatus(allFulfilled ? "COMPLETE" : anyReceived ? "PARTIALLY_RECEIVED" : "INCOMPLETE");

        poRepository.save(po);
        String actor = actorName(userId, strOrNull(body.get("updatedBy")));
        activityLogService.log(userId, actor, "UPDATE_PURCHASE_ORDER",
            "Edited PO " + po.getPoNumber() + " for " + vendorName
            + " | " + po.getItems().size() + " items | ₱" + totalAmount,
            "PURCHASE_ORDER", String.valueOf(po.getId()));

        return poRepository.findByIdWithItems(po.getId())
                .map(full -> ResponseEntity.ok((Object) toMap(full)))
                .orElse(ResponseEntity.ok((Object) toMap(po)));
    }

    /** Set a PO line's supplier snapshot + unit price from the (supplierId, productId) mapping,
     *  mirroring create. Explicit caller price always wins; else the mapping cost; else keep/zero. */
    private void resolveSupplierSnapshot(PoItem item, Long supplierId, Long productId, BigDecimal explicitPrice) {
        item.setSupplierItemCode(null);
        item.setSupplierDescription(null);
        if (supplierId != null && productId != null) {
            mappingRepository.findBySupplierIdAndProductId(supplierId, productId).ifPresent(m -> {
                item.setSupplierItemCode(m.getSupplierItemCode());
                item.setSupplierDescription(m.getSupplierDescription());
                if (explicitPrice == null && m.getUnitCost() != null) item.setUnitPrice(m.getUnitCost());
            });
        }
        if (explicitPrice != null) item.setUnitPrice(explicitPrice);
        if (item.getUnitPrice() == null) item.setUnitPrice(BigDecimal.ZERO);
    }

    // ── Update PO status (manual toggle) ──────────────────────────────────
    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = userIdFromHeader(authHeader);
        String actor = actorName(userId, body.getOrDefault("changedBy", "Admin"));
        return poRepository.findByIdWithItems(id).map(po -> {
            String newStatus = body.getOrDefault("status", "").toUpperCase().trim();
            if (!List.of("INCOMPLETE", "COMPLETE").contains(newStatus)) {
                return ResponseEntity.badRequest()
                        .body((Object) Map.of("message", "Status must be INCOMPLETE or COMPLETE"));
            }
            String oldStatus = po.getStatus();
            po.setStatus(newStatus);
            poRepository.save(po);
            activityLogService.log(userId, actor, "UPDATE_PO_STATUS",
                "Changed PO " + po.getPoNumber() + " status: " + oldStatus + " → " + newStatus,
                "PURCHASE_ORDER", String.valueOf(id));
            return ResponseEntity.ok((Object) toMap(po));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Delete a PO (creation-mistake fail-safe) ──────────────────────────
    // Only permitted while NOTHING has been received against it. Creating a PO has no
    // stock/ledger side-effects, but receiving a line item adds warehouse stock, writes a
    // RESTOCK movement, and creates a payable — so a PO with any fulfilledQty > 0 must NOT
    // be deletable or those records would be stranded. In that case we block with a clear
    // message. For an unreceived PO the items cascade-delete (orphanRemoval=true) and the
    // row is removed cleanly with zero financial/stock impact. Gated to the
    // "purchase-orders" page by PageAccessInterceptor, same as create/receive.
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deletePurchaseOrder(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = userIdFromHeader(authHeader);
        return poRepository.findByIdWithItems(id).map(po -> {
            boolean anyReceived = po.getItems().stream()
                    .anyMatch(i -> i.getFulfilledQty() != null && i.getFulfilledQty() > 0);
            if (anyReceived) {
                return ResponseEntity.badRequest().body((Object) Map.of("message",
                    "This PO already has received goods and cannot be deleted. "
                    + "Reverse the received items first if it was recorded by mistake."));
            }
            String actor    = actorName(userId, "Admin");
            String poNumber = po.getPoNumber();
            int    itemCnt  = po.getItems().size();
            poRepository.delete(po);
            activityLogService.log(userId, actor, "DELETE_PURCHASE_ORDER",
                "Deleted PO " + poNumber + " (" + itemCnt + " item(s), nothing received)",
                "PURCHASE_ORDER", String.valueOf(id));
            return ResponseEntity.ok((Object) Map.of("message",
                "Purchase order " + poNumber + " deleted"));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Receive goods against a PO line item ──────────────────────────────
    @PatchMapping("/{id}/items/{itemId}/receive")
    @Transactional
    public ResponseEntity<?> receiveItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        String actor = actorName(userId, "System");

        PurchaseOrder po = poRepository.findByIdWithItems(id).orElse(null);
        if (po == null) return ResponseEntity.notFound().build();

        PoItem item = po.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst().orElse(null);
        if (item == null) return ResponseEntity.notFound().build();

        int receivedQty = body.get("receivedQty") != null
                ? ((Number) body.get("receivedQty")).intValue() : 0;

        String drNumber  = body.get("drNumber")  != null ? body.get("drNumber").toString().trim()  : null;
        String warehouse = body.get("warehouse") != null ? body.get("warehouse").toString().toLowerCase() : "wh1";
        if (!List.of("wh1", "wh2", "wh3").contains(warehouse)) warehouse = "wh1";

        boolean isFinalDelivery = Boolean.TRUE.equals(body.get("isFinalDelivery"));
        if (receivedQty <= 0 && !isFinalDelivery) {
            return ResponseEntity.badRequest().body(Map.of("message", "receivedQty must be greater than 0"));
        }

        // Update item fulfillment tracking
        int newFulfilled = (item.getFulfilledQty() != null ? item.getFulfilledQty() : 0) + receivedQty;
        item.setFulfilledQty(newFulfilled);
        if (drNumber != null && !drNumber.isBlank()) item.setDrNumber(drNumber);
        if (isFinalDelivery || newFulfilled >= item.getQuantityOrdered()) item.setIsFulfilled(true);
        if (isFinalDelivery) item.setIsFinalDelivery(true);
        poItemRepository.save(item);

        // Update inventory stock if this item is linked to a product and goods were actually received
        if (receivedQty > 0 && item.getProductId() != null) {
            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product != null) {
                switch (warehouse) {
                    case "wh1": product.setStockWh1(product.getStockWh1() + receivedQty); break;
                    case "wh2": product.setStockWh2(product.getStockWh2() + receivedQty); break;
                    case "wh3": product.setStockWh3(product.getStockWh3() + receivedQty); break;
                }
                productRepository.save(product);
                inventoryService.logMovement(
                        product.getId(), "RESTOCK", warehouse, receivedQty,
                        (drNumber != null && !drNumber.isBlank()) ? drNumber : po.getPoNumber(),
                        "PO " + po.getPoNumber() + " — " + po.getVendorName(),
                        userId);
            }
        }

        // Create a PENDING payable for this receipt so the AP record exists
        if (receivedQty > 0) {
            BigDecimal unitCost = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
            Payable payable = new Payable();
            payable.setDeliveryLogId(null);
            payable.setReceiptNumber(drNumber != null && !drNumber.isBlank() ? drNumber : po.getPoNumber());
            payable.setSupplierName(po.getVendorName());
            payable.setTotalAmount(unitCost.multiply(BigDecimal.valueOf(receivedQty)));
            payable.setAmountPaid(BigDecimal.ZERO);
            payable.setStatus("PENDING");
            payable.setCreatedBy(actor);
            payableRepository.save(payable);
        }

        // Recompute PO status:
        //   COMPLETE          — every item is fully fulfilled (isFulfilled=true)
        //   PARTIALLY_RECEIVED — at least one item has had goods received (fulfilledQty > 0)
        //   INCOMPLETE        — nothing received yet
        List<PoItem> allItems = po.getItems();
        boolean allFulfilled = allItems.stream().allMatch(i -> Boolean.TRUE.equals(i.getIsFulfilled()));
        boolean anyReceived  = allItems.stream().anyMatch(i -> i.getFulfilledQty() != null && i.getFulfilledQty() > 0);
        String newStatus = allFulfilled ? "COMPLETE" : anyReceived ? "PARTIALLY_RECEIVED" : "INCOMPLETE";
        po.setStatus(newStatus);
        poRepository.save(po);

        String logMsg = isFinalDelivery && receivedQty == 0
                ? "Final delivery (no goods) — closed PO line: " + item.getItemDescription() + " for PO " + po.getPoNumber()
                : "Received " + receivedQty + " × " + item.getItemDescription()
                  + " into " + warehouse.toUpperCase()
                  + " for PO " + po.getPoNumber()
                  + (isFinalDelivery ? " [FINAL]" : "")
                  + (drNumber != null && !drNumber.isBlank() ? " | DR: " + drNumber : "");
        activityLogService.log(userId, actor, "PURCHASE_ORDER_RECEIVE", logMsg,
                "PURCHASE_ORDER", String.valueOf(id));

        return poRepository.findByIdWithItems(id)
                .map(full -> ResponseEntity.ok((Object) toMap(full)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Serialise PO → response map ───────────────────────────────────────
    Map<String, Object> toMap(PurchaseOrder po) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                  po.getId());
        m.put("poNumber",            po.getPoNumber());
        m.put("vendorName",          po.getVendorName());
        m.put("vendorContact",       po.getVendorContact());
        m.put("vendorAddress",       po.getVendorAddress());
        m.put("shipToName",          po.getShipToName());
        m.put("shipToContact",       po.getShipToContact());
        m.put("shipToAddress",       po.getShipToAddress());
        m.put("notes",               po.getNotes());
        m.put("vatType",             po.getVatType());
        m.put("shippingArrangement", po.getShippingArrangement());
        m.put("supplierId",          po.getSupplierId());
        m.put("vendorReference",     po.getVendorReference());
        m.put("status",              po.getStatus());
        m.put("createdBy",           po.getCreatedBy());
        m.put("createdAt",           po.getCreatedAt() != null ? po.getCreatedAt().toString() : null);
        m.put("totalAmount",         po.getTotalAmount());

        List<Map<String, Object>> items = new ArrayList<>();
        for (PoItem i : po.getItems()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id",              i.getId());
            im.put("itemDescription", i.getItemDescription());
            im.put("quantityOrdered", i.getQuantityOrdered());
            im.put("unitPrice",       i.getUnitPrice());
            im.put("lineTotal",       i.getLineTotal());
            im.put("fulfilledQty",         i.getFulfilledQty());
            im.put("drNumber",             i.getDrNumber());
            im.put("isFulfilled",          i.getIsFulfilled());
            im.put("supplierItemCode",     i.getSupplierItemCode());
            im.put("supplierDescription",  i.getSupplierDescription());
            im.put("productId",            i.getProductId());
            im.put("isFinalDelivery",      i.getIsFinalDelivery());
            items.add(im);
        }
        m.put("items",         items);
        m.put("totalItems",    items.size());
        m.put("fulfilledCount", items.stream()
                .filter(i -> Boolean.TRUE.equals(i.get("isFulfilled"))).count());

        // Effective payable: final-delivery items billed at fulfilledQty × unitPrice;
        // all others at quantityOrdered × unitPrice (original quoted amount)
        BigDecimal effectiveTotal = po.getItems().stream()
                .map(i -> {
                    BigDecimal cost = i.getUnitPrice() != null ? i.getUnitPrice() : BigDecimal.ZERO;
                    int qty = Boolean.TRUE.equals(i.getIsFinalDelivery())
                            ? (i.getFulfilledQty() != null ? i.getFulfilledQty() : 0)
                            : (i.getQuantityOrdered() != null ? i.getQuantityOrdered() : 0);
                    return cost.multiply(BigDecimal.valueOf(qty));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        m.put("effectiveTotalAmount", effectiveTotal);
        return m;
    }

    private String strOrNull(Object val) {
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
