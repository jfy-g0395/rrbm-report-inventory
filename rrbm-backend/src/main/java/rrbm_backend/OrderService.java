package rrbm_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        "WALK_IN", "IN_HOUSE", "AGENT", "ECOMMERCE", "FACEBOOK_PAGE", "RESELLER", "DISTRIBUTOR"
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
    private final AgentRepository    agentRepository;
    private final ResellerRepository resellerRepository;

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
                        CashLedgerService cashLedgerService,
                        AgentRepository agentRepository,
                        ResellerRepository resellerRepository) {
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
        this.agentRepository    = agentRepository;
        this.resellerRepository = resellerRepository;
    }

    /**
     * Builds (but does not persist) an {@link Order} from a {@link CreateOrderRequest},
     * including payload validation, agent linking, and per-item commission (opAmount) setup.
     *
     * Shared by the live {@code POST /api/orders} path and the backdated "Add Records" path
     * (S2) so both produce identical orders — same source, agent, payment mode, multi-item and
     * commission handling. This is the core accuracy fix: one builder, no re-implementation.
     *
     * On any validation failure this throws {@link RuntimeException} carrying a user-facing
     * message; callers translate that to a 400 response (the live createOrder catch block and
     * the per-row try/catch in the backdated commit both already do this).
     *
     * Does NOT save, generate the order ID, deduct stock, or touch the ledger/cash — that is
     * the job of {@link #createOrder} / {@code createOrderAtDate}. Does NOT create commission
     * entries (callers do that post-save via CommissionService); it only sets {@code opAmount}.
     *
     * @param userId the acting user (reserved for future audit use; build is user-independent)
     */
    public Order buildOrderFromRequest(CreateOrderRequest request, Long userId) {
        // ── Payload validation (M-21): a direct API call bypasses frontend checks ──
        String cname = request.getCustomerName();
        if (cname == null || cname.trim().isEmpty())
            throw new RuntimeException("Customer name is required");
        if (request.getItems() == null || request.getItems().isEmpty())
            throw new RuntimeException("Order must have at least one item");
        for (CreateOrderRequest.OrderItemRequest it : request.getItems()) {
            if (it.getQuantity() == null || it.getQuantity() <= 0)
                throw new RuntimeException("Item quantity must be at least 1");
            if (it.getUnitPrice() == null || it.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0)
                throw new RuntimeException("Item unit price must be greater than 0 for: "
                    + (it.getProductName() != null ? it.getProductName() : "unknown"));
            if (it.getProductId() == null)
                throw new RuntimeException("Item \"" + (it.getProductName() != null ? it.getProductName() : "unknown")
                    + "\" must be selected from the product catalog");
            if (!productRepository.existsById(it.getProductId()))
                throw new RuntimeException("Product \"" + (it.getProductName() != null ? it.getProductName() : "ID " + it.getProductId())
                    + "\" no longer exists in the catalog");
        }

        // ── Build Order entity from request ──
        Order order = new Order();
        order.setCustomerName(request.getCustomerName());
        order.setSource(request.getSource());
        order.setAgentName(request.getAgentName());
        order.setFbPage(request.getFbPage());
        order.setEcommercePlatform(request.getEcommercePlatform());
        order.setPaymentMode(request.getPaymentMode() != null ? request.getPaymentMode() : "CASH");
        order.setOrderType(request.getOrderType() != null ? request.getOrderType() : "STANDARD");
        order.setAddress(request.getAddress());
        order.setDiscount(request.getDiscount() != null ? request.getDiscount() : BigDecimal.ZERO);
        order.setDeliveryFee(request.getDeliveryFee() != null ? request.getDeliveryFee() : BigDecimal.ZERO);
        order.setNotes(request.getNotes());
        // Deferred delivery (V93): presence of a scheduled date makes createOrder route
        // this into the inert SCHEDULED_DELIVERY flow (records nothing until fulfilled).
        order.setScheduledDeliveryDate(request.getScheduledDeliveryDate());

        // ── A2: Agent linking — look up registered agent if agentId is supplied ──
        final Agent linkedAgent;
        if (request.getAgentId() != null) {
            Agent found = agentRepository.findById(request.getAgentId()).orElse(null);
            if (found == null || "INACTIVE".equals(found.getStatus()))
                throw new RuntimeException("Agent not found");
            order.setAgentId(found.getId());
            order.setAgentName(found.getFullName());
            linkedAgent = found;
        } else {
            linkedAgent = null;
        }

        // ── S-A1: Reseller/Distributor linking — parallel to agent linking ──
        // When a registered reseller is chosen, link the FK and reuse agent_name for its
        // display name (keeps existing name-based display/reporting paths working). Free-text
        // names remain accepted at the API level for backward compatibility (e.g. CSV import);
        // the New Order UI restricts these sources to registered, ACTIVE resellers.
        if (request.getResellerId() != null) {
            Reseller reseller = resellerRepository.findById(request.getResellerId()).orElse(null);
            if (reseller == null || "INACTIVE".equals(reseller.getStatus()))
                throw new RuntimeException("Reseller/Distributor not found or inactive");
            order.setResellerId(reseller.getId());
            order.setAgentName(reseller.getName());
        }

        // ── Build OrderItems (+ commission opAmount, U15 flat-amount model) ──
        request.getItems().forEach(itemReq -> {
            OrderItem item = new OrderItem();
            item.setProductId(itemReq.getProductId());
            item.setProductName(itemReq.getProductName());
            item.setQuantity(itemReq.getQuantity());
            item.setUnitPrice(itemReq.getUnitPrice());
            item.setWarehouse(itemReq.getWarehouse());
            if (linkedAgent != null
                    && itemReq.getBasePrice() != null
                    && itemReq.getOpPerUnit() != null) {
                item.setBasePrice(itemReq.getBasePrice());
                item.setOpPerUnit(itemReq.getOpPerUnit());
                // Commission = opPerUnit × quantity
                item.setOpAmount(
                    itemReq.getOpPerUnit()
                           .multiply(new BigDecimal(itemReq.getQuantity()))
                           .setScale(5, RoundingMode.HALF_UP)
                );
            }
            order.addItem(item);
        });

        return order;
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

        // Deferred delivery (V93): record NOTHING now — no stock, no sale, no commission,
        // no cash. The order sits inert as SCHEDULED_DELIVERY until it is fulfilled (recorded
        // on the delivery day) or cancelled (nothing recorded). Takes precedence over the COD
        // routing below so a scheduled COD order is still SCHEDULED_DELIVERY, not PENDING.
        if (order.getScheduledDeliveryDate() != null) {
            return createScheduledDelivery(order, creator, createdByUserId);
        }

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
     * Persist a deferred-delivery order (V93) without recording anything.
     *
     * Called from {@link #createOrder} when the request carries a scheduledDeliveryDate.
     * The order is saved with status SCHEDULED_DELIVERY and an opening change-log line, but
     * NO stock is deducted, NO SALE is posted, NO commission is created, and NO cash is
     * booked. All of that happens later in {@link #fulfillScheduledDelivery} on the delivery
     * day. The controller must likewise skip its post-create commission call for this status.
     */
    private Order createScheduledDelivery(Order order, User creator, Long createdByUserId) {
        LocalDate date = order.getScheduledDeliveryDate();
        if (date.isBefore(LocalDate.now())) {
            throw new RuntimeException("Scheduled delivery date cannot be in the past");
        }
        order.setStatus("SCHEDULED_DELIVERY");
        order.appendDeliveryLog(LocalDate.now() + " — scheduled for delivery on " + date
                + " by " + creator.getFullName());

        Order savedOrder = orderRepository.save(order);

        activityLogService.log(createdByUserId, creator.getFullName(), "CREATE_ORDER",
                "Created scheduled-delivery order " + savedOrder.getId() + " for "
                        + savedOrder.getCustomerName() + " — ₱" + savedOrder.getTotal()
                        + " — delivery " + date + " (records nothing until delivered)",
                "ORDER", savedOrder.getId());

        return savedOrder;
    }

    /**
     * Fulfil a SCHEDULED_DELIVERY order (V93) — "Mark Delivered" and "Deliver now" both land here.
     *
     * This is the moment a deferred order becomes real: recorded on the delivery day (today).
     * Modelled on {@link #batchMarkAsCollected} / the collect endpoint — deduct stock, post a
     * SALE dated today, create commission, book cash if paid in cash, and move to DELIVERED with
     * a delivered_at timestamp. Stock deduction is last so a short-stock failure rolls the whole
     * fulfilment back (nothing is half-recorded).
     *
     * Revenue is recognised on the delivery day via the transaction ledger (getGrossSalesForDate),
     * so today's live report and the eventual close snapshot both pick it up without any manual
     * daily-report patch (unlike collections, which book to a past, already-closed day).
     */
    public Order fulfillScheduledDelivery(String orderId, Long userId) {
        return fulfillScheduledDelivery(orderId, userId, false);
    }

    /**
     * Overload with the Fix 1 "for collection" branch.
     *
     * <p><b>forCollection = false (Paid)</b> — the original behavior: the order becomes a real,
     * fully-recorded DELIVERED sale (stock deducted, SALE dated today, commission, cash-if-cash).
     *
     * <p><b>forCollection = true (For collection)</b> — terms clients: the boxes are physically
     * delivered (stock deducted) but payment is deferred. This mirrors the backdated-UNPAID path
     * exactly so every downstream guard (collect / cancel / replacement) treats it identically to
     * any other PENDING_COLLECTION order:
     * <ul>
     *   <li>status → PENDING_COLLECTION (+ pendingCollectionAt), paymentStatus → UNPAID</li>
     *   <li>SALE dated today + COLL-DEFER dated today → nets to ₱0 (net not inflated until collected)</li>
     *   <li>no commission, no cash — both are created later by the /collect endpoint</li>
     *   <li>stock still deducted (goods left the warehouse)</li>
     * </ul>
     * The order is settled later through the existing {@code PATCH /api/orders/{id}/collect}
     * (PENDING_COLLECTION path), which posts COLL-SALE + commission + cash on the collection day.
     * deliveredAt is stamped in both branches so the order surfaces in the delivery day's list (Fix 3).
     */
    @Transactional
    public Order fulfillScheduledDelivery(String orderId, Long userId, boolean forCollection) {
        Order order = orderRepository.findByIdForUpdateWithItems(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if (!"SCHEDULED_DELIVERY".equals(order.getStatus())) {
            throw new RuntimeException("Order is not a scheduled delivery (status: " + order.getStatus() + ")");
        }

        // Final-order confirmation gate (V95): the order must be confirmed before it can
        // be delivered. Editing the items clears this flag, forcing a re-confirmation.
        if (!order.isDeliveryConfirmed()) {
            throw new RuntimeException("This order must be confirmed before delivery. Review the final items and confirm first.");
        }

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        final LocalDate today = LocalDate.now();

        // The goods are delivered on the delivery day in both branches — stamp deliveredAt so
        // the order shows in the delivery day's order list (Fix 3).
        order.setDeliveredAt(OffsetDateTime.now());

        if (forCollection) {
            // Deferred payment: delivered but not yet collected.
            order.setStatus("PENDING_COLLECTION");
            order.setPendingCollectionAt(OffsetDateTime.now());
            order.setPaymentStatus("UNPAID");
            order.appendDeliveryLog(today + " — delivered FOR COLLECTION (payment deferred) by "
                    + actor.getFullName());
            orderRepository.save(order);

            // SALE dated today, immediately neutralised by a COLL-DEFER dated today → net ₱0
            // until the payment is actually collected (revenue recognised on the collection day).
            transactionService.recordSale(order, userId, today);
            transactionService.recordDeferralVoid(order, userId, today);

            // Commission and cash are BOTH deferred — the /collect endpoint creates them on the
            // collection day (matches the backdated-UNPAID and force-close deferral behavior).

            // Deduct stock last — short stock throws → whole fulfilment rolls back.
            inventoryService.deductStockForOrder(order, userId);

            activityLogService.log(userId, actor.getFullName(), "ORDER_DELIVERED_FOR_COLLECTION",
                    "Delivered scheduled order " + orderId + " for collection — ₱" + order.getTotal()
                            + " deferred on " + today + " (awaiting collection)",
                    "ORDER", orderId);
            return order;
        }

        // ── Paid: flip to a real, recorded order — on the delivery day. ──
        order.setStatus("DELIVERED");
        order.appendDeliveryLog(today + " — delivered & recorded by " + actor.getFullName());
        orderRepository.save(order);

        // SALE dated today (revenue recognised on the delivery day).
        transactionService.recordSale(order, userId, today);

        // Commission — idempotent; no-op when the order has no agent / no opAmount.
        try { commissionService.createEntriesForOrder(order, userId); }
        catch (Exception e) {
            log.warn("Failed to create commission entries for delivered order {}: {}",
                     order.getId(), e.getMessage());
        }

        // Cash on hand: only cash-paid orders add to the drawer, booked on the delivery day.
        if ("CASH".equalsIgnoreCase(order.getPaymentMode())) {
            cashLedgerService.recordOrderCashSale(order, userId, actor.getFullName(), today);
        }

        // Deduct stock last — short stock throws → whole fulfilment rolls back.
        inventoryService.deductStockForOrder(order, userId);

        activityLogService.log(userId, actor.getFullName(), "ORDER_DELIVERED",
                "Delivered scheduled order " + orderId + " — ₱" + order.getTotal()
                        + " recorded on " + today,
                "ORDER", orderId);

        return order;
    }

    /**
     * Reschedule a SCHEDULED_DELIVERY order (V93) to a new date. Repeatable indefinitely;
     * records nothing (no stock/sale/cash) — only moves the date and appends the change log.
     */
    @Transactional
    public Order rescheduleDelivery(String orderId, LocalDate newDate, Long userId) {
        if (newDate == null) {
            throw new RuntimeException("New delivery date is required");
        }
        if (newDate.isBefore(LocalDate.now())) {
            throw new RuntimeException("New delivery date cannot be in the past");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if (!"SCHEDULED_DELIVERY".equals(order.getStatus())) {
            throw new RuntimeException("Only scheduled-delivery orders can be rescheduled (status: "
                    + order.getStatus() + ")");
        }

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        LocalDate old = order.getScheduledDeliveryDate();
        order.setScheduledDeliveryDate(newDate);
        order.appendDeliveryLog(LocalDate.now() + " — rescheduled " + old + " → " + newDate
                + " by " + actor.getFullName());
        orderRepository.save(order);

        activityLogService.log(userId, actor.getFullName(), "ORDER_RESCHEDULE_DELIVERY",
                "Rescheduled delivery for order " + orderId + ": " + old + " → " + newDate,
                "ORDER", orderId);

        return order;
    }

    /**
     * Edit the line items of a SCHEDULED_DELIVERY order (V95) before it is delivered.
     *
     * Safe with no stock/ledger reversal: a scheduled order records nothing until fulfilled,
     * so we simply rebuild the item list and recompute the order total (the SALE at
     * fulfilment reads order.getTotal()). Editing always CLEARS the confirmation gate —
     * the final list must be re-confirmed before delivery. Repeatable; records nothing.
     */
    @Transactional
    public Order editScheduledDeliveryItems(String orderId,
                                            java.util.List<CreateOrderRequest.OrderItemRequest> items,
                                            BigDecimal discount, BigDecimal deliveryFee,
                                            String driver, String helpers,
                                            String coordinatedBy, String notes, Long userId) {
        Order order = orderRepository.findByIdForUpdateWithItems(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if (!"SCHEDULED_DELIVERY".equals(order.getStatus())) {
            throw new RuntimeException("Only scheduled-delivery orders can be edited (status: "
                    + order.getStatus() + ")");
        }

        // Validate items with the same rules as order creation (buildOrderFromRequest).
        if (items == null || items.isEmpty())
            throw new RuntimeException("Order must have at least one item");
        for (CreateOrderRequest.OrderItemRequest it : items) {
            if (it.getQuantity() == null || it.getQuantity() <= 0)
                throw new RuntimeException("Item quantity must be at least 1");
            if (it.getUnitPrice() == null || it.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0)
                throw new RuntimeException("Item unit price must be greater than 0 for: "
                    + (it.getProductName() != null ? it.getProductName() : "unknown"));
            if (it.getProductId() == null)
                throw new RuntimeException("Item \"" + (it.getProductName() != null ? it.getProductName() : "unknown")
                    + "\" must be selected from the product catalog");
            if (!productRepository.existsById(it.getProductId()))
                throw new RuntimeException("Product \"" + (it.getProductName() != null ? it.getProductName() : "ID " + it.getProductId())
                    + "\" no longer exists in the catalog");
        }

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Rebuild the item list in place so orphanRemoval drops the old rows.
        order.getItems().clear();
        for (CreateOrderRequest.OrderItemRequest itemReq : items) {
            OrderItem item = new OrderItem();
            item.setProductId(itemReq.getProductId());
            item.setProductName(itemReq.getProductName());
            item.setQuantity(itemReq.getQuantity());
            item.setUnitPrice(itemReq.getUnitPrice());
            item.setWarehouse(itemReq.getWarehouse());
            // Compute subtotal explicitly (calculateTotals only fills a null subtotal).
            item.setSubtotal(itemReq.getUnitPrice().multiply(new BigDecimal(itemReq.getQuantity())));
            order.addItem(item);
        }

        // Optional order-level adjustments — preserve existing values when not supplied.
        if (discount != null)    order.setDiscount(discount);
        if (deliveryFee != null) order.setDeliveryFee(deliveryFee);
        // Fix 5: delivery crew is editable here too (a blank value clears the field).
        if (driver != null)        order.setDeliveryDriver(driver.isBlank() ? null : driver.trim());
        if (helpers != null)       order.setDeliveryHelpers(helpers.isBlank() ? null : helpers.trim());
        if (coordinatedBy != null) order.setDeliveryCoordinatedBy(coordinatedBy.isBlank() ? null : coordinatedBy.trim());
        if (notes != null)         order.setDeliveryNotes(notes.isBlank() ? null : notes.trim());
        order.calculateTotals();

        // Editing clears the confirmation gate — the new list must be re-confirmed.
        order.setDeliveryConfirmed(false);
        order.setDeliveryConfirmedAt(null);
        order.appendDeliveryLog(LocalDate.now() + " — items edited by " + actor.getFullName()
                + " (total now ₱" + order.getTotal() + "; re-confirmation required)");
        orderRepository.save(order);

        activityLogService.log(userId, actor.getFullName(), "EDIT_DELIVERY_ITEMS",
                "Edited items on scheduled order " + orderId + " — new total ₱" + order.getTotal()
                        + " (re-confirmation required)",
                "ORDER", orderId);

        return order;
    }

    /**
     * Confirm the final order for a SCHEDULED_DELIVERY order (V95). Only after this may the
     * order be fulfilled. Records nothing (still inert) — just flips the confirmation gate.
     * Idempotent: confirming an already-confirmed order is a no-op.
     */
    @Transactional
    public Order confirmScheduledDelivery(String orderId, Long userId,
                                          String driver, String helpers,
                                          String coordinatedBy, String notes) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if (!"SCHEDULED_DELIVERY".equals(order.getStatus())) {
            throw new RuntimeException("Only scheduled-delivery orders can be confirmed (status: "
                    + order.getStatus() + ")");
        }

        // Fix 5: the delivery crew is captured (and required) at final-order confirmation.
        if (driver == null || driver.isBlank())
            throw new RuntimeException("Driver is required to confirm the delivery");
        if (helpers == null || helpers.isBlank())
            throw new RuntimeException("At least one helper is required to confirm the delivery");

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        order.setDeliveryDriver(driver.trim());
        order.setDeliveryHelpers(helpers.trim());
        order.setDeliveryCoordinatedBy(
                (coordinatedBy != null && !coordinatedBy.isBlank()) ? coordinatedBy.trim() : actor.getFullName());
        order.setDeliveryNotes((notes != null && !notes.isBlank()) ? notes.trim() : null);

        if (order.isDeliveryConfirmed()) {
            // Already confirmed — just refresh the crew details; don't re-flip the gate or re-log.
            orderRepository.save(order);
            return order;
        }

        order.setDeliveryConfirmed(true);
        order.setDeliveryConfirmedAt(OffsetDateTime.now());
        order.appendDeliveryLog(LocalDate.now() + " — final order confirmed by " + actor.getFullName()
                + " · driver: " + order.getDeliveryDriver()
                + ", helper(s): " + order.getDeliveryHelpers().replace("\n", ", "));
        orderRepository.save(order);

        activityLogService.log(userId, actor.getFullName(), "CONFIRM_DELIVERY",
                "Confirmed final order for scheduled delivery " + orderId + " — ₱" + order.getTotal()
                        + " (driver " + order.getDeliveryDriver() + ")",
                "ORDER", orderId);

        return order;
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
        // Default: affect inventory stock (preserves existing import behavior for all callers).
        return createOrderAtDate(order, createdByUserId, targetDate, true);
    }

    /**
     * Overload of {@link #createOrderAtDate(Order, Long, LocalDate)} that lets the batch-import
     * pipeline choose whether to deduct inventory stock.
     *
     * When {@code affectStock} is false ("Recording only" imports of old historical data), the
     * order, its backdated SALE ledger entry, and downstream commission entries are still written
     * — only {@link InventoryService#deductStockForOrder} is skipped, so the live warehouse counts
     * (already the actual on-hand) are left untouched. Cash-on-hand is handled by the caller.
     */
    @Transactional
    public Order createOrderAtDate(Order order, Long createdByUserId, LocalDate targetDate, boolean affectStock) {
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
        // bulk/backdated import pipeline; cash on hand is handled by the caller (ImportController)
        // so it can honor the "Recording only" toggle. Recording-only imports also skip the
        // inventory deduction below, leaving live warehouse counts (the actual on-hand) intact.
        if (affectStock) {
            inventoryService.deductStockForOrder(savedOrder, createdByUserId);
        }

        return savedOrder;
    }

    /**
     * Backdated order creation for the "Add Records" page (S2).
     *
     * Builds the order from the same {@link CreateOrderRequest} the live New Order screen uses
     * (via {@link #buildOrderFromRequest}), backdates it to {@code date} (order-ID prefix,
     * createdAt, SALE ledger entry — all via {@link #createOrderAtDate}), then applies the same
     * payment-status routing as the CSV import commit and the live COD path — so source / agent /
     * payment mode / multi-item / commission all behave identically to a normal order.
     *
     * Routing (mirrors {@code ImportController.commitImport}; the live COD path lives in
     * {@code createOrderAtDate}, which already sets PENDING for {@code paymentMode == COD}):
     * <ul>
     *   <li><b>PAID</b> → status ACTIVE; commission created (if agent); cash-in when CASH &amp;&amp; !recordingOnly.</li>
     *   <li><b>UNPAID/pending</b> → status PENDING_COLLECTION (+ pendingCollectionAt); SALE deferral-voided
     *       (net not inflated until collected); no commission; no cash. Surfaces on the Collections page
     *       and is settled later via {@code PATCH /api/orders/{id}/collect}.</li>
     *   <li><b>blank/null</b> → status left as built (ACTIVE, or PENDING for COD); commission created;
     *       cash-in when CASH &amp;&amp; !recordingOnly.</li>
     * </ul>
     *
     * {@code recordingOnly} (V84) skips inventory deduction and cash-on-hand, but never suppresses
     * the SALE ledger entry or commissions — historical records still count in sales and agent payouts.
     *
     * @param paymentStatus "PAID", "UNPAID", or blank/null (COD is expressed via {@code req.paymentMode})
     */
    @Transactional
    public Order createBackdatedOrder(CreateOrderRequest req, String paymentStatus,
                                      Long userId, LocalDate date, boolean recordingOnly) {
        User creator = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        Order built = buildOrderFromRequest(req, userId);
        // Provenance: Add Records entries are back-records. Flag lateImported when the target
        // date's daily report is already closed (S3 will recompute those reports).
        built.setImported(true);
        built.setLateImported(dailyReportRepository.findByReportDate(date).isPresent());
        // affectStock = !recordingOnly: record-only back-records leave live warehouse counts intact.
        Order saved = createOrderAtDate(built, userId, date, !recordingOnly);

        String ps = paymentStatus == null ? "" : paymentStatus.trim().toUpperCase();

        if ("UNPAID".equals(ps)) {
            saved.setPaymentStatus("UNPAID");
            saved.setStatus("PENDING_COLLECTION");
            saved.setPendingCollectionAt(OffsetDateTime.now());
            orderRepository.save(saved);
            // Net not inflated until collected; commission deferred; no cash movement.
            try { transactionService.recordDeferralVoid(saved, userId); } catch (Exception ignored) {}
            return saved;
        }

        if ("PAID".equals(ps)) {
            saved.setPaymentStatus("PAID");
            saved.setStatus("ACTIVE");
            orderRepository.save(saved);
        }
        // else blank/null → keep status as built (ACTIVE default, or PENDING for COD paymentMode).

        // Commission entries — idempotent; no-op when the order has no agent / no opAmount.
        try { commissionService.createEntriesForOrder(saved, userId); }
        catch (Exception e) {
            log.warn("Failed to create commission entries for backdated order {}: {}",
                     saved.getId(), e.getMessage());
        }

        // Cash-on-hand: only for cash-paid orders, and only when not recording-only.
        if (!recordingOnly && "CASH".equalsIgnoreCase(saved.getPaymentMode())) {
            try {
                cashLedgerService.recordOrderCashSale(saved, userId, creator.getFullName(), date);
            } catch (Exception e) {
                log.warn("Failed to record cash-on-hand for backdated order {}: {}",
                         saved.getId(), e.getMessage());
            }
        }

        return saved;
    }

    /**
     * Get today's orders (for the New Order view summary + Order List view).
     *
     * Fix 3: includes orders DELIVERED today as well as those created today, so a
     * scheduled delivery (which keeps its scheduling-day createdAt but is recorded on
     * the delivery day) shows up in the delivery day's list like a normal order —
     * paid → DELIVERED, for-collection → PENDING_COLLECTION.
     */
    public List<Order> getTodaysOrders() {
        return orderRepository.findForTodayList(LocalDate.now());
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
        // Fetch items eagerly: the movement-ledger restore no longer touches order.getItems(),
        // so without this the collection stays uninitialized and convertToResponse() (called
        // after this @Transactional method commits and the session closes) throws
        // LazyInitializationException. Mirrors cancelOrderForReplacement().
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if ("CANCELLED".equals(order.getStatus())) {
            throw new RuntimeException("Order is already cancelled");
        }

        User cancelledBy = userRepository.findById(cancelledByUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Deferred delivery (V93): a SCHEDULED_DELIVERY order recorded nothing (no stock, no
        // SALE, no cash), so cancelling must NOT restore stock or write a VOID — that would
        // fabricate a phantom stock-in and a negative ledger entry. Captured before the flip.
        final boolean wasScheduledDelivery = "SCHEDULED_DELIVERY".equals(order.getStatus());

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

        // Deferred delivery: nothing was ever recorded — drop it clean, no ledger/cash/stock work.
        if (wasScheduledDelivery) {
            return savedOrder;
        }

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
        }
        // Non-DELIVERED: goods never left the warehouse — stock is auto-restored to the exact
        // origin warehouse(s) from the movement ledger, so no per-line restock warehouse is required.

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
    public BatchCollectResult batchMarkAsCollected(List<String> orderIds, Long userId, String callerName,
                                                   LocalDate collectionDate) {
        final LocalDate collDate = (collectionDate != null) ? collectionDate : LocalDate.now();
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

                final LocalDate originalDate = order.getCreatedAt().toLocalDate();
                // One date applies to the whole batch; skip any order that predates it (can't be
                // collected before it existed) rather than booking money before the order date.
                if (collDate.isBefore(originalDate)) {
                    skipped.add(Map.of("orderId", orderId,
                            "reason", "Collection date " + collDate + " is before the order date " + originalDate));
                    continue;
                }

                order.setStatus("DELIVERED");
                OffsetDateTime nowTs = OffsetDateTime.now();
                order.setCollectedAt(collDate.isEqual(nowTs.toLocalDate())
                        ? nowTs : collDate.atTime(nowTs.toOffsetTime()));
                order.setCollectedBy(callerName);
                orderRepository.save(order);

                if ("PENDING_COLLECTION".equals(status)) {
                    transactionService.recordCollectionSale(order, userId, collDate);
                    try { commissionService.createEntriesForOrder(order, userId); }
                    catch (Exception e) {
                        log.warn("Failed to create commission entries for order {}: {}", order.getId(), e.getMessage());
                    }

                    final BigDecimal amount = order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO;

                    // Revenue recognized on the COLLECTION date (cash basis).
                    dailyReportRepository.findByReportDate(collDate).ifPresent(report -> {
                        report.setGrossSales(report.getGrossSales() != null
                                ? report.getGrossSales().add(amount) : amount);
                        report.setNetSales(report.getNetSales() != null
                                ? report.getNetSales().add(amount) : amount);
                        report.setTotalRevenue(report.getTotalRevenue() != null
                                ? report.getTotalRevenue().add(amount) : amount);
                        dailyReportRepository.save(report);
                    });

                    // Fulfillment counts stay on the ORIGINAL date's snapshot (collapses into one
                    // patch when collDate == originalDate).
                    dailyReportRepository.findByReportDate(originalDate).ifPresent(report -> {
                        report.setTotalOrders(report.getTotalOrders() + 1);
                        report.setUnfulfilledOrders(Math.max(0, report.getUnfulfilledOrders() - 1));
                        BigDecimal ua = report.getUnfulfilledAmount() != null
                                ? report.getUnfulfilledAmount() : BigDecimal.ZERO;
                        report.setUnfulfilledAmount(ua.subtract(amount).max(BigDecimal.ZERO));
                        dailyReportRepository.save(report);
                    });
                }

                activityLogService.log(userId, callerName, "ORDER_COLLECT",
                        "Collected payment for order " + orderId + " — ₱" + order.getTotal()
                                + " (order date: " + originalDate + ", collected: " + collDate + ")",
                        "ORDER", orderId);

                // Cash on hand: only orders actually paid in cash affect the drawer. Batch collect
                // has no per-order picker, so it uses each order's recorded mode — COD/CASH count as
                // cash; BANK_TRANSFER/GCASH/PAYMAYA do not. Booked on the batch collection date. Idempotent.
                String batchMode = order.getPaymentMode() == null ? "" : order.getPaymentMode().trim().toUpperCase();
                if (batchMode.equals("CASH") || batchMode.equals("COD")) {
                    cashLedgerService.recordOrderCashSale(order, userId, callerName, collDate);
                }

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
