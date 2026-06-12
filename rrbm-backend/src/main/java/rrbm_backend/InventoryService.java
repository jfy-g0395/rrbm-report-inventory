package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private final ProductRepository             productRepository;
    private final InventoryMovementRepository   movementRepository;
    private final ProductSetComponentRepository productSetComponentRepository;
    private final LowStockEmailService          lowStockEmailService;

    public InventoryService(ProductRepository productRepository,
                            InventoryMovementRepository movementRepository,
                            ProductSetComponentRepository productSetComponentRepository,
                            LowStockEmailService lowStockEmailService) {
        this.productRepository             = productRepository;
        this.movementRepository            = movementRepository;
        this.productSetComponentRepository = productSetComponentRepository;
        this.lowStockEmailService          = lowStockEmailService;
    }

    /**
     * Deduct stock for all items in an order.
     * Called when an order is successfully created.
     *
     * For SET products: deducts from each component product (same warehouse).
     * For regular products: deducts directly.
     * Throws RuntimeException on insufficient stock — rolls back the whole order.
     */
    @Transactional
    public void deductStockForOrder(Order order, Long userId) {
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                // Product typed manually — skip stock deduction
                continue;
            }

            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new RuntimeException(
                            "Product not found: " + item.getProductName()));

            int qty = item.getQuantity();

            if (Boolean.TRUE.equals(product.getIsSet())) {
                // ── SET PRODUCT: deduct each component ──────────────────────────
                List<ProductSetComponent> comps = productSetComponentRepository.findBySetProductId(product.getId());
                if (comps.isEmpty()) {
                    throw new RuntimeException("Set product \"" + product.getName() + "\" has no components defined.");
                }
                String warehouse = item.getWarehouse();
                if (warehouse == null || warehouse.isBlank()) {
                    warehouse = findBestWarehouseForSet(comps, qty);
                    item.setWarehouse(warehouse);
                } else {
                    warehouse = warehouse.toLowerCase();
                }
                // Pre-validate all components before deducting anything
                for (ProductSetComponent comp : comps) {
                    Product compProduct = productRepository.findById(comp.getComponentProductId())
                            .orElseThrow(() -> new RuntimeException(
                                    "Component product (id=" + comp.getComponentProductId() + ") not found."));
                    int needed = qty * comp.getQuantityPerSet();
                    int available = getWhStock(compProduct, warehouse);
                    if (available < needed) {
                        throw new RuntimeException(
                            "Insufficient stock in " + warehouse.toUpperCase()
                            + " for component \"" + compProduct.getName()
                            + "\" (part of set \"" + product.getName() + "\"). "
                            + "Available: " + available + ", Needed: " + needed);
                    }
                }
                // All components OK — deduct
                for (ProductSetComponent comp : comps) {
                    Product compProduct = productRepository.findById(comp.getComponentProductId()).get();
                    int needed = qty * comp.getQuantityPerSet();
                    deductWhStock(compProduct, warehouse, needed);
                    productRepository.save(compProduct);
                    logMovement(compProduct.getId(), "ORDER_OUT", warehouse, -needed,
                            order.getId(),
                            "Set order by " + order.getCustomerName() + " (set: " + product.getName() + ")",
                            userId);
                }
                // Do NOT deduct from the set product itself — it has no physical stock

            } else {
                // ── REGULAR PRODUCT ─────────────────────────────────────────────
                String warehouse = item.getWarehouse();
                if (warehouse == null || warehouse.isBlank()) {
                    warehouse = findBestWarehouse(product, qty);
                    item.setWarehouse(warehouse);
                } else {
                    warehouse = warehouse.toLowerCase();
                }
                int available = getWhStock(product, warehouse);
                if (available < qty) {
                    throw new RuntimeException(
                        "Insufficient stock in " + warehouse.toUpperCase() + " for " + product.getName()
                        + ". Available: " + available + ", Requested: " + qty);
                }
                deductWhStock(product, warehouse, qty);
                productRepository.save(product);
                logMovement(item.getProductId(), "ORDER_OUT", warehouse, -qty,
                        order.getId(), "Order by " + order.getCustomerName(), userId);
            }
        }

        // Check for any products that just fell below their low-stock threshold
        checkAndAlertLowStock();
    }

    /**
     * Checks all active products and sends email alerts for any that are below threshold.
     * Called asynchronously after every stock deduction.
     */
    private void checkAndAlertLowStock() {
        try {
            List<Product> allActive = productRepository.findByActiveTrueOrderByNameAsc();
            List<Product> lowItems = allActive.stream()
                    .filter(p -> {
                        String tag = p.getSellingTag();
                        if (tag == null) return false;
                        int threshold = switch (tag.toUpperCase()) {
                            case "HOT"     -> 5000;
                            case "SELLING" -> 2000;
                            case "SLOW"    -> 1000;
                            default        -> 0;
                        };
                        return threshold > 0 && p.getTotalStock() <= threshold;
                    })
                    .collect(Collectors.toList());
            if (!lowItems.isEmpty()) {
                lowStockEmailService.sendLowStockAlert(lowItems);
            }
        } catch (Exception e) {
            // Non-critical — don't let email failures break orders
            System.err.println("Low stock check failed: " + e.getMessage());
        }
    }

    /** Returns the stock level for a given warehouse key (wh1/wh2/wh3). */
    int getWhStock(Product p, String warehouse) {
        switch (warehouse) {
            case "wh1": return p.getStockWh1() != null ? p.getStockWh1() : 0;
            case "wh2": return p.getStockWh2() != null ? p.getStockWh2() : 0;
            case "wh3": return p.getStockWh3() != null ? p.getStockWh3() : 0;
            default: throw new RuntimeException("Unknown warehouse: " + warehouse);
        }
    }

    /** Deducts qty from the given warehouse column on the product (mutates, does not save). */
    private void deductWhStock(Product p, String warehouse, int qty) {
        switch (warehouse) {
            case "wh1": p.setStockWh1(p.getStockWh1() - qty); break;
            case "wh2": p.setStockWh2(p.getStockWh2() - qty); break;
            case "wh3": p.setStockWh3(p.getStockWh3() - qty); break;
            default: throw new RuntimeException("Unknown warehouse: " + warehouse);
        }
    }

    /** Finds the warehouse with the highest stock that can fulfill qty. */
    String findBestWarehouse(Product p, int qty) {
        String[] warehouses = {"wh1", "wh2", "wh3"};
        String bestWh = warehouses[0];
        int bestStock = getWhStock(p, warehouses[0]);
        for (int i = 1; i < warehouses.length; i++) {
            int s = getWhStock(p, warehouses[i]);
            boolean currentMeets = bestStock >= qty;
            boolean candidateMeets = s >= qty;
            if (candidateMeets && (!currentMeets || s > bestStock)) {
                bestWh = warehouses[i];
                bestStock = s;
            } else if (!candidateMeets && !currentMeets && s > bestStock) {
                bestWh = warehouses[i];
                bestStock = s;
            }
        }
        return bestWh;
    }

    /** Finds a single warehouse where ALL set components have sufficient stock (highest total). */
    String findBestWarehouseForSet(List<ProductSetComponent> comps, int qty) {
        String bestWh = "wh1";
        int bestTotal = -1;
        for (String wh : new String[]{"wh1", "wh2", "wh3"}) {
            boolean allMet = true;
            int totalAvail = 0;
            for (ProductSetComponent comp : comps) {
                Product compProduct = productRepository.findById(comp.getComponentProductId()).orElse(null);
                if (compProduct == null) { allMet = false; break; }
                int available = getWhStock(compProduct, wh);
                int needed = qty * comp.getQuantityPerSet();
                if (available < needed) allMet = false;
                totalAvail += available;
            }
            if (allMet && totalAvail > bestTotal) {
                bestTotal = totalAvail;
                bestWh = wh;
            }
        }
        return bestWh;
    }

    /**
     * Restore stock for all items in a cancelled order.
     * This is the reverse of deductStockForOrder — adds stock back to the same warehouse.
     * For SET products: restores each component.
     */
    @Transactional
    public void restoreStockForCancelledOrder(Order order, Long userId) {
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                continue; // Typed manually — nothing to restore
            }

            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product == null) continue; // Deleted product — skip

            String warehouse = item.getWarehouse() != null ? item.getWarehouse().toLowerCase() : "wh1";
            int qty = item.getQuantity();

            if (Boolean.TRUE.equals(product.getIsSet())) {
                // Restore each component
                List<ProductSetComponent> comps = productSetComponentRepository.findBySetProductId(product.getId());
                for (ProductSetComponent comp : comps) {
                    Product compProduct = productRepository.findById(comp.getComponentProductId()).orElse(null);
                    if (compProduct == null) continue;
                    int restore = qty * comp.getQuantityPerSet();
                    switch (warehouse) {
                        case "wh1": compProduct.setStockWh1(compProduct.getStockWh1() + restore); break;
                        case "wh2": compProduct.setStockWh2(compProduct.getStockWh2() + restore); break;
                        case "wh3": compProduct.setStockWh3(compProduct.getStockWh3() + restore); break;
                    }
                    productRepository.save(compProduct);
                    logMovement(compProduct.getId(), "CANCELLED_RETURN", warehouse, +restore,
                            order.getId(),
                            "Cancellation of set order " + order.getId() + " (set: " + product.getName() + ")",
                            userId);
                }
            } else {
                // Regular product
                switch (warehouse) {
                    case "wh1": product.setStockWh1(product.getStockWh1() + qty); break;
                    case "wh2": product.setStockWh2(product.getStockWh2() + qty); break;
                    case "wh3": product.setStockWh3(product.getStockWh3() + qty); break;
                }
                productRepository.save(product);
                logMovement(item.getProductId(), "CANCELLED_RETURN", warehouse, +qty,
                        order.getId(), "Cancellation of order " + order.getId(), userId);
            }
        }
    }

    /**
     * Handles inventory side-effects for a cancel-for-replacement operation.
     *
     * Iterates every item on the order.  For each item, disposition is resolved
     * from {@code dispositionMap} (keyed by orderItemId) when the order is
     * DELIVERED; for non-DELIVERED orders every item is auto-SELLABLE because
     * the goods were never physically dispatched.
     *
     * SELLABLE items:
     *   Stock is restored to the originating warehouse.
     *   SET products: each component is restored individually (same logic as
     *   restoreStockForCancelledOrder).
     *   Movement type: CANCELLED_RETURN (positive quantity).
     *
     * REJECTED items:
     *   No stock change.  A CANCEL_REJECTED movement record is written with
     *   quantity 0 as an audit trail only.
     *
     * @param order          the order being cancelled
     * @param dispositionMap orderItemId → 'SELLABLE' | 'REJECTED'; may be empty
     *                       for non-DELIVERED orders
     * @param isDelivered    true when order status is DELIVERED
     * @param userId         authenticated user performing the cancellation
     */
    @Transactional
    public void restoreStockForCancelledWithDisposition(Order order,
                                                        Map<Long, String> dispositionMap,
                                                        boolean isDelivered,
                                                        Long userId) {
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) continue; // manually typed item — skip

            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product == null) continue; // product since deleted — skip silently

            String warehouse = item.getWarehouse() != null ? item.getWarehouse().toLowerCase() : "wh1";

            // Use REMAINING quantity — not the original full quantity.
            // Voided units were already restored to stock when each void was applied.
            // Restoring the full quantity here would double-restore already-voided units.
            int alreadyVoided = item.getVoidedQuantity() != null ? item.getVoidedQuantity() : 0;
            int qty = item.getQuantity() - alreadyVoided;
            if (qty <= 0) continue; // all units already voided — nothing left to restore

            // Resolve disposition: DELIVERED orders use the caller-supplied map;
            // non-DELIVERED orders always restore (goods are still in the warehouse).
            String disposition = "SELLABLE";
            if (isDelivered && dispositionMap != null) {
                String d = dispositionMap.get(item.getId());
                if (d != null) disposition = d.toUpperCase();
            }

            boolean restoreStock = "SELLABLE".equals(disposition);

            if (restoreStock) {
                if (Boolean.TRUE.equals(product.getIsSet())) {
                    // ── SET product: restore each component ──────────────────
                    List<ProductSetComponent> comps =
                            productSetComponentRepository.findBySetProductId(product.getId());
                    for (ProductSetComponent comp : comps) {
                        Product compProduct =
                                productRepository.findById(comp.getComponentProductId()).orElse(null);
                        if (compProduct == null) continue;
                        int restore = qty * comp.getQuantityPerSet();
                        switch (warehouse) {
                            case "wh2": compProduct.setStockWh2(compProduct.getStockWh2() + restore); break;
                            case "wh3": compProduct.setStockWh3(compProduct.getStockWh3() + restore); break;
                            default:    compProduct.setStockWh1(compProduct.getStockWh1() + restore); break;
                        }
                        productRepository.save(compProduct);
                        logMovement(compProduct.getId(), "CANCELLED_RETURN", warehouse, +restore,
                                order.getId(),
                                "Replacement cancel of set order " + order.getId()
                                    + " (set: " + product.getName() + ") — SELLABLE",
                                userId);
                    }
                } else {
                    // ── Regular product ──────────────────────────────────────
                    switch (warehouse) {
                        case "wh2": product.setStockWh2(product.getStockWh2() + qty); break;
                        case "wh3": product.setStockWh3(product.getStockWh3() + qty); break;
                        default:    product.setStockWh1(product.getStockWh1() + qty); break;
                    }
                    productRepository.save(product);
                    logMovement(item.getProductId(), "CANCELLED_RETURN", warehouse, +qty,
                            order.getId(),
                            "Replacement cancel of order " + order.getId() + " — SELLABLE",
                            userId);
                }
            } else {
                // ── REJECTED: no stock change; write audit trail movement ────
                logMovement(item.getProductId(), "CANCEL_REJECTED", warehouse, qty,
                        order.getId(),
                        "Replacement cancel of order " + order.getId() + " — REJECTED (no stock restore)",
                        userId);
            }
        }
    }

    /**
     * Handles inventory side-effects for a single voided order item.
     *
     * For non-DELIVERED orders (ACTIVE, PENDING, etc.): goods were never
     * physically dispatched, so any voided quantity is always restored to
     * warehouse stock regardless of the disposition value.
     *
     * For DELIVERED orders:
     *   disposition = 'SELLABLE' → restore qty to warehouse (goods coming back)
     *   disposition = 'REJECTED' → do NOT restore stock; write a zero-qty ITEM_VOID
     *                              movement record as an audit trail only.
     *
     * @param item        the order item being voided
     * @param voidQty     number of units voided on this line
     * @param disposition 'SELLABLE' or 'REJECTED'; ignored for non-delivered orders
     * @param isDelivered whether the parent order has status DELIVERED
     * @param orderId     the parent order ID (for movement reference)
     * @param userId      the authenticated user performing the void
     */
    @Transactional
    public String restoreStockForVoidedItem(OrderItem item, int voidQty,
                                          String disposition, boolean isDelivered,
                                          String orderId, Long userId) {
        if (item.getProductId() == null)
            return item.getProductName() + " — manual item, no stock tracked";

        Product product = productRepository.findById(item.getProductId()).orElse(null);
        if (product == null)
            return item.getProductName() + " — product not found, no stock change";

        String warehouse = item.getWarehouse() != null ? item.getWarehouse().toLowerCase() : "wh1";
        boolean restoreStock = !isDelivered || "SELLABLE".equalsIgnoreCase(disposition);

        if (restoreStock) {
            switch (warehouse) {
                case "wh2": product.setStockWh2(product.getStockWh2() + voidQty); break;
                case "wh3": product.setStockWh3(product.getStockWh3() + voidQty); break;
                default:    product.setStockWh1(product.getStockWh1() + voidQty); break;
            }
            productRepository.save(product);
            logMovement(item.getProductId(), "ITEM_VOID", warehouse, +voidQty,
                    orderId,
                    "Void of " + voidQty + " unit(s) — "
                        + (isDelivered ? "SELLABLE return" : "non-delivered order")
                        + " — order " + orderId,
                    userId);
            return voidQty + " unit(s) of " + item.getProductName()
                + " returned to " + warehouse.toUpperCase()
                + " — new stock total: " + getWhStock(product, warehouse);
        } else {
            // REJECTED: no stock change; write movement record with actual qty for reporting
            logMovement(item.getProductId(), "VOID_REJECTED", warehouse, voidQty,
                    orderId,
                    "Void of " + voidQty + " unit(s) — REJECTED (no stock restore) — order " + orderId,
                    userId);
            return voidQty + " unit(s) of " + item.getProductName()
                + " recorded as rejected/damaged";
        }
    }

    /**
     * Handles inventory side-effects for one order item in a post-sale return.
     *
     * sellableQty > 0:
     *   Stock is restored to the originating warehouse.
     *   For SET products: each component is restored individually
     *   (same decomposition as restoreStockForCancelledWithDisposition).
     *   Movement type: RETURN_SELLABLE, quantity = sellableQty.
     *
     * rejectedQty > 0:
     *   No stock change.  A RETURN_REJECTED movement is written with the actual
     *   rejected quantity — meaningful for waste/damage tracking, unlike the
     *   zero-qty convention used by CANCEL_REJECTED.
     *
     * Either or both may be non-zero on the same call.  Zero values are
     * skipped (no movement written).
     *
     * @param item        the order item being returned
     * @param sellableQty units returning to warehouse stock
     * @param rejectedQty units physically received but not resaleable
     * @param orderId     parent order ID (movement reference)
     * @param userId      authenticated user performing the return
     */
    @Transactional
    public void processReturnForItem(OrderItem item, int sellableQty, int rejectedQty,
                                     String orderId, Long userId) {
        if (item.getProductId() == null) return; // manually typed item — skip

        Product product = productRepository.findById(item.getProductId()).orElse(null);
        if (product == null) return; // product since deleted — skip silently

        String warehouse = item.getWarehouse() != null ? item.getWarehouse().toLowerCase() : "wh1";

        // ── Sellable: restore stock ──────────────────────────────────────
        if (sellableQty > 0) {
            if (Boolean.TRUE.equals(product.getIsSet())) {
                // SET product: restore each component proportionally
                List<ProductSetComponent> comps =
                        productSetComponentRepository.findBySetProductId(product.getId());
                for (ProductSetComponent comp : comps) {
                    Product compProduct =
                            productRepository.findById(comp.getComponentProductId()).orElse(null);
                    if (compProduct == null) continue;
                    int restore = sellableQty * comp.getQuantityPerSet();
                    switch (warehouse) {
                        case "wh2": compProduct.setStockWh2(compProduct.getStockWh2() + restore); break;
                        case "wh3": compProduct.setStockWh3(compProduct.getStockWh3() + restore); break;
                        default:    compProduct.setStockWh1(compProduct.getStockWh1() + restore); break;
                    }
                    productRepository.save(compProduct);
                    logMovement(compProduct.getId(), "RETURN_SELLABLE", warehouse, +restore,
                            orderId,
                            "Return — " + sellableQty + " sellable unit(s) of set \""
                                + product.getName() + "\" — order " + orderId,
                            userId);
                }
            } else {
                // Regular product
                switch (warehouse) {
                    case "wh2": product.setStockWh2(product.getStockWh2() + sellableQty); break;
                    case "wh3": product.setStockWh3(product.getStockWh3() + sellableQty); break;
                    default:    product.setStockWh1(product.getStockWh1() + sellableQty); break;
                }
                productRepository.save(product);
                logMovement(item.getProductId(), "RETURN_SELLABLE", warehouse, +sellableQty,
                        orderId,
                        "Return — " + sellableQty + " sellable unit(s) of \""
                            + item.getProductName() + "\" — order " + orderId,
                        userId);
            }
        }

        // ── Rejected: no stock change; write movement with actual count ──
        if (rejectedQty > 0) {
            logMovement(item.getProductId(), "RETURN_REJECTED", warehouse, rejectedQty,
                    orderId,
                    "Return — " + rejectedQty + " rejected unit(s) of \""
                        + item.getProductName() + "\" (no stock restore) — order " + orderId,
                    userId);
        }
    }

    /**
     * Validates and normalizes a destination warehouse code.
     * Must be called before any stock write when a sellable restock is requested.
     *
     * @param warehouse    raw value from the request (may be null/blank)
     * @param productLabel product name for the error message
     * @return normalized lowercase warehouse code: "wh1", "wh2", or "wh3"
     * @throws RuntimeException with a descriptive message if blank or unrecognized
     */
    String requireValidWarehouse(String warehouse, String productLabel) {
        if (warehouse == null || warehouse.isBlank())
            throw new RuntimeException(
                "Destination warehouse is required for sellable item \""
                + productLabel + "\". Must be wh1, wh2, or wh3.");
        String normalized = warehouse.trim().toLowerCase();
        if (!normalized.equals("wh1") && !normalized.equals("wh2") && !normalized.equals("wh3"))
            throw new RuntimeException(
                "Invalid destination warehouse \"" + warehouse + "\" for item \""
                + productLabel + "\". Must be wh1, wh2, or wh3.");
        return normalized;
    }

    /**
     * Write one row to inventory_movements.
     * quantity is signed: negative = out, positive = in.
     */
    void logMovement(Long productId, String movementType,
                              String warehouse, int quantity,
                              String referenceId, String reason, Long userId) {
        InventoryMovement movement = new InventoryMovement();
        movement.setProductId(productId);
        movement.setMovementType(movementType);
        movement.setWarehouse(warehouse);
        movement.setQuantity(quantity);
        movement.setReferenceId(referenceId);
        movement.setReason(reason);
        movement.setUserId(userId);
        movementRepository.save(movement);
    }
}
