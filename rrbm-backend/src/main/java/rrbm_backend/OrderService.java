package rrbm_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import rrbm_backend.dto.CancelForReplacementRequest;
import rrbm_backend.dto.CorrectItemRequest;
import rrbm_backend.dto.CreateOrderRequest;
import rrbm_backend.dto.ReturnOrderRequest;
import rrbm_backend.dto.VoidOrderRequest;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final Set<String> VALID_SOURCES = Set.of(
        "WALK_IN", "AGENT", "ECOMMERCE", "FACEBOOK_PAGE", "RESELLER", "DISTRIBUTOR"
    );

    private final OrderRepository    orderRepository;
    private final OrderIdGenerator   orderIdGenerator;
    private final UserRepository     userRepository;
    private final InventoryService   inventoryService;
    private final MasterKeyService   masterKeyService;
    private final ActivityLogService activityLogService;
    private final TransactionService transactionService;
    private final CommissionService  commissionService;
    private final DailyReportRepository dailyReportRepository;
    private final ProductRepository  productRepository;
    private final CashLedgerService  cashLedgerService;

    public OrderService(OrderRepository orderRepository,
                        OrderIdGenerator orderIdGenerator,
                        UserRepository userRepository,
                        InventoryService inventoryService,
                        MasterKeyService masterKeyService,
                        ActivityLogService activityLogService,
                        TransactionService transactionService,
                        CommissionService commissionService,
                        DailyReportRepository dailyReportRepository,
                        ProductRepository productRepository,
                        CashLedgerService cashLedgerService) {
        this.orderRepository    = orderRepository;
        this.orderIdGenerator   = orderIdGenerator;
        this.userRepository     = userRepository;
        this.inventoryService   = inventoryService;
        this.masterKeyService   = masterKeyService;
        this.activityLogService = activityLogService;
        this.transactionService = transactionService;
        this.commissionService  = commissionService;
        this.dailyReportRepository = dailyReportRepository;
        this.productRepository  = productRepository;
        this.cashLedgerService  = cashLedgerService;
    }

    /**
     * Create a new order with items.
     *
     * The @Transactional annotation means ALL of the following happen
     * as one atomic unit — if any step fails, EVERYTHING rolls back:
     *   1. Generate order ID
     *   2. Save the order and its items
     *   3. Deduct stock from inventory
     *   4. Log inventory movements
     *
     * So if someone orders 200 units but only 50 are in stock,
     * the order is NOT saved and the customer gets an error message.
     */
    @Transactional
    public Order createOrder(Order order, Long createdByUserId) {
        // Validate source value up-front — prevents unknown sources reaching the DB
        if (order.getSource() == null || !VALID_SOURCES.contains(order.getSource().toUpperCase())) {
            throw new RuntimeException("Invalid order source: '" + order.getSource()
                + "'. Must be one of: " + VALID_SOURCES);
        }
        order.setSource(order.getSource().toUpperCase());

        // Generate unique order ID (e.g. 010626-000042)
        String orderId = orderIdGenerator.generateOrderId();
        order.setId(orderId);

        // Set the user who created this order
        User creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new RuntimeException("User not found: " + createdByUserId));
        order.setCreatedBy(creator);

        // Link all items to this order (required for the FK relationship)
        order.getItems().forEach(item -> item.setOrder(order));

        // Calculate subtotal, discount, total
        order.calculateTotals();

        // COD orders start as PENDING — admin must confirm before fulfilment
        if ("COD".equalsIgnoreCase(order.getPaymentMode())) {
            order.setStatus("PENDING");
        }

        // Save order first (items cascade-save with it)
        Order savedOrder = orderRepository.save(order);
        // Log order creation

        activityLogService.log(createdByUserId, creator.getFullName(), "CREATE_ORDER",
        "Created order " + savedOrder.getId() + " for " + savedOrder.getCustomerName() + " — ₱" + savedOrder.getTotal(),
        "ORDER", savedOrder.getId());
        

        // Record SALE in the accounting ledger — atomic with the order save.
        // If this throws the order is also rolled back; ledger stays clean.
        transactionService.recordSale(savedOrder, createdByUserId);

        // Cash on hand: an order paid in cash physically adds money to the drawer.
        // COD orders are "COD" (not CASH) and start PENDING — their cash is recorded
        // at collection time, not here.
        if ("CASH".equalsIgnoreCase(savedOrder.getPaymentMode())) {
            cashLedgerService.recordOrderCashSale(savedOrder, createdByUserId,
                    creator.getFullName(), LocalDate.now());
        }

        // Deduct stock — if this throws, the whole transaction rolls back.
        inventoryService.deductStockForOrder(savedOrder, createdByUserId);

        return savedOrder;
    }

    /**
     * Same as createOrder() but uses a caller-supplied date for the order ID prefix and createdAt.
     * Used exclusively by the batch import pipeline for backdating.
     *
     * The existing createOrder() method is intentionally left untouched — all live order creation
     * paths continue to call that method without any change.
     *
     * Key differences from createOrder():
     *   - order ID prefix = targetDate (so DailyReportService can find the order at close time)
     *   - createdAt is pre-set to targetDate@noon; @PrePersist skips because createdAt != null
     *   - activity log entry includes "(backdated ...)" for audit trail
     */
    @Transactional
    public Order createOrderAtDate(Order order, Long createdByUserId, LocalDate targetDate) {
        if (order.getSource() == null || !VALID_SOURCES.contains(order.getSource().toUpperCase())) {
            throw new RuntimeException("Invalid order source: '" + order.getSource()
                + "'. Must be one of: " + VALID_SOURCES);
        }
        order.setSource(order.getSource().toUpperCase());

        // ID prefix = target date so DailyReportService finds it at close time
        order.setId(orderIdGenerator.generateOrderIdForDate(targetDate));

        // Pre-set createdAt so @PrePersist leaves it alone (conditional guard on onCreate())
        order.setCreatedAt(targetDate.atTime(12, 0));

        User creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new RuntimeException("User not found: " + createdByUserId));
        order.setCreatedBy(creator);

        order.getItems().forEach(item -> item.setOrder(order));
        order.calculateTotals();

        if ("COD".equalsIgnoreCase(order.getPaymentMode())) {
            order.setStatus("PENDING");
        }

        Order savedOrder = orderRepository.save(order);

        activityLogService.log(createdByUserId, creator.getFullName(), "CREATE_ORDER",
            "Imported order " + savedOrder.getId() + " (backdated " + targetDate + ") for "
                + savedOrder.getCustomerName() + " — ₱" + savedOrder.getTotal(),
            "ORDER", savedOrder.getId());

        // Use the date-parameterised overload so the SALE transaction lands on targetDate
        // in the ledger — not on today. Existing recordSale(Order, Long) is untouched.
        transactionService.recordSale(savedOrder, createdByUserId, targetDate);
        // NOTE: deliberately NOT touching the cash-on-hand ledger here. This path is the
        // bulk/backdated import pipeline; cash on hand is a live drawer figure seeded from
        // an opening balance, so historical imports must not move it (would double-count).
        inventoryService.deductStockForOrder(savedOrder, createdByUserId);

        return savedOrder;
    }

    /**
     * Get today's orders (for the New Order view summary table)
     */
    public List<Order> getTodaysOrders() {
        return orderRepository.findByCreatedAtDate(LocalDate.now());
    }

    /**
     * Get all orders (for the Order List view)
     */
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    /**
     * Get a single order by ID
     */
    public Order getOrderById(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
    }

    /**
     * Update order status.
     * Valid transitions:
     *   ACTIVE → DELIVERED            (order completed/picked up)
     *   ACTIVE → PENDING              (put on hold)
     *   PENDING → ACTIVE              (resume after hold)
     *   PENDING → PENDING_COLLECTION  (force-close without immediate payment)
     *   PENDING_COLLECTION → ACTIVE   (reinstate after force-close)
     *   ACTIVE/PENDING → CANCELLED    (use cancelOrder instead — requires security key)
     */
    @Transactional
    public Order updateStatus(String orderId, String newStatus, Long changedByUserId) {
        Order order = getOrderById(orderId);
        String current = order.getStatus();

        if ("CANCELLED".equals(current)) {
            throw new RuntimeException("Cannot change status of a cancelled order");
        }

        // Strict transition matrix — only these three pairs are valid through this endpoint.
        // PENDING → PENDING_COLLECTION is set directly by DailyReportService (force-close path).
        // PENDING_COLLECTION → ACTIVE is intentionally blocked: that order has a deferralVoid
        // in the ledger and MUST go through collectOrder() to write the offsetting COLL-SALE.
        boolean validTransition = ("ACTIVE".equals(current)  && "DELIVERED".equals(newStatus))
                               || ("ACTIVE".equals(current)  && "PENDING".equals(newStatus))
                               || ("PENDING".equals(current) && "ACTIVE".equals(newStatus));
        if (!validTransition) {
            throw new RuntimeException(
                "Invalid status transition: " + current + " → " + newStatus
                + ". PENDING_COLLECTION orders must be resolved via the collect endpoint.");
        }

        order.setStatus(newStatus);
        Order savedOrder = orderRepository.save(order);

        // Resolve actor name from the user performing the change
        User changedBy = userRepository.findById(changedByUserId).orElse(null);
        String changedByName = changedBy != null ? changedBy.getFullName() : "Unknown";

        // Determine action label for the activity log
        String actionType;
        if ("ACTIVE".equals(newStatus) && ("PENDING".equals(current) || "ON_HOLD".equals(current))) {
            actionType = "ORDER_RESUMED";
        } else if ("PENDING".equals(newStatus) || "ON_HOLD".equals(newStatus)) {
            actionType = "ORDER_ON_HOLD";
        } else {
            actionType = "ORDER_STATUS_UPDATED";
        }

        activityLogService.log(changedByUserId, changedByName, actionType,
                "Order " + orderId + " status changed from " + current + " to " + newStatus
                + " for " + savedOrder.getCustomerName(),
                "ORDER", orderId);

        return savedOrder;
    }

    /**
     * Cancel an order and restore its stock.
     * Security key validation is handled in OrderController before this is called.
     */
    @Transactional
    public Order cancelOrder(String orderId, Long cancelledByUserId, String reason) {
        Order order = getOrderById(orderId);

        if ("CANCELLED".equals(order.getStatus())) {
            throw new RuntimeException("Order is already cancelled");
        }

        User cancelledBy = userRepository.findById(cancelledByUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Update order status
        order.setStatus("CANCELLED");
        order.setCancellationType("STANDARD");
        order.setCancelledBy(cancelledBy);
        order.setCancelledAt(LocalDateTime.now());
        order.setCancellationReason(reason);

        Order savedOrder = orderRepository.save(order);

        activityLogService.log(cancelledByUserId, cancelledBy.getFullName(), "CANCEL_ORDER",
            "Cancelled order " + orderId + " — Reason: " + reason,
            "ORDER", orderId);

        // M-26: Skip the VOID ledger entry for force-closed, uncollected orders.
        //
        // Three ledger lifecycles for a cancelled order:
        //
        //   1. Never deferred (normal ACTIVE/PENDING → CANCELLED):
        //      Ledger: SALE(+X). No COLL-DEFER exists.
        //      → write VOID(-X). Net = 0. ✓
        //
        //   2. Deferred, uncollected (PENDING_COLLECTION → CANCELLED):
        //      Ledger: SALE(+X) + COLL-DEFER(-X). No COLL-SALE. Net already = 0.
        //      A second VOID(-X) would produce net = -X. Skip it.
        //      → skip VOID. Net stays 0. ✓
        //
        //   3. Deferred then collected (DELIVERED from collection → CANCELLED):
        //      Ledger: SALE(+X) + COLL-DEFER(-X) + COLL-SALE(+X). Net = +X.
        //      Both COLL-DEFER and COLL-SALE exist → isDeferredAndUncollected = false.
        //      → write VOID(-X). Net = 0. ✓
        //
        // A COLL-DEFER-only guard (ignoring COLL-SALE) would incorrectly skip the
        // VOID in lifecycle 3, leaving the ledger at +X after cancel.
        if (!transactionService.isDeferredAndUncollected(savedOrder.getId())) {
            // NET basis: only void what the order still owes after any prior item-level voids.
            // Using gross total here would over-void by the already-voided amount, producing
            // a phantom debit equal to −voidedAmount in the ledger.
            BigDecimal grossTotal    = savedOrder.getTotal()       != null ? savedOrder.getTotal()       : BigDecimal.ZERO;
            BigDecimal alreadyVoided = savedOrder.getVoidedAmount() != null ? savedOrder.getVoidedAmount() : BigDecimal.ZERO;
            BigDecimal effectiveVoid = grossTotal.subtract(alreadyVoided);
            if (effectiveVoid.compareTo(BigDecimal.ZERO) > 0) {
                // Record VOID in the ledger — effective_date = today, NOT the original order date.
                // Historical daily reports are therefore never touched.
                transactionService.recordVoid(savedOrder, effectiveVoid, cancelledByUserId, reason);
            }
        }

        // Cash on hand: cancelling a cash order removes the cash it brought in.
        // No-op for non-cash orders (no CASH_SALE entry exists) and for deferred-
        // uncollected orders that were never collected in cash.
        cashLedgerService.reverseOrderCashSale(savedOrder.getId(), cancelledByUserId,
                cancelledBy.getFullName(), "CANCELLED: " + reason);

        // Restore stock back to inventory (also logs the CANCELLED_RETURN movement)
        inventoryService.restoreStockForCancelledOrder(savedOrder, cancelledByUserId);

        return savedOrder;
    }

    /**
     * Cancel an order with the intent to create a replacement.
     *
     * Called after the controller has already:
     *   - verified the JWT
     *   - confirmed the master key
     *   - confirmed the order is not already cancelled
     *   - confirmed disposition items are present for DELIVERED orders
     *
     * This method owns the transactional boundary. All writes (inventory
     * movements, order status, ledger entry, activity log) are atomic.
     *
     * replacementOrderId is NOT written here — it is null until the replacement
     * order is created in Step 5 of the build sequence.
     *
     * @param orderId  the order to cancel
     * @param request  validated request (master key already checked by caller)
     * @param userId   authenticated user performing the cancellation
     * @return the updated order
     */
    @Transactional
    public Order cancelOrderForReplacement(String orderId,
                                           CancelForReplacementRequest request,
                                           Long userId) {

        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if ("CANCELLED".equals(order.getStatus())) {
            throw new RuntimeException("Order is already cancelled");
        }

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        boolean isDelivered = "DELIVERED".equals(order.getStatus());

        // Build orderItemId → disposition and orderItemId → restockWarehouse lookups
        Map<Long, String> dispositionMap = new java.util.HashMap<>();
        Map<Long, String> destinationMap = new java.util.HashMap<>();
        if (request.getItems() != null) {
            for (CancelForReplacementRequest.CancelItemDisposition d : request.getItems()) {
                if (d.getOrderItemId() != null) {
                    if (d.getDisposition() != null)
                        dispositionMap.put(d.getOrderItemId(), d.getDisposition());
                    if (d.getRestockWarehouse() != null)
                        destinationMap.put(d.getOrderItemId(), d.getRestockWarehouse());
                }
            }
        }

        // Validate before any writes
        if (isDelivered) {
            // DELIVERED: every item needs a disposition; SELLABLE items also need a warehouse
            for (OrderItem item : order.getItems()) {
                String disp = dispositionMap.get(item.getId());
                if (disp == null || disp.isBlank()) {
                    throw new RuntimeException(
                        "Disposition (SELLABLE or REJECTED) is required for \""
                        + item.getProductName() + "\" because the order is DELIVERED");
                }
                if (!disp.equalsIgnoreCase("SELLABLE") && !disp.equalsIgnoreCase("REJECTED")) {
                    throw new RuntimeException(
                        "Invalid disposition \"" + disp + "\" for \""
                        + item.getProductName() + "\". Must be SELLABLE or REJECTED.");
                }
                if (disp.equalsIgnoreCase("SELLABLE"))
                    inventoryService.requireValidWarehouse(
                        destinationMap.get(item.getId()), item.getProductName());
            }
        } else {
            // Non-DELIVERED: all items auto-SELLABLE; warehouse required for every active line
            for (OrderItem item : order.getItems()) {
                int alreadyVoided = item.getVoidedQuantity() != null ? item.getVoidedQuantity() : 0;
                if (item.getQuantity() - alreadyVoided > 0)
                    inventoryService.requireValidWarehouse(
                        destinationMap.get(item.getId()), item.getProductName());
            }
        }

        // Inventory side-effects: stock restore + movement records per item
        inventoryService.restoreStockForCancelledWithDisposition(
                order, dispositionMap, destinationMap, isDelivered, userId);

        // Update order — replacementOrderId left null until Step 5
        order.setStatus("CANCELLED");
        order.setCancellationType("REPLACEMENT");
        order.setCancelledAt(LocalDateTime.now());
        order.setCancelledBy(actor);
        order.setCancellationReason(request.getReason());

        Order savedOrder = orderRepository.save(order);

        // Ledger VOID entry — net basis: effectiveVoid = total − voidedAmount so
        // prior item-level voids are not double-counted.
        //
        // M-26: Same deferred-uncollected guard as cancelOrder().
        // A PENDING_COLLECTION order that has a COLL-DEFER but no COLL-SALE already
        // has a net of ₱0 in the ledger — a second VOID would drive it to -X.
        BigDecimal grossTotal    = savedOrder.getTotal()       != null ? savedOrder.getTotal()       : BigDecimal.ZERO;
        BigDecimal alreadyVoided = savedOrder.getVoidedAmount() != null ? savedOrder.getVoidedAmount() : BigDecimal.ZERO;
        BigDecimal effectiveVoid = grossTotal.subtract(alreadyVoided);
        if (effectiveVoid.compareTo(BigDecimal.ZERO) > 0
                && !transactionService.isDeferredAndUncollected(savedOrder.getId())) {
            transactionService.recordVoid(savedOrder, effectiveVoid, userId, request.getReason());
        }

        activityLogService.log(userId, actor.getFullName(), "CANCEL_FOR_REPLACEMENT",
                "Cancelled order " + orderId + " for replacement — Reason: " + request.getReason(),
                "ORDER", orderId);

        return savedOrder;
    }

    /**
     * Create a replacement order linked to a previously cancelled-for-replacement order.
     *
     * Called after the controller has already:
     *   - verified the JWT
     *   - validated customerName and items are present
     *   - confirmed the original order exists with cancellationType = REPLACEMENT
     *   - confirmed replacementOrderId is null (no duplicate replacement)
     *
     * This method owns the transactional boundary.  All writes are atomic:
     *   new order save, stock deduction, SALE ledger entry, and the write-back of
     *   replacementOrderId onto the original order.  If the write-back fails,
     *   everything rolls back — including the new order row and stock deduction.
     *
     * No elevated key required — standard login is sufficient per Section 2.
     *
     * @param originalOrderId  the cancelled order being replaced
     * @param request          staff-provided fields for the new order
     * @param userId           authenticated user performing the creation
     * @return the saved replacement order (originalOrderId is set; id is the new order ID)
     */
    @Transactional
    public Order createReplacementOrder(String originalOrderId,
                                        CreateOrderRequest request,
                                        Long userId) {

        // ── Validate original order ───────────────────────────────────────
        Order original = orderRepository.findById(originalOrderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + originalOrderId));

        if (!"REPLACEMENT".equals(original.getCancellationType())) {
            throw new RuntimeException(
                "The specified order was not cancelled for replacement");
        }
        if (original.getReplacementOrderId() != null) {
            throw new RuntimeException(
                "A replacement order has already been created for this order: "
                + original.getReplacementOrderId());
        }

        // ── Validate source ───────────────────────────────────────────────
        if (request.getSource() == null
                || !VALID_SOURCES.contains(request.getSource().toUpperCase())) {
            throw new RuntimeException("Invalid order source: '" + request.getSource()
                + "'. Must be one of: " + VALID_SOURCES);
        }

        // ── Generate order ID ─────────────────────────────────────────────
        String newOrderId = orderIdGenerator.generateOrderId();

        // ── Load creator ──────────────────────────────────────────────────
        User creator = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // ── Build replacement order ───────────────────────────────────────
        Order replacement = new Order();
        replacement.setId(newOrderId);
        replacement.setCustomerName(request.getCustomerName());
        replacement.setSource(request.getSource().toUpperCase());
        replacement.setAgentName(request.getAgentName());
        replacement.setFbPage(request.getFbPage());
        replacement.setEcommercePlatform(request.getEcommercePlatform());
        replacement.setPaymentMode(request.getPaymentMode() != null ? request.getPaymentMode() : "CASH");
        replacement.setOrderType(request.getOrderType() != null ? request.getOrderType() : "STANDARD");
        replacement.setAddress(request.getAddress());
        replacement.setDiscount(request.getDiscount() != null ? request.getDiscount() : BigDecimal.ZERO);
        replacement.setDeliveryFee(request.getDeliveryFee() != null ? request.getDeliveryFee() : BigDecimal.ZERO);
        replacement.setNotes(request.getNotes());
        replacement.setOriginalOrderId(originalOrderId);
        replacement.setCreatedBy(creator);

        // COD orders start as PENDING (same rule as regular order creation)
        if ("COD".equalsIgnoreCase(replacement.getPaymentMode())) {
            replacement.setStatus("PENDING");
        }

        // ── Link items ────────────────────────────────────────────────────
        request.getItems().forEach(itemReq -> {
            OrderItem item = new OrderItem();
            item.setProductId(itemReq.getProductId());
            item.setProductName(itemReq.getProductName());
            item.setQuantity(itemReq.getQuantity());
            item.setUnitPrice(itemReq.getUnitPrice());
            item.setWarehouse(itemReq.getWarehouse());
            replacement.addItem(item);
        });

        // ── Calculate totals ──────────────────────────────────────────────
        replacement.calculateTotals();

        // ── Save replacement order (items cascade-save with it) ───────────
        Order savedReplacement = orderRepository.save(replacement);

        // ── Activity log ──────────────────────────────────────────────────
        activityLogService.log(userId, creator.getFullName(), "CREATE_REPLACEMENT_ORDER",
            "Created replacement order " + savedReplacement.getId()
                + " for " + savedReplacement.getCustomerName()
                + " — replaces order " + originalOrderId
                + " — ₱" + savedReplacement.getTotal(),
            "ORDER", savedReplacement.getId());

        // ── Record SALE in the accounting ledger ──────────────────────────
        transactionService.recordSale(savedReplacement, userId);

        // ── Deduct stock ──────────────────────────────────────────────────
        inventoryService.deductStockForOrder(savedReplacement, userId);

        // ── Write-back: close the two-way link on the original order ──────
        // This is the last write.  If it throws, the entire transaction rolls
        // back — including the new order row, stock movements, and SALE entry.
        // The original order's replacementOrderId stays null: clean state.
        original.setReplacementOrderId(savedReplacement.getId());
        orderRepository.save(original);

        return savedReplacement;
    }

    /**
     * Search orders by customer name
     */
    public List<Order> searchByCustomerName(String customerName) {
        return orderRepository.findByCustomerNameContainingIgnoreCaseOrderByCreatedAtDesc(customerName);
    }

    /**
     * Get orders within a date range (for Order History view, #8)
     */
    public List<Order> getOrdersByDateRange(LocalDate start, LocalDate end) {
        return orderRepository.findByDateRange(start, end);
    }

    /**
     * Process a post-sale return for one or more items on an order.
     *
     * Called after the controller has already:
     *   - verified the JWT
     *   - validated the admin security key
     *   - confirmed items list is non-empty
     *
     * This method owns the transactional boundary.  Inventory movements, the
     * optional refund ledger entry, the refundedAt timestamp update, and the
     * activity log entry are all atomic — if any step fails, everything rolls back.
     *
     * No order status change is made.  A return is a post-sale stock/financial
     * adjustment; the order lifecycle status is not affected.
     *
     * @param orderId  the order items are being returned against
     * @param request  validated request (security key already checked by caller)
     * @param userId   authenticated user performing the return
     * @return a result map with orderId, returnedItems detail, refundIssued, refundAmount
     */
    @Transactional
    public Map<String, Object> processReturn(String orderId,
                                             ReturnOrderRequest request,
                                             Long userId) {

        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Build orderItemId → OrderItem lookup
        Map<Long, OrderItem> itemMap = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, i -> i));

        // ── Validate all return items before any writes ──────────────────
        for (ReturnOrderRequest.ReturnItemRequest req : request.getItems()) {
            if (req.getOrderItemId() == null)
                throw new RuntimeException("Each return item must include an orderItemId");

            OrderItem item = itemMap.get(req.getOrderItemId());
            if (item == null)
                throw new RuntimeException(
                    "Item id " + req.getOrderItemId() + " does not belong to order " + orderId);

            int total    = req.getTotalReturned()  != null ? req.getTotalReturned()  : 0;
            int sellable = req.getSellableQty()    != null ? req.getSellableQty()    : 0;
            int rejected = req.getRejectedQty()    != null ? req.getRejectedQty()    : 0;

            if (total <= 0)
                throw new RuntimeException(
                    "totalReturned must be greater than 0 for \"" + item.getProductName() + "\"");
            if (sellable < 0 || rejected < 0)
                throw new RuntimeException(
                    "sellableQty and rejectedQty must be 0 or greater for \"" + item.getProductName() + "\"");
            if (sellable + rejected != total)
                throw new RuntimeException(
                    "sellableQty + rejectedQty must equal totalReturned for \""
                    + item.getProductName() + "\" (got " + sellable + " + " + rejected
                    + " = " + (sellable + rejected) + ", expected " + total + ")");
            if (total > item.getQuantity())
                throw new RuntimeException(
                    "Cannot return " + total + " unit(s) of \"" + item.getProductName()
                    + "\" — the original order quantity was " + item.getQuantity());
            if (sellable > 0)
                inventoryService.requireValidWarehouse(
                    req.getRestockWarehouse(), item.getProductName());
        }

        // ── Apply inventory side-effects per item ────────────────────────
        List<Map<String, Object>> resultItems = new ArrayList<>();

        for (ReturnOrderRequest.ReturnItemRequest req : request.getItems()) {
            OrderItem item     = itemMap.get(req.getOrderItemId());
            int sellable       = req.getSellableQty()   != null ? req.getSellableQty()   : 0;
            int rejected       = req.getRejectedQty()   != null ? req.getRejectedQty()   : 0;

            inventoryService.processReturnForItem(
                item, sellable, rejected, req.getRestockWarehouse(), orderId, userId);

            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("orderItemId",   item.getId());
            detail.put("productName",   item.getProductName());
            detail.put("totalReturned", req.getTotalReturned());
            detail.put("sellableQty",   sellable);
            detail.put("rejectedQty",   rejected);
            resultItems.add(detail);
        }

        // ── Optional refund ──────────────────────────────────────────────
        boolean refundIssued = false;
        BigDecimal refundAmt = BigDecimal.ZERO;

        if (request.getRefundAmount() != null
                && request.getRefundAmount().compareTo(BigDecimal.ZERO) > 0) {
            refundAmt = request.getRefundAmount();
            // Mark order as refunded (first refund sets the timestamp; subsequent
            // returns leave it unchanged — same convention as the existing refund endpoint)
            if (order.getRefundedAt() == null) {
                order.setRefundedAt(OffsetDateTime.now());
                orderRepository.save(order);
            }
            transactionService.recordReturnRefund(orderId, refundAmt,
                    request.getReason(), userId, actor.getFullName());
            // Cash on hand: a cash refund hands money back out of the drawer.
            // No-op for non-cash orders; capped at the order's remaining cash inflow.
            cashLedgerService.reverseOrderCashPartial(orderId, refundAmt, userId,
                    actor.getFullName(), "refund on return");
            refundIssued = true;
        }

        // ── Activity log ─────────────────────────────────────────────────
        String logDetail = "Return on order " + orderId
                + " — " + resultItems.size() + " item(s)"
                + (refundIssued ? ", refund ₱" + refundAmt : "")
                + " — Reason: " + request.getReason();
        activityLogService.log(userId, actor.getFullName(), "RETURN_ORDER",
                logDetail, "ORDER", orderId);

        // ── Build result ─────────────────────────────────────────────────
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("orderId",       orderId);
        result.put("returnedItems", resultItems);
        result.put("refundIssued",  refundIssued);
        result.put("refundAmount",  refundAmt);
        return result;
    }

    /**
     * "Correct Recorded Item" failsafe — swap one wrongly-recorded order item for
     * the correct product/quantity/price.  Standalone feature: shares no code path
     * with return / replacement / void.
     *
     * Atomic sequence (all-or-nothing):
     *   1. Restore the recorded item's stock to its origin warehouse, and deduct the
     *      replacement product's stock from the chosen warehouse (validates stock).
     *   2. Mutate the order_item in place to the replacement and recompute the order total.
     *   3. Post one ADJUSTMENT to the ledger for (new value − old value), dated TODAY,
     *      so closed daily reports stay immutable and the correction lands on the current day.
     *   4. Write a CORRECT_ORDER_ITEM audit entry.
     *
     * Caller (controller) has already verified JWT, role, and the admin security key.
     */
    @Transactional
    public Map<String, Object> correctRecordedItem(String orderId,
                                                   CorrectItemRequest request,
                                                   Long userId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if ("CANCELLED".equals(order.getStatus()))
            throw new RuntimeException("Cannot correct an item on a cancelled order");

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (request.getOrderItemId() == null)
            throw new RuntimeException("orderItemId is required");
        OrderItem item = order.getItems().stream()
                .filter(i -> i.getId().equals(request.getOrderItemId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Item id " + request.getOrderItemId() + " does not belong to order " + orderId));

        // Guard: a partially-voided line is excluded — its stock/ledger state is
        // entangled with the void flow, so correcting it could double-count.
        int alreadyVoided = item.getVoidedQuantity() != null ? item.getVoidedQuantity() : 0;
        if (alreadyVoided > 0)
            throw new RuntimeException("This item has voided units and cannot be corrected. "
                    + "Use the void/return flow instead.");

        if (request.getReplacementProductId() == null)
            throw new RuntimeException("A replacement product must be selected");
        Product replacement = productRepository.findById(request.getReplacementProductId())
                .orElseThrow(() -> new RuntimeException("Replacement product not found"));
        if (!Boolean.TRUE.equals(replacement.getActive()))
            throw new RuntimeException("Replacement product \"" + replacement.getName() + "\" is inactive");

        int newQty = request.getReplacementQty() != null ? request.getReplacementQty() : 0;
        if (newQty <= 0)
            throw new RuntimeException("Replacement quantity must be at least 1");
        BigDecimal newUnit = request.getReplacementUnitPrice();
        if (newUnit == null || newUnit.compareTo(BigDecimal.ZERO) <= 0)
            throw new RuntimeException("Replacement unit price must be greater than 0");

        // Snapshot the recorded (old) values before mutation
        String     oldName  = item.getProductName();
        int        oldQty   = item.getQuantity() != null ? item.getQuantity() : 0;
        BigDecimal oldUnit  = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal oldValue = item.getSubtotal() != null
                ? item.getSubtotal()
                : oldUnit.multiply(BigDecimal.valueOf(oldQty));
        BigDecimal newValue = newUnit.multiply(BigDecimal.valueOf(newQty));
        BigDecimal delta    = newValue.subtract(oldValue);

        // 1. Inventory: restore recorded item + deduct replacement (validates stock, may throw)
        String destWh = inventoryService.requireValidWarehouse(request.getWarehouse(), replacement.getName());
        inventoryService.applyItemCorrection(item, replacement, newQty, destWh, orderId, userId);

        // 2. Mutate the order line to the replacement, recompute the order total
        item.setProductId(replacement.getId());
        item.setProductName(replacement.getName());
        item.setQuantity(newQty);
        item.setUnitPrice(newUnit);
        item.setSubtotal(newValue);
        item.setWarehouse(destWh);
        order.calculateTotals();
        orderRepository.save(order);

        // 3. Ledger: post the net difference as an ADJUSTMENT dated today (skip if zero)
        String summary = "Item correction on " + orderId + ": \"" + oldName + "\" ("
                + oldQty + "×₱" + oldUnit + ") → \"" + replacement.getName() + "\" ("
                + newQty + "×₱" + newUnit + "), net ₱" + delta
                + (request.getReason() != null && !request.getReason().isBlank()
                    ? " — " + request.getReason() : "");
        if (delta.compareTo(BigDecimal.ZERO) != 0) {
            transactionService.recordAdjustment(orderId, delta, summary, userId, actor.getFullName());
        }

        // 4. Audit
        activityLogService.log(userId, actor.getFullName(), "CORRECT_ORDER_ITEM",
                summary, "ORDER", orderId);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("orderId",        orderId);
        result.put("orderItemId",    item.getId());
        result.put("oldProductName", oldName);
        result.put("oldQty",         oldQty);
        result.put("oldUnitPrice",   oldUnit);
        result.put("newProductName", replacement.getName());
        result.put("newQty",         newQty);
        result.put("newUnitPrice",   newUnit);
        result.put("netAdjustment",  delta);
        result.put("newOrderTotal",  order.getTotal());
        return result;
    }

    /**
     * Apply an item-level void to an order.
     *
     * Called after the controller has already:
     *   - verified the JWT
     *   - confirmed today's daily report is not closed
     *   - confirmed the order was created today
     *   - determined the tier and validated the appropriate key
     *
     * This method owns the transactional boundary.  Every write inside
     * (item quantity updates, voided_amount update, ledger entry, movement
     * records, activity log) either all commit or all roll back.
     *
     * @param orderId   the order to void items from
     * @param request   validated void request (items, reason, keys already checked)
     * @param tier      "TIER_1" or "TIER_2" — determined by caller
     * @param userId    authenticated user performing the void
     * @return a result map with orderId, tier, voidedNow, totalVoidedAmount,
     *         effectiveTotal, and per-item details
     */
    @Transactional
    public Map<String, Object> voidOrderItems(String orderId,
                                              VoidOrderRequest request,
                                              String tier,
                                              Long userId) {

        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if ("CANCELLED".equals(order.getStatus())) {
            throw new RuntimeException("Cannot void items on a cancelled order");
        }

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        boolean isDelivered = "DELIVERED".equals(order.getStatus());

        // Build a quick lookup map: orderItemId → OrderItem
        Map<Long, OrderItem> itemMap = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, i -> i));

        // ── Validate all requested items before any writes ──────────────
        for (VoidOrderRequest.VoidItemRequest req : request.getItems()) {
            if (req.getOrderItemId() == null || req.getVoidQuantity() == null || req.getVoidQuantity() <= 0)
                throw new RuntimeException("Each void item must have a valid orderItemId and voidQuantity > 0");

            OrderItem item = itemMap.get(req.getOrderItemId());
            if (item == null)
                throw new RuntimeException("Item id " + req.getOrderItemId() + " does not belong to order " + orderId);

            int alreadyVoided = item.getVoidedQuantity() != null ? item.getVoidedQuantity() : 0;
            int remaining     = item.getQuantity() - alreadyVoided;
            if (req.getVoidQuantity() > remaining)
                throw new RuntimeException(
                    "Cannot void " + req.getVoidQuantity() + " unit(s) of \""
                    + item.getProductName() + "\" — only " + remaining + " active unit(s) remain");

            if (isDelivered && (req.getDisposition() == null || req.getDisposition().isBlank()))
                throw new RuntimeException(
                    "Disposition (SELLABLE or REJECTED) is required for \"" + item.getProductName()
                    + "\" because the order is DELIVERED");

            boolean willRestock = !isDelivered || "SELLABLE".equalsIgnoreCase(req.getDisposition());
            if (willRestock)
                inventoryService.requireValidWarehouse(req.getRestockWarehouse(), item.getProductName());
        }

        // ── Apply void quantities and accumulate monetary amount ─────────
        BigDecimal voidedNow = BigDecimal.ZERO;
        List<Map<String, Object>> resultItems = new ArrayList<>();

        for (VoidOrderRequest.VoidItemRequest req : request.getItems()) {
            OrderItem item = itemMap.get(req.getOrderItemId());
            int alreadyVoided = item.getVoidedQuantity() != null ? item.getVoidedQuantity() : 0;

            // Update voided quantity
            item.setVoidedQuantity(alreadyVoided + req.getVoidQuantity());

            // Monetary value of this line's void
            BigDecimal lineVoidValue = item.getUnitPrice()
                    .multiply(BigDecimal.valueOf(req.getVoidQuantity()));
            voidedNow = voidedNow.add(lineVoidValue);

            // Inventory side-effect (stock restore + movement record)
            String disposition = req.getDisposition() != null ? req.getDisposition() : "SELLABLE";
            String inventoryOutcome = inventoryService.restoreStockForVoidedItem(
                    item, req.getVoidQuantity(), disposition, isDelivered,
                    req.getRestockWarehouse(), orderId, userId);

            // Collect result detail for this item
            int newVoided    = item.getVoidedQuantity();
            int newRemaining = item.getQuantity() - newVoided;
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("orderItemId",       item.getId());
            detail.put("productName",       item.getProductName());
            detail.put("originalQuantity",  item.getQuantity());
            detail.put("voidedQuantity",    newVoided);
            detail.put("remainingQuantity", newRemaining);
            detail.put("disposition",       disposition);
            detail.put("inventoryOutcome",  inventoryOutcome);
            resultItems.add(detail);
        }

        // ── Update order's running voided_amount total ───────────────────
        BigDecimal prevVoided    = order.getVoidedAmount() != null ? order.getVoidedAmount() : BigDecimal.ZERO;
        BigDecimal totalVoided   = prevVoided.add(voidedNow);
        order.setVoidedAmount(totalVoided);

        // ── Tier 2: mark order as fully voided / cancelled ───────────────
        if ("TIER_2".equals(tier)) {
            order.setStatus("CANCELLED");
            order.setCancelledAt(LocalDateTime.now());
            order.setCancelledBy(actor);
            order.setCancellationReason(request.getReason());
            order.setCancellationType("VOIDED");
        }

        orderRepository.save(order);

        // ── Ledger entry ─────────────────────────────────────────────────
        transactionService.recordItemVoid(orderId, voidedNow,
                request.getReason(), userId, actor.getFullName());

        // Cash on hand: voiding item(s) on a cash order removes that cash.
        // No-op for non-cash orders; capped at the order's remaining cash inflow.
        cashLedgerService.reverseOrderCashPartial(orderId, voidedNow, userId,
                actor.getFullName(), "item void");

        // ── Activity log (ledger method also logs — this entry adds tier context)
        StringBuilder logDesc = new StringBuilder();
        logDesc.append("Void (").append(tier).append(") on order ").append(orderId)
               .append(" — ₱").append(voidedNow).append(" removed — Reason: ").append(request.getReason());
        for (Map<String, Object> d : resultItems) {
            String outcome = (String) d.get("inventoryOutcome");
            if (outcome != null) logDesc.append(" | ").append(outcome);
        }
        activityLogService.log(userId, actor.getFullName(),
                "TIER_2".equals(tier) ? "VOID_ORDER_FULL" : "VOID_ORDER_PARTIAL",
                logDesc.toString(),
                "ORDER", orderId);

        // ── Build and return result ───────────────────────────────────────
        BigDecimal effectiveTotal = (order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO)
                .subtract(totalVoided);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("orderId",           orderId);
        result.put("tier",              tier);
        result.put("voidedNow",         voidedNow);
        result.put("totalVoidedAmount", totalVoided);
        result.put("effectiveTotal",    effectiveTotal);
        result.put("voidedItems",       resultItems);
        return result;
    }

    @Transactional
    public BatchCollectResult batchMarkAsCollected(List<String> orderIds, Long userId, String callerName) {
        int collected = 0;
        List<Map<String, Object>> skipped  = new ArrayList<>();
        List<Map<String, Object>> errors   = new ArrayList<>();

        for (String orderId : orderIds) {
            try {
                Order order = orderRepository.findByIdForUpdateWithItems(orderId).orElse(null);
                if (order == null) {
                    skipped.add(Map.of("orderId", orderId, "reason", "Order not found"));
                    continue;
                }

                String status = order.getStatus();
                if (!"PENDING_COLLECTION".equals(status) && !"PENDING".equals(status)) {
                    skipped.add(Map.of("orderId", orderId, "reason", "Not in collectable state: " + status));
                    continue;
                }

                order.setStatus("DELIVERED");
                order.setCollectedAt(OffsetDateTime.now());
                order.setCollectedBy(callerName);
                orderRepository.save(order);

                LocalDate originalDate = order.getCreatedAt().toLocalDate();

                if ("PENDING_COLLECTION".equals(status)) {
                    transactionService.recordCollectionSale(order, userId);
                    try { commissionService.createEntriesForOrder(order, userId); }
                    catch (Exception e) {
                        log.warn("Failed to create commission entries for order {}: {}", order.getId(), e.getMessage());
                    }

                    Optional<DailyReport> reportOpt = dailyReportRepository.findByReportDate(originalDate);
                    if (reportOpt.isPresent()) {
                        DailyReport report = reportOpt.get();
                        BigDecimal amount = order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO;
                        report.setGrossSales(report.getGrossSales() != null
                                ? report.getGrossSales().add(amount) : amount);
                        report.setNetSales(report.getNetSales() != null
                                ? report.getNetSales().add(amount) : amount);
                        report.setTotalRevenue(report.getTotalRevenue() != null
                                ? report.getTotalRevenue().add(amount) : amount);
                        report.setTotalOrders(report.getTotalOrders() + 1);
                        report.setUnfulfilledOrders(Math.max(0, report.getUnfulfilledOrders() - 1));
                        BigDecimal ua = report.getUnfulfilledAmount() != null
                                ? report.getUnfulfilledAmount() : BigDecimal.ZERO;
                        report.setUnfulfilledAmount(ua.subtract(amount).max(BigDecimal.ZERO));
                        dailyReportRepository.save(report);
                    }
                }

                activityLogService.log(userId, callerName, "ORDER_COLLECT",
                        "Collected payment for order " + orderId + " — ₱" + order.getTotal()
                                + " (original date: " + originalDate + ")",
                        "ORDER", orderId);

                // Cash on hand: batch COD collections are taken in cash. Idempotent.
                cashLedgerService.recordOrderCashSale(order, userId, callerName, LocalDate.now());

                collected++;
            } catch (Exception e) {
                errors.add(Map.of("orderId", orderId, "error", e.getMessage()));
            }
        }

        return new BatchCollectResult(collected, skipped, errors);
    }

    public static class BatchCollectResult {
        public final int collected;
        public final List<Map<String, Object>> skipped;
        public final List<Map<String, Object>> errors;

        public BatchCollectResult(int collected,
                                  List<Map<String, Object>> skipped,
                                  List<Map<String, Object>> errors) {
            this.collected = collected;
            this.skipped   = skipped;
            this.errors    = errors;
        }
    }
}
