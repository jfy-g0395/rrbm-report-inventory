package rrbm_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import rrbm_backend.dto.CreateOrderRequest;
import rrbm_backend.dto.OrderResponse;
import rrbm_backend.dto.CancelForReplacementRequest;
import rrbm_backend.dto.CorrectItemRequest;
import rrbm_backend.dto.ReturnOrderRequest;
import rrbm_backend.dto.VoidOrderRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final ActivityLogService activityLogService;
    private final TransactionService transactionService;
    private final DailyReportRepository dailyReportRepository;
    private final MasterKeyService masterKeyService;
    private final ProductRepository productRepository;
    private final AgentRepository agentRepository;
    private final ResellerRepository resellerRepository;
    private final CommissionService commissionService;
    private final CashLedgerService cashLedgerService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // Used to clear the persistence context between rows in the CSV batch import (createOrderBatch).
    // With open-session-in-view, one Hibernate session is bound to the whole request; every order
    // created in the loop leaves its entities (order, items, movements, transactions, commission
    // entries) managed in it. Each query that auto-flushes (e.g. sumStockById in logMovement, and
    // the per-row existsByNotesContaining duplicate check) then dirty-checks the ENTIRE growing set,
    // making a large import O(N^2) — slow enough to exceed the proxy timeout so the modal never gets
    // its result back. Clearing per row keeps each flush cheap (O(N) overall). Mirrors the fix in
    // ImportController.commit (commit e896a74).
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public OrderController(OrderService orderService,
                           JwtUtil jwtUtil,
                           UserRepository userRepository,
                           OrderRepository orderRepository,
                           ActivityLogService activityLogService,
                           TransactionService transactionService,
                           DailyReportRepository dailyReportRepository,
                           MasterKeyService masterKeyService,
                           ProductRepository productRepository,
                           AgentRepository agentRepository,
                           ResellerRepository resellerRepository,
                           CommissionService commissionService,
                           CashLedgerService cashLedgerService) {
        this.orderService          = orderService;
        this.jwtUtil               = jwtUtil;
        this.userRepository        = userRepository;
        this.orderRepository       = orderRepository;
        this.activityLogService    = activityLogService;
        this.transactionService    = transactionService;
        this.dailyReportRepository = dailyReportRepository;
        this.masterKeyService      = masterKeyService;
        this.productRepository     = productRepository;
        this.agentRepository       = agentRepository;
        this.resellerRepository    = resellerRepository;
        this.commissionService     = commissionService;
        this.cashLedgerService     = cashLedgerService;
    }

    /** Strip "Bearer " prefix and extract the user ID from the JWT. */
    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    /**
     * Extracts the ecommerce platform order number from the notes field.
     * Notes format: "Order No: XXX | tracking via platform"
     * Returns null for grouped orders ("Orders: X, Y, Z") — those skip duplicate check.
     */
    private String extractExternalOrderRef(String notes) {
        if (notes == null || !notes.startsWith("Order No: ")) return null;
        String rest = notes.substring("Order No: ".length());
        int pipe = rest.indexOf(" |");
        return pipe > 0 ? rest.substring(0, pipe).trim() : rest.trim();
    }
    
    /**
     * Create a new order
     * POST /api/orders
     */
    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request, 
                                        @RequestHeader("Authorization") String authHeader) {
        try {
            Long userId = userIdFromHeader(authHeader);
            if (userId == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));

            // M-21: Server-side payload validation + entity build + agent linking + per-item
            // commission setup are all done by the shared builder so this live path and the
            // backdated "Add Records" path produce identical orders. Validation failures throw
            // RuntimeException (caught below → 400) with the same messages as before.
            Order order = orderService.buildOrderFromRequest(request, userId);

            // Create order
            Order savedOrder = orderService.createOrder(order, userId);

            // Best-effort commission entry creation — does not affect order response.
            // Skip for deferred deliveries: they record nothing (incl. commission) until
            // fulfilled; fulfillScheduledDelivery creates the entries on the delivery day.
            if (!"SCHEDULED_DELIVERY".equals(savedOrder.getStatus())) {
                try {
                    commissionService.createEntriesForOrder(savedOrder, userId);
                } catch (Exception ignored) {}
            }

            // Convert to response DTO
            OrderResponse response = convertToResponse(savedOrder);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
            
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }
    
    /**
     * Batch-import orders from CSV (e-commerce import).
     * POST /api/orders/batch
     * Accepts an array of CreateOrderRequest. Processes each independently —
     * a failure on one order does not block the rest.
     */
    @PostMapping("/batch")
    public ResponseEntity<?> createOrderBatch(@RequestBody List<CreateOrderRequest> requests,
                                              @RequestHeader("Authorization") String authHeader) {
        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Invalid or missing authentication token"));

        int imported = 0, failed = 0;
        List<Map<String, Object>> succeeded = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (CreateOrderRequest request : requests) {
            // Detach the previous row's entities so the persistence context stays small and each
            // per-row auto-flush (duplicate check, stock-movement sums) does not degrade to O(N^2)
            // across a large import (see entityManager field doc). Safe here: the method is not
            // @Transactional and each createOrder commits its own transaction, so nothing pending
            // is discarded.
            entityManager.clear();

            // Duplicate check: skip if this ecommerce order number already exists.
            // N-8: append " |" so LIKE '%Order No: 12 |%' does NOT match "Order No: 123 | …".
            // Without the separator, "Order No: 12" is a substring of "Order No: 123" causing
            // a valid import to be silently skipped as a false duplicate.
            String externalRef = extractExternalOrderRef(request.getNotes());
            if (externalRef != null && orderRepository.existsByNotesContaining("Order No: " + externalRef + " |")) {
                skipped.add(Map.of(
                    "ref",      externalRef,
                    "customer", request.getCustomerName() != null ? request.getCustomerName() : "?"
                ));
                continue;
            }
            try {
                // M-21: Same payload validation as single-order path — exceptions are
                // caught by the outer catch and added to the errors list.
                String cn = request.getCustomerName();
                if (cn == null || cn.trim().isEmpty()) throw new RuntimeException("Customer name is required");
                if (request.getItems() == null || request.getItems().isEmpty()) throw new RuntimeException("Order must have at least one item");
                for (CreateOrderRequest.OrderItemRequest it : request.getItems()) {
                    if (it.getQuantity() == null || it.getQuantity() <= 0)
                        throw new RuntimeException("Item quantity must be at least 1");
                    if (it.getUnitPrice() == null || it.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0)
                        throw new RuntimeException("Item unit price must be greater than 0");
                }
                // M-22: paymentMode is required in batch — no silent COD fallback.
                // A missing paymentMode caused orders to silently become PENDING (COD default)
                // instead of failing with a clear error.
                if (request.getPaymentMode() == null || request.getPaymentMode().isBlank())
                    throw new RuntimeException("Payment mode is required");

                Order order = new Order();
                order.setCustomerName(request.getCustomerName());
                order.setSource(request.getSource());
                order.setAgentName(request.getAgentName());
                order.setFbPage(request.getFbPage());
                order.setEcommercePlatform(request.getEcommercePlatform());
                order.setPaymentMode(request.getPaymentMode()); // validated non-null above
                order.setOrderType(request.getOrderType() != null ? request.getOrderType() : "STANDARD");
                order.setAddress(request.getAddress());
                order.setDiscount(request.getDiscount() != null ? request.getDiscount() : BigDecimal.ZERO);
                order.setDeliveryFee(request.getDeliveryFee() != null ? request.getDeliveryFee() : BigDecimal.ZERO);
                order.setNotes(request.getNotes());
                request.getItems().forEach(itemReq -> {
                    OrderItem item = new OrderItem();
                    item.setProductId(itemReq.getProductId());
                    item.setProductName(itemReq.getProductName());
                    item.setQuantity(itemReq.getQuantity());
                    item.setUnitPrice(itemReq.getUnitPrice());
                    item.setWarehouse(itemReq.getWarehouse() != null ? itemReq.getWarehouse() : "wh1");
                    order.addItem(item);
                });
                Order saved = orderService.createOrder(order, userId);
                imported++;
                succeeded.add(Map.of(
                        "ref",      externalRef != null ? externalRef
                                    : (request.getCustomerName() != null ? request.getCustomerName() : "?"),
                        "customer", request.getCustomerName() != null ? request.getCustomerName() : "?",
                        "orderId",  saved.getId(),
                        "items",    order.getItems().size()
                ));
            } catch (Exception e) {
                failed++;
                errors.add(Map.of(
                        "ref",      externalRef != null ? externalRef
                                    : (request.getCustomerName() != null ? request.getCustomerName() : "?"),
                        "customer", request.getCustomerName() != null ? request.getCustomerName() : "?",
                        "reason",   e.getMessage() != null ? e.getMessage() : "Unknown error"
                ));
            }
        }
        return ResponseEntity.ok(Map.of("imported", imported, "failed", failed,
                "succeeded", succeeded, "errors", errors, "skipped", skipped));
    }

    /**
     * Get today's orders
     * GET /api/orders/today
     */
    @GetMapping("/today")
    public ResponseEntity<List<OrderResponse>> getTodaysOrders() {
        List<Order> orders = orderService.getTodaysOrders();
        List<OrderResponse> response = orders.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Minimal active-agent list for the New Order form's agent picker.
     * GET /api/orders/agent-options
     *
     * Lives under /api/orders so PageAccessInterceptor gates it by the "orders"
     * page — order creators who lack the Agents page can still assign an agent
     * (the /api/agents endpoints remain gated to the "agents" page).
     */
    @GetMapping("/agent-options")
    public ResponseEntity<?> getAgentOptions() {
        List<Map<String, Object>> options = agentRepository.findByStatusOrderByFullNameAsc("ACTIVE")
                .stream()
                .map(a -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id",        a.getId());
                    m.put("fullName",  a.getFullName());
                    m.put("agentCode", a.getAgentCode());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(options);
    }

    /**
     * Minimal active reseller/distributor list for the New Order form's picker (S-A1).
     * GET /api/orders/reseller-options?type=RESELLER|DISTRIBUTOR
     *
     * Lives under /api/orders so PageAccessInterceptor gates it by the "orders" page —
     * order creators who lack the Resellers page can still assign one (the /api/resellers
     * endpoints remain gated to the "resellers" page).
     */
    @GetMapping("/reseller-options")
    public ResponseEntity<?> getResellerOptions(@RequestParam(value = "type", required = false) String type) {
        List<Reseller> resellers = (type != null && !type.isBlank())
                ? resellerRepository.findByTypeAndStatusOrderByNameAsc(type.toUpperCase(), "ACTIVE")
                : resellerRepository.findByStatusOrderByNameAsc("ACTIVE");
        List<Map<String, Object>> options = resellers.stream()
                .map(r -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id",           r.getId());
                    m.put("name",         r.getName());
                    m.put("resellerCode", r.getResellerCode());
                    m.put("type",         r.getType());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(options);
    }

    /**
     * Get all orders
     * GET /api/orders
     */
    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        List<Order> orders = orderService.getAllOrders();
        List<OrderResponse> response = orders.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get order by ID
     * GET /api/orders/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getOrderById(@PathVariable String id) {
        try {
            Order order = orderService.getOrderById(id);
            OrderResponse response = convertToResponse(order);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        }
    }
    
    /**
     * Update order status
     * PUT /api/orders/{id}/status
     * Body: { "status": "DELIVERED" }
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateOrderStatus(@PathVariable String id,
                                               @RequestBody Map<String, String> request,
                                               @RequestHeader("Authorization") String authHeader) {
        try {
            String newStatus = request.get("status");
            if (newStatus == null || newStatus.isEmpty()) {
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("message", "Status is required"));
            }

            Long userId = userIdFromHeader(authHeader);
            if (userId == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));

            // COD / non-cash resume gate: PENDING → ACTIVE requires the admin's personal
            // security key on the backend — the frontend verify-password step is not enough.
            Order existingOrder = orderRepository.findById(id).orElse(null);
            if (existingOrder != null
                    && "PENDING".equals(existingOrder.getStatus())
                    && "ACTIVE".equals(newStatus)
                    && !"CASH".equals(existingOrder.getPaymentMode())) {
                String secKey = request.getOrDefault("securityKey", "").trim();
                if (secKey.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("message", "Admin security key is required to resume a non-cash order"));
                }
                User caller = userRepository.findById(userId).orElse(null);
                if (caller == null || caller.getAdminSecurityKey() == null
                        || !passwordEncoder.matches(secKey, caller.getAdminSecurityKey())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("message", "Invalid admin security key"));
                }

                // COD resolution: a COD order's real payment mode is captured at resume.
                // If the caller supplies a concrete (non-COD) mode, record it on the order
                // so the payment summary classifies it correctly (cash vs e-wallet/online).
                String chosenMode = request.getOrDefault("paymentMode", "").trim().toUpperCase();
                if (!chosenMode.isEmpty()) {
                    java.util.Set<String> accepted = java.util.Set.of(
                            "CASH", "GCASH", "PAYMAYA", "BANK_TRANSFER", "BANK_DEPOSIT", "ONLINE");
                    if (!accepted.contains(chosenMode)) {
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(Map.of("message", "Invalid payment mode: " + chosenMode));
                    }
                    existingOrder.setPaymentMode(chosenMode);
                    orderRepository.save(existingOrder);

                    // COD resolved to CASH → the cash physically comes in at this moment.
                    // Record it to the cash ledger so the Cash Flow page reflects it.
                    // Idempotent by order reference, so a later mark-collected won't double-count.
                    // (caller is loaded and null-checked above in this same block.)
                    if ("CASH".equals(chosenMode)) {
                        cashLedgerService.recordOrderCashSale(existingOrder, userId,
                                caller.getFullName(), LocalDate.now());
                    }
                }
            }

            Order updatedOrder = orderService.updateStatus(id, newStatus, userId);
            OrderResponse response = convertToResponse(updatedOrder);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }
    
    /**
     * Cancel an order
     * POST /api/orders/{id}/cancel
     * Now requires the employee's personal admin security key (not the system master key).
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable String id,
                                        @RequestBody Map<String, String> request,
                                        @RequestHeader("Authorization") String authHeader) {
        try {
            String securityKey = request.get("securityKey");
            String reason      = request.get("reason");

            if (reason == null || reason.isBlank()) {
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("message", "Cancellation reason is required"));
            }
            if (securityKey == null || securityKey.trim().isEmpty()) {
                return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("message", "Admin security key is required to cancel an order"));
            }

            Long userId = userIdFromHeader(authHeader);
            if (userId == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));

            // Validate admin security key against the caller's stored hash
            User caller = userRepository.findById(userId).orElse(null);
            if (caller == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "User not found"));
            }
            if (!isOrderManager(caller)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "You do not have permission to cancel orders (requires the Void & Cancel Orders access)"));
            }
            if (caller.getAdminSecurityKey() == null) {
                return ResponseEntity.status(403)
                        .body(Map.of("message", "No admin security key has been set for your account. Ask your Super Admin to assign one."));
            }
            if (!passwordEncoder.matches(securityKey.trim(), caller.getAdminSecurityKey())) {
                return ResponseEntity.status(403)
                        .body(Map.of("message", "Invalid admin security key"));
            }

            Order cancelledOrder = orderService.cancelOrder(id, userId, reason);
            OrderResponse response = convertToResponse(cancelledOrder);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
    }
    
    /**
     * Get order history by date range (excludes today by default)
     * GET /api/orders/history?start=YYYY-MM-DD&end=YYYY-MM-DD
     * #8 — Order History separation
     */
    @GetMapping("/history")
    public ResponseEntity<List<OrderResponse>> getOrderHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        LocalDate startDate = start != null ? start : LocalDate.now().minusMonths(1);
        LocalDate endDate   = end   != null ? end   : LocalDate.now().minusDays(1);
        List<Order> orders = orderService.getOrdersByDateRange(startDate, endDate);
        List<OrderResponse> response = orders.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /**
     * Search orders by customer name
     * GET /api/orders/search?customerName=xxx
     */
    @GetMapping("/search")
    public ResponseEntity<List<OrderResponse>> searchOrders(@RequestParam String customerName) {
        List<Order> orders = orderService.searchByCustomerName(customerName);
        List<OrderResponse> response = orders.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
    
    /**
     * GET /api/orders/collections
     * Returns all orders pending payment collection:
     *   - PENDING_COLLECTION (force-closed orders awaiting payment)
     *   - PENDING + non-CASH payment mode (COD/credit orders from any date)
     * Ordered oldest-first so longest-outstanding appear at the top.
     */
    @GetMapping("/collections")
    public ResponseEntity<?> getPendingCollections() {
        List<Order> orders = orderRepository.findPendingCollections();
        List<OrderResponse> response = orders.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/orders/scheduled-deliveries
     * Returns all inert SCHEDULED_DELIVERY orders (V93), newest-first. Feeds the
     * "Order Deliveries" card under the Delivery Schedule tab. The frontend derives
     * the "overdue" flag from scheduledDeliveryDate &lt; today.
     */
    @GetMapping("/scheduled-deliveries")
    public ResponseEntity<?> getScheduledDeliveries() {
        List<Order> orders = orderRepository.findByStatusOrderByCreatedAtDesc("SCHEDULED_DELIVERY");
        List<OrderResponse> response = orders.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/orders/collections/history?start=YYYY-MM-DD&end=YYYY-MM-DD
     * Already-collected payments in the date range (by collection date), newest-first.
     * Feeds the Collections History tab. Defaults to the last 30 days when params are omitted.
     */
    @GetMapping("/collections/history")
    public ResponseEntity<?> getCollectionsHistory(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        LocalDate endDate;
        LocalDate startDate;
        try {
            endDate   = (end   == null || end.isBlank())   ? LocalDate.now()             : LocalDate.parse(end.trim());
            startDate = (start == null || start.isBlank()) ? endDate.minusDays(30)       : LocalDate.parse(start.trim());
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid start/end date"));
        }
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.badRequest().body(Map.of("message", "start date must be on or before end date"));
        }
        List<OrderResponse> response = orderRepository.findCollectedBetween(startDate, endDate).stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/orders/{id}/collect
     * Mark an order as collected:
     *   1. Verify the logged-in admin's personal security key.
     *   2. Assert order is PENDING_COLLECTION or PENDING.
     *   3. Set status = DELIVERED, collectedAt = now(), collectedBy = admin name.
     *   4. Post a SALE transaction dated to the original order date (retroactive revenue).
     *   5. Increment grossSales / netSales on the DailyReport for that original date (if it exists).
     *   6. Log ORDER_COLLECT activity.
     */
    @Transactional
    @PatchMapping("/{id}/collect")
    public ResponseEntity<?> collectOrder(@PathVariable String id,
                                          @RequestBody Map<String, String> body,
                                          @RequestHeader("Authorization") String authHeader) {
        try {
            String securityKey = body.getOrDefault("securityKey", "").trim();
            if (securityKey.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Security key is required"));
            }

            Long userId = userIdFromHeader(authHeader);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));
            }

            // Verify admin's personal security key
            User caller = userRepository.findById(userId).orElse(null);
            if (caller == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "User not found"));
            }
            if (caller.getAdminSecurityKey() == null) {
                return ResponseEntity.status(403)
                        .body(Map.of("message", "No admin security key has been set for your account. Ask your Super Admin to assign one."));
            }
            if (!passwordEncoder.matches(securityKey, caller.getAdminSecurityKey())) {
                return ResponseEntity.status(403)
                        .body(Map.of("message", "Invalid admin security key"));
            }

            // Pessimistic write lock with items eagerly fetched: blocks concurrent collect
            // requests and ensures items are available for commission entry creation without
            // triggering a LazyInitializationException in a detached state.
            Order order = orderRepository.findByIdForUpdateWithItems(id)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + id));

            String status = order.getStatus();
            if (!"PENDING_COLLECTION".equals(status) && !"PENDING".equals(status)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Order is not in a collectable state (status: " + status + ")"));
            }

            LocalDate originalDate = order.getCreatedAt().toLocalDate();

            // The collector records the ACTUAL date the payment was received (defaults to today for
            // legacy clients). All collection money — cash-on-hand, the COLL-SALE revenue entry, and
            // the daily-report revenue — is booked on this date so late-recorded orders land on the
            // real collection date instead of the (possibly backdated) order date.
            LocalDate collectionDate;
            try {
                String cd = body.get("collectionDate");
                collectionDate = (cd == null || cd.trim().isEmpty()) ? LocalDate.now() : LocalDate.parse(cd.trim());
            } catch (Exception ex) {
                return ResponseEntity.badRequest().body(Map.of("message", "Invalid collection date"));
            }
            if (collectionDate.isAfter(LocalDate.now())) {
                return ResponseEntity.badRequest().body(Map.of("message", "Collection date cannot be in the future"));
            }
            if (collectionDate.isBefore(originalDate)) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "Collection date cannot be before the order date (" + originalDate + ")"));
            }

            // Mark as collected / delivered — stamp collectedAt with the chosen date (keeping the
            // current time-of-day) so Collections History and reports attribute it correctly.
            order.setStatus("DELIVERED");
            OffsetDateTime nowTs = OffsetDateTime.now();
            order.setCollectedAt(collectionDate.isEqual(nowTs.toLocalDate())
                    ? nowTs : collectionDate.atTime(nowTs.toOffsetTime()));
            order.setCollectedBy(caller.getFullName());
            orderRepository.save(order);

            // Only record COLL-SALE and patch the daily report if this order went through
            // force-close (status was PENDING_COLLECTION). In that path, recordDeferralVoid
            // already zeroed out the original SALE in the ledger, so COLL-SALE correctly
            // restores revenue at the original date.
            //
            // For direct COD collections that were never force-closed (status was PENDING),
            // the original SALE-{id} transaction is still live in the ledger and the closed
            // daily report already includes it — posting another SALE entry here would
            // double-count revenue on both the transaction ledger and the closed report.
            if ("PENDING_COLLECTION".equals(status)) {
                transactionService.recordCollectionSale(order, userId, collectionDate);

                // Create commission entries — safe (idempotent via existsByOrderId guard)
                try { commissionService.createEntriesForOrder(order, userId); }
                catch (Exception e) {
                    log.warn("Failed to create commission entries for order {}: {}", order.getId(), e.getMessage());
                }

                final BigDecimal amount = order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO;

                // Revenue is recognized on the COLLECTION date (cash basis) — patch that day's snapshot.
                dailyReportRepository.findByReportDate(collectionDate).ifPresent(report -> {
                    report.setGrossSales(report.getGrossSales() != null
                            ? report.getGrossSales().add(amount) : amount);
                    report.setNetSales(report.getNetSales() != null
                            ? report.getNetSales().add(amount) : amount);
                    report.setTotalRevenue(report.getTotalRevenue() != null
                            ? report.getTotalRevenue().add(amount) : amount);
                    dailyReportRepository.save(report);
                });

                // Fulfillment counts stay on the ORIGINAL date's snapshot, where this order was
                // counted as unfulfilled when that day closed. When collectionDate == originalDate
                // both patches hit the same snapshot, collapsing into the legacy single patch.
                dailyReportRepository.findByReportDate(originalDate).ifPresent(report -> {
                    report.setTotalOrders(report.getTotalOrders() + 1);
                    report.setUnfulfilledOrders(Math.max(0, report.getUnfulfilledOrders() - 1));
                    BigDecimal ua = report.getUnfulfilledAmount() != null
                            ? report.getUnfulfilledAmount() : BigDecimal.ZERO;
                    report.setUnfulfilledAmount(ua.subtract(amount).max(BigDecimal.ZERO));
                    dailyReportRepository.save(report);
                });
            }

            // Activity log
            activityLogService.log(userId, caller.getFullName(), "ORDER_COLLECT",
                    "Collected payment for order " + id + " — ₱" + order.getTotal()
                            + " (order date: " + originalDate + ", collected: " + collectionDate + ")",
                    "ORDER", id);

            // Cash on hand: the collector picks the actual method received. Only Cash affects the
            // drawer; Bank Transfer / GCash / PayMaya do not. Default to CASH only when the caller
            // sends nothing (legacy clients). Idempotent — a re-collect won't double count.
            String collectMode = body.getOrDefault("paymentMode", "CASH");
            collectMode = (collectMode == null || collectMode.trim().isEmpty()) ? "CASH" : collectMode.trim();

            // Record the actual method received onto the order so reports/records reflect reality.
            order.setPaymentMode(collectMode.toUpperCase());
            orderRepository.save(order);

            if ("CASH".equalsIgnoreCase(collectMode)) {
                cashLedgerService.recordOrderCashSale(order, userId, caller.getFullName(), collectionDate);
            }

            return ResponseEntity.ok(convertToResponse(order));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/batch-mark-collected
     *
     * Batch collect multiple COD orders at once.
     * Validates the admin's security key once, then processes each order.
     */
    @Transactional
    @PostMapping("/batch-mark-collected")
    public ResponseEntity<?> batchMarkCollected(@RequestBody Map<String, Object> body,
                                                @RequestHeader("Authorization") String authHeader) {
        try {
            String securityKey = body.getOrDefault("securityKey", "").toString().trim();
            if (securityKey.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Security key is required"));
            }

            @SuppressWarnings("unchecked")
            List<String> orderIds = (List<String>) body.get("orderIds");
            if (orderIds == null || orderIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "No order IDs provided"));
            }

            Long userId = userIdFromHeader(authHeader);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));
            }

            User caller = userRepository.findById(userId).orElse(null);
            if (caller == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "User not found"));
            }
            if (caller.getAdminSecurityKey() == null) {
                return ResponseEntity.status(403)
                        .body(Map.of("message", "No admin security key has been set for your account. Ask your Super Admin to assign one."));
            }
            if (!passwordEncoder.matches(securityKey, caller.getAdminSecurityKey())) {
                return ResponseEntity.status(403)
                        .body(Map.of("message", "Invalid admin security key"));
            }

            // One collection date applies to the whole batch (default today; never future).
            LocalDate collectionDate;
            try {
                Object cd = body.get("collectionDate");
                String cds = (cd == null) ? null : cd.toString().trim();
                collectionDate = (cds == null || cds.isEmpty()) ? LocalDate.now() : LocalDate.parse(cds);
            } catch (Exception ex) {
                return ResponseEntity.badRequest().body(Map.of("message", "Invalid collection date"));
            }
            if (collectionDate.isAfter(LocalDate.now())) {
                return ResponseEntity.badRequest().body(Map.of("message", "Collection date cannot be in the future"));
            }

            OrderService.BatchCollectResult result = orderService.batchMarkAsCollected(
                    orderIds, userId, caller.getFullName(), collectionDate);

            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("collected", result.collected);
            response.put("skipped",   result.skipped);
            response.put("errors",    result.errors);
            response.put("message",  "Collected " + result.collected + " of " + orderIds.size() + " order(s).");

            if (!result.skipped.isEmpty()) {
                response.put("warning", result.skipped.size() + " order(s) skipped.");
            }
            if (!result.errors.isEmpty()) {
                response.put("error", result.errors.size() + " error(s) during batch collect.");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/fulfill-delivery
     *
     * Deferred delivery (V93) — "Mark Delivered" and "Deliver now" both call this.
     * Records the order on the delivery day and moves it out of SCHEDULED_DELIVERY.
     *
     * Body (optional): { "mode": "PAID" | "FOR_COLLECTION" } (default PAID).
     *   - PAID           → DELIVERED (stock + SALE dated today + commission + cash-if-CASH).
     *   - FOR_COLLECTION → PENDING_COLLECTION (stock + SALE/COLL-DEFER net ₱0; payment deferred,
     *     settled later via /collect). Fix 1.
     *
     * No security key required — recording a delivery is no more privileged than creating a
     * normal order. The privileged step for a for-collection order is the later /collect, which
     * does require the admin's personal security key. Gated to the orders page via PageAccessInterceptor.
     */
    @PostMapping("/{id}/fulfill-delivery")
    public ResponseEntity<?> fulfillDelivery(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, String> body,
                                             @RequestHeader("Authorization") String authHeader) {
        try {
            Long userId = userIdFromHeader(authHeader);
            if (userId == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));

            String mode = (body != null) ? body.getOrDefault("mode", "PAID") : "PAID";
            boolean forCollection = "FOR_COLLECTION".equalsIgnoreCase(mode == null ? "" : mode.trim());

            Order delivered = orderService.fulfillScheduledDelivery(id, userId, forCollection);
            return ResponseEntity.ok(convertToResponse(delivered));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/reschedule-delivery
     * Body: { "scheduledDeliveryDate": "YYYY-MM-DD" }
     *
     * Deferred delivery (V93) — move a scheduled order to a new date. Repeatable
     * indefinitely; records nothing. No security key required.
     */
    @PostMapping("/{id}/reschedule-delivery")
    public ResponseEntity<?> rescheduleDelivery(@PathVariable String id,
                                                @RequestBody Map<String, String> body,
                                                @RequestHeader("Authorization") String authHeader) {
        try {
            Long userId = userIdFromHeader(authHeader);
            if (userId == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));

            LocalDate newDate;
            try {
                String d = body.get("scheduledDeliveryDate");
                if (d == null || d.trim().isEmpty())
                    return ResponseEntity.badRequest().body(Map.of("message", "A new delivery date is required"));
                newDate = LocalDate.parse(d.trim());
            } catch (Exception ex) {
                return ResponseEntity.badRequest().body(Map.of("message", "Invalid delivery date"));
            }

            Order updated = orderService.rescheduleDelivery(id, newDate, userId);
            return ResponseEntity.ok(convertToResponse(updated));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/cancel-delivery
     * Body: { "reason": "..." }
     *
     * Deferred delivery (V93) — drop an inert SCHEDULED_DELIVERY order. Because nothing
     * was ever recorded (no stock, no SALE, no cash), this is a lightweight cancel: no
     * admin security key and no void-cancel-orders permission (unlike the full cancel
     * endpoint, which reverses recorded money/stock). Only SCHEDULED_DELIVERY orders are
     * accepted here; any other status is rejected and must use the normal cancel flow.
     */
    @PostMapping("/{id}/cancel-delivery")
    public ResponseEntity<?> cancelDelivery(@PathVariable String id,
                                            @RequestBody Map<String, String> body,
                                            @RequestHeader("Authorization") String authHeader) {
        try {
            Long userId = userIdFromHeader(authHeader);
            if (userId == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));

            String reason = body.get("reason");
            if (reason == null || reason.isBlank())
                return ResponseEntity.badRequest().body(Map.of("message", "Cancellation reason is required"));

            Order order = orderRepository.findById(id).orElse(null);
            if (order == null)
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Order not found: " + id));
            if (!"SCHEDULED_DELIVERY".equals(order.getStatus()))
                return ResponseEntity.badRequest().body(Map.of("message",
                        "This endpoint only cancels scheduled-delivery orders (status: " + order.getStatus()
                        + "). Use the standard cancel for recorded orders."));

            Order cancelled = orderService.cancelOrder(id, userId, reason);
            return ResponseEntity.ok(convertToResponse(cancelled));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/edit-delivery-items
     * Body: a CreateOrderRequest-shaped payload — only items[] (+ optional discount,
     * deliveryFee) are used.
     *
     * Deferred delivery (V95) — replace the line items of a SCHEDULED_DELIVERY order
     * before it is delivered. Records nothing (order stays inert); recomputes the total
     * and CLEARS the confirmation gate (the new list must be re-confirmed). No security
     * key required — nothing was recorded yet.
     */
    @PostMapping("/{id}/edit-delivery-items")
    public ResponseEntity<?> editDeliveryItems(@PathVariable String id,
                                               @RequestBody CreateOrderRequest body,
                                               @RequestHeader("Authorization") String authHeader) {
        try {
            Long userId = userIdFromHeader(authHeader);
            if (userId == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));

            Order updated = orderService.editScheduledDeliveryItems(
                    id, body.getItems(), body.getDiscount(), body.getDeliveryFee(),
                    body.getDeliveryDriver(), body.getDeliveryHelpers(),
                    body.getDeliveryCoordinatedBy(), body.getDeliveryNotes(), userId);
            return ResponseEntity.ok(convertToResponse(updated));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/confirm-delivery
     *
     * Deferred delivery (V95) — confirm the final order for a SCHEDULED_DELIVERY order.
     * Only after this may it be fulfilled ("Mark Delivered"). Records nothing; no security
     * key required. Idempotent.
     */
    @PostMapping("/{id}/confirm-delivery")
    public ResponseEntity<?> confirmDelivery(@PathVariable String id,
                                             @RequestBody(required = false) Map<String, String> body,
                                             @RequestHeader("Authorization") String authHeader) {
        try {
            Long userId = userIdFromHeader(authHeader);
            if (userId == null)
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or missing authentication token"));

            Map<String, String> b = body != null ? body : java.util.Collections.emptyMap();
            Order confirmed = orderService.confirmScheduledDelivery(id, userId,
                    b.get("driver"), b.get("helpers"), b.get("coordinatedBy"), b.get("notes"));
            return ResponseEntity.ok(convertToResponse(confirmed));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/void
     *
     * Item-level same-day void.  Removes specific quantities from one or more
     * items on an order created today, before today's daily report is closed.
     *
     * Tier 1 (partial — some quantity remains): requires the caller's personal
     *   admin security key in the "securityKey" body field.
     * Tier 2 (full — all items reach zero): requires the system master key in
     *   the "masterKey" body field.  After a Tier 2 void the order is marked
     *   CANCELLED with cancellation_type = VOIDED.
     *
     * Body: {
     *   "items": [{ "orderItemId": 45, "voidQuantity": 3, "disposition": "SELLABLE" }],
     *   "reason": "...",
     *   "securityKey": "..."   // Tier 1
     *   "masterKey": "..."     // Tier 2
     * }
     */
    @PostMapping("/{id}/void")
    public ResponseEntity<?> voidOrderItems(
            @PathVariable String id,
            @RequestBody VoidOrderRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // ── Auth ──────────────────────────────────────────────────────────
        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        User voidCaller = userRepository.findById(userId).orElse(null);
        if (voidCaller == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "User not found"));
        if (!isOrderManager(voidCaller))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to void order items (requires the Void & Cancel Orders access)"));

        // ── Basic request validation ──────────────────────────────────────
        if (request.getItems() == null || request.getItems().isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "At least one item is required"));
        if (request.getReason() == null || request.getReason().isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Reason is required"));

        // ── Load order ────────────────────────────────────────────────────
        Order order = orderRepository.findByIdWithItems(id).orElse(null);
        if (order == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Order not found: " + id));

        if ("CANCELLED".equals(order.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Cannot void items on a cancelled order"));

        // ── Same-day guard ────────────────────────────────────────────────
        if (order.getCreatedAt() == null
                || !order.getCreatedAt().toLocalDate().equals(LocalDate.now()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Void is only available for orders created today"));

        // ── Day-close guard ───────────────────────────────────────────────
        DailyReport todayReport = dailyReportRepository.findByReportDate(LocalDate.now()).orElse(null);
        if (todayReport != null && todayReport.getClosedAt() != null)
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Void is not available after the daily report has been closed"));

        // ── Tier calculation ──────────────────────────────────────────────
        // Build lookup of current state: itemId → current remaining qty
        Map<Long, OrderItem> itemMap = new java.util.HashMap<>();
        order.getItems().forEach(it -> itemMap.put(it.getId(), it));

        boolean allReachZero = true;
        for (VoidOrderRequest.VoidItemRequest req : request.getItems()) {
            OrderItem item = req.getOrderItemId() != null ? itemMap.get(req.getOrderItemId()) : null;
            if (item == null) continue; // service will catch this
            int alreadyVoided = item.getVoidedQuantity() != null ? item.getVoidedQuantity() : 0;
            int remaining     = item.getQuantity() - alreadyVoided;
            int afterVoid     = remaining - (req.getVoidQuantity() != null ? req.getVoidQuantity() : 0);
            if (afterVoid > 0) { allReachZero = false; break; }
        }
        // An order item NOT in the request keeps its current remaining qty
        for (OrderItem item : order.getItems()) {
            boolean inRequest = request.getItems().stream()
                    .anyMatch(r -> item.getId().equals(r.getOrderItemId()));
            if (!inRequest) {
                int alreadyVoided = item.getVoidedQuantity() != null ? item.getVoidedQuantity() : 0;
                if (item.getQuantity() - alreadyVoided > 0) { allReachZero = false; break; }
            }
        }

        String tier = allReachZero ? "TIER_2" : "TIER_1";

        // ── Key validation ────────────────────────────────────────────────
        if ("TIER_1".equals(tier)) {
            String secKey = request.getSecurityKey() != null ? request.getSecurityKey().trim() : "";
            if (secKey.isEmpty())
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Admin security key is required for a partial void"));
            if (voidCaller.getAdminSecurityKey() == null
                    || !passwordEncoder.matches(secKey, voidCaller.getAdminSecurityKey()))
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Invalid admin security key"));
        } else {
            // TIER_2 — master key
            String mk = request.getMasterKey() != null ? request.getMasterKey().trim() : "";
            if (mk.isEmpty())
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Master key is required to zero out all items on an order"));
            if (!masterKeyService.validateMasterKey(mk))
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Invalid master key"));
        }

        // ── Delegate transactional work to service ────────────────────────
        try {
            Map<String, Object> result = orderService.voidOrderItems(id, request, tier, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/return
     *
     * Post-sale return of physical goods.  No same-day or day-close restriction —
     * returns are available at any time on any order, regardless of date or
     * daily report status.
     *
     * For each item being returned the caller specifies:
     *   totalReturned  = sellableQty + rejectedQty  (validated server-side)
     *
     * sellableQty → stock restored to the chosen restockWarehouse; RETURN_SELLABLE movement.
     * rejectedQty → no stock change; RETURN_REJECTED movement with the actual count.
     *
     * An optional refundAmount may be included.  If present the RETURN ledger entry
     * and the inventory adjustments are committed atomically — both succeed or both
     * roll back.
     *
     * Requires the caller's personal admin security key (BCrypt), not the master key.
     *
     * Body: {
     *   "securityKey":  "...",
     *   "reason":       "...",
     *   "items": [
     *     { "orderItemId": 123, "totalReturned": 2, "sellableQty": 1, "rejectedQty": 1, "restockWarehouse": "wh2" }
     *   ],
     *   "refundAmount": 9.98   // optional
     * }
     */
    @PostMapping("/{id}/return")
    public ResponseEntity<?> processReturn(
            @PathVariable String id,
            @RequestBody ReturnOrderRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // ── Auth ──────────────────────────────────────────────────────────
        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        // ── Basic request validation ──────────────────────────────────────
        if (request.getSecurityKey() == null || request.getSecurityKey().trim().isEmpty())
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Admin security key is required to process a return"));
        if (request.getReason() == null || request.getReason().isBlank())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Return reason is required"));
        if (request.getItems() == null || request.getItems().isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "At least one return item is required"));

        // ── Security key validation ───────────────────────────────────────
        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "User not found"));
        if (!isOrderManager(caller))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to process returns (requires the Void & Cancel Orders access)"));
        if (caller.getAdminSecurityKey() == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message",
                            "No admin security key has been set for your account. Ask your Super Admin to assign one."));
        if (!passwordEncoder.matches(request.getSecurityKey().trim(), caller.getAdminSecurityKey()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Invalid admin security key"));

        // ── Delegate transactional work to service ────────────────────────
        try {
            Map<String, Object> result = orderService.processReturn(id, request, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/return-replace — the unified Return / Replace flow (Phase B).
     *
     * Replaces Process Return + Issue Replacement + Create Replacement + Cancel-for-replacement.
     * Voids the returned units off the original (revenue reduced, stock restocked), optionally
     * creates a linked replacement order, and records any refund OWED (not paid — the Refund
     * button settles cash later). Same auth as /return: order-manager + personal admin key.
     */
    @PostMapping("/{id}/return-replace")
    public ResponseEntity<?> returnReplace(
            @PathVariable String id,
            @RequestBody rrbm_backend.dto.ReturnReplaceRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        if (request.getSecurityKey() == null || request.getSecurityKey().trim().isEmpty())
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Admin security key is required for return/replace"));

        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        if (!isOrderManager(caller))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to process returns/replacements (requires the Void & Cancel Orders access)"));
        if (caller.getAdminSecurityKey() == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "No admin security key has been set for your account. Ask your Super Admin to assign one."));
        if (!passwordEncoder.matches(request.getSecurityKey().trim(), caller.getAdminSecurityKey()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Invalid admin security key"));

        try {
            Map<String, Object> result = orderService.returnReplace(id, request, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * GET /api/orders/collections/refunds — return events that still owe a refund (status OWED).
     * Feeds the "To Refund" tab on the Collections page. Gated to the collections page.
     */
    @GetMapping("/collections/refunds")
    public ResponseEntity<?> getRefundsOwed() {
        return ResponseEntity.ok(orderService.listRefundsOwed());
    }

    /**
     * POST /api/orders/collections/refunds/{eventId}/pay — pay the refund owed (the Refund button).
     * Reverses the owed cash on the original order and marks the event REFUNDED. Requires the
     * caller's personal admin security key (a cash-out is at least as sensitive as a collection).
     */
    @PostMapping("/collections/refunds/{eventId}/pay")
    public ResponseEntity<?> payRefund(
            @PathVariable Long eventId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required"));

        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        if (!isOrderManager(caller))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to issue refunds (requires the Void & Cancel Orders access)"));

        String key = (body != null && body.get("securityKey") != null) ? body.get("securityKey").toString().trim() : "";
        if (key.isEmpty())
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Admin security key is required to issue a refund"));
        if (caller.getAdminSecurityKey() == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "No admin security key has been set for your account. Ask your Super Admin to assign one."));
        if (!passwordEncoder.matches(key, caller.getAdminSecurityKey()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Invalid admin security key"));

        try {
            return ResponseEntity.ok(orderService.payReturnRefund(eventId, userId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/replacement
     *
     * Creates a new replacement order linked to an order that was previously
     * cancelled for replacement (cancellationType = REPLACEMENT).
     *
     * No elevated key required — standard JWT login is sufficient (Section 2).
     *
     * Guards (in order):
     *   1. JWT required → 401
     *   2. customerName + items validation → 400
     *   3. Original order must exist → 404
     *   4. cancellationType must be REPLACEMENT → 400
     *   5. replacementOrderId must be null (no duplicate) → 400
     *
     * The service method owns the transactional boundary for all four writes:
     * new order save, stock deduction, SALE ledger entry, write-back of
     * replacementOrderId onto the original order.
     *
     * Body: same structure as POST /api/orders (CreateOrderRequest).
     * The originalOrderId is taken from the path variable, not the body.
     * Pre-population of customer/address fields is a frontend responsibility.
     */
    @PostMapping("/{id}/replacement")
    public ResponseEntity<?> createReplacementOrder(
            @PathVariable String id,
            @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // ── Auth ──────────────────────────────────────────────────────────
        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        // ── Basic request validation ──────────────────────────────────────
        String cname = request.getCustomerName();
        if (cname == null || cname.trim().isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "Customer name is required"));
        if (request.getItems() == null || request.getItems().isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "Order must have at least one item"));
        for (CreateOrderRequest.OrderItemRequest it : request.getItems()) {
            if (it.getQuantity() == null || it.getQuantity() <= 0)
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Item quantity must be at least 1"));
            if (it.getUnitPrice() == null || it.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0)
                return ResponseEntity.badRequest()
                        .body(Map.of("message",
                            "Item unit price must be greater than 0 for: "
                            + (it.getProductName() != null ? it.getProductName() : "unknown")));
            if (it.getProductId() == null)
                return ResponseEntity.badRequest()
                        .body(Map.of("message",
                            "Item \"" + (it.getProductName() != null ? it.getProductName() : "unknown")
                            + "\" must be selected from the product catalog"));
            if (!productRepository.existsById(it.getProductId()))
                return ResponseEntity.badRequest()
                        .body(Map.of("message",
                            "Product \"" + (it.getProductName() != null ? it.getProductName() : "ID " + it.getProductId())
                            + "\" no longer exists in the catalog"));
        }

        // ── Load original order ───────────────────────────────────────────
        Order original = orderRepository.findById(id).orElse(null);
        if (original == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Order not found: " + id));

        // ── Cancellation type guard ───────────────────────────────────────
        if (!"REPLACEMENT".equals(original.getCancellationType()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message",
                            "The specified order was not cancelled for replacement"));

        // ── Duplicate replacement guard ───────────────────────────────────
        if (original.getReplacementOrderId() != null)
            return ResponseEntity.badRequest()
                    .body(Map.of("message",
                            "A replacement order has already been created for this order: "
                            + original.getReplacementOrderId()));

        // ── Delegate transactional work to service ────────────────────────
        try {
            Order saved = orderService.createReplacementOrder(id, request, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(convertToResponse(saved));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/cancel-for-replacement
     *
     * Cancels an order with the intent to create a replacement order.
     * Requires the system master key (same key as Tier 2 void).
     * Sets status = CANCELLED, cancellationType = REPLACEMENT.
     *
     * replacementOrderId is NOT written here — it stays null until the replacement
     * order is created (Step 5 of the build sequence).  A null replacementOrderId
     * with cancellationType = REPLACEMENT is the correct state for "pending replacement".
     *
     * For DELIVERED orders: every item must appear in the request items list with a
     * disposition of 'SELLABLE' (stock restored) or 'REJECTED' (CANCEL_REJECTED
     * movement written; no stock restore).
     *
     * For non-DELIVERED orders: items list is ignored; all stock auto-restores.
     *
     * Body: {
     *   "masterKey":  "rrbm2024",
     *   "reason":     "Items need to be re-encoded",
     *   "items": [                               ← required only for DELIVERED orders
     *     { "orderItemId": 123, "disposition": "SELLABLE" },
     *     { "orderItemId": 124, "disposition": "REJECTED" }
     *   ]
     * }
     */
    @PostMapping("/{id}/cancel-for-replacement")
    public ResponseEntity<?> cancelForReplacement(
            @PathVariable String id,
            @RequestBody CancelForReplacementRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // ── Auth ──────────────────────────────────────────────────────────
        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        User replaceCaller = userRepository.findById(userId).orElse(null);
        if (replaceCaller == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "User not found"));
        if (!isOrderManager(replaceCaller))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to cancel orders for replacement (requires the Void & Cancel Orders access)"));

        // ── Request validation ────────────────────────────────────────────
        if (request.getMasterKey() == null || request.getMasterKey().trim().isEmpty())
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Master key is required to cancel an order for replacement"));
        if (request.getReason() == null || request.getReason().isBlank())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Cancellation reason is required"));

        // ── Load order ────────────────────────────────────────────────────
        Order order = orderRepository.findByIdWithItems(id).orElse(null);
        if (order == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Order not found: " + id));

        // ── Already-cancelled guard ───────────────────────────────────────
        if ("CANCELLED".equals(order.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Order is already cancelled"));

        // ── Master key validation ─────────────────────────────────────────
        if (!masterKeyService.validateMasterKey(request.getMasterKey().trim()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Invalid master key"));

        // ── DELIVERED disposition guard ───────────────────────────────────
        // Service validates full per-item coverage; controller just checks the
        // list is present so callers get a clear message before any DB work.
        if ("DELIVERED".equals(order.getStatus())) {
            if (request.getItems() == null || request.getItems().isEmpty())
                return ResponseEntity.badRequest()
                        .body(Map.of("message",
                                "Item dispositions are required for a DELIVERED order"));
        }

        // ── Delegate transactional work to service ────────────────────────
        try {
            Order cancelled = orderService.cancelOrderForReplacement(id, request, userId);
            return ResponseEntity.ok(convertToResponse(cancelled));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * POST /api/orders/{id}/correct-item
     *
     * "Correct Recorded Item" failsafe for wrong inputs — works on order-history
     * orders including those whose daily report is already closed.  Standalone
     * feature: distinct endpoint, request type, and service path from
     * return / replacement / void.
     *
     * Auth: JWT + the Void & Cancel Orders permission + caller's admin security key
     * (NOT the master key).  All financial deltas post to TODAY so closed daily
     * reports stay immutable.
     */
    @PostMapping("/{id}/correct-item")
    public ResponseEntity<?> correctOrderItem(
            @PathVariable String id,
            @RequestBody CorrectItemRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // ── Auth ──────────────────────────────────────────────────────────
        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        // ── Basic request validation ──────────────────────────────────────
        if (request.getSecurityKey() == null || request.getSecurityKey().trim().isEmpty())
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Admin security key is required to correct an item"));
        if (request.getReason() == null || request.getReason().isBlank())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "A reason is required"));

        // ── Security key validation (same mechanism as returns) ───────────
        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "User not found"));
        if (!isOrderManager(caller))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You do not have permission to correct recorded items (requires the Void & Cancel Orders access)"));
        if (caller.getAdminSecurityKey() == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message",
                            "No admin security key has been set for your account. Ask your Super Admin to assign one."));
        if (!passwordEncoder.matches(request.getSecurityKey().trim(), caller.getAdminSecurityKey()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Invalid admin security key"));

        // ── Delegate transactional work to service ────────────────────────
        try {
            Map<String, Object> result = orderService.correctRecordedItem(id, request, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Void/cancel/return orders — SUPER_ADMIN or the 'void-cancel-orders' permission. */
    private boolean isOrderManager(User u) {
        return u != null && u.hasPagePermission("void-cancel-orders");
    }

    /**
     * Helper method to convert Order entity to OrderResponse DTO
     */
    private OrderResponse convertToResponse(Order order) {
        List<OrderResponse.OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderResponse.OrderItemResponse(
                    item.getId(),
                    item.getProductName(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getSubtotal(),
                    item.getWarehouse(),
                    item.getVoidedQuantity() != null ? item.getVoidedQuantity() : 0,
                    item.getBasePrice(),
                    item.getOpRate(),
                    item.getOpPerUnit(),
                    item.getOpAmount()
                ))
                .collect(Collectors.toList());

        return new OrderResponse(
            order.getId(),
            order.getCustomerName(),
            order.getSource(),
            order.getAgentName(),
            order.getFbPage(),
            order.getEcommercePlatform(),
            order.getPaymentMode(),
            order.getPaymentStatus(),
            order.getSubtotal(),
            order.getDiscount(),
            order.getDeliveryFee(),
            order.getTotal(),
            order.getStatus(),
            order.getCancellationReason(),
            order.getNotes(),
            order.getOrderType(),
            order.getAddress(),
            order.getCreatedAt(),
            order.getCreatedBy() != null ? order.getCreatedBy().getFullName() : null,
            itemResponses,
            order.getCancelledAt(),
            order.getCancelledBy() != null ? order.getCancelledBy().getFullName() : null,
            order.getCollectedAt(),
            order.getCollectedBy(),
            order.getRefundedAt(),
            order.getVoidedAmount() != null ? order.getVoidedAmount() : BigDecimal.ZERO,
            order.getReplacementOrderId(),
            order.getOriginalOrderId(),
            order.getCancellationType(),
            order.getAgentId(),
            order.isImported(),
            order.getImportRef(),
            order.getScheduledDeliveryDate(),
            order.getDeliveredAt(),
            order.getDeliveryChangeLog(),
            order.isDeliveryConfirmed(),
            order.getDeliveryConfirmedAt(),
            order.getDeliveryDriver(),
            order.getDeliveryHelpers(),
            order.getDeliveryCoordinatedBy(),
            order.getDeliveryNotes()
        );
    }
}
