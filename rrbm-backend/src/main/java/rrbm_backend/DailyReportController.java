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
@RequestMapping("/api/reports")
public class DailyReportController {

    private final DailyReportService          dailyReportService;
    private final ActivityLogService          activityLogService;
    private final DeliveryLogRepository       deliveryLogRepo;
    private final MasterKeyService            masterKeyService;
    private final DeliveryLogItemRepository   deliveryLogItemRepo;
    private final JwtUtil                     jwtUtil;
    private final UserRepository              userRepository;
    private final InventoryMovementRepository movementRepo;
    private final ProductRepository           productRepository;
    private final ManualRejectedItemRepository manualRejectedRepo;
    private final CashLedgerRepository         cashLedgerRepo;

    public DailyReportController(DailyReportService dailyReportService,
                                  ActivityLogService activityLogService,
                                  DeliveryLogRepository deliveryLogRepo,
                                  MasterKeyService masterKeyService,
                                  DeliveryLogItemRepository deliveryLogItemRepo,
                                  JwtUtil jwtUtil,
                                  UserRepository userRepository,
                                  InventoryMovementRepository movementRepo,
                                  ProductRepository productRepository,
                                  ManualRejectedItemRepository manualRejectedRepo,
                                  CashLedgerRepository cashLedgerRepo) {
        this.dailyReportService  = dailyReportService;
        this.activityLogService  = activityLogService;
        this.deliveryLogRepo     = deliveryLogRepo;
        this.masterKeyService    = masterKeyService;
        this.deliveryLogItemRepo = deliveryLogItemRepo;
        this.jwtUtil             = jwtUtil;
        this.userRepository      = userRepository;
        this.movementRepo        = movementRepo;
        this.productRepository   = productRepository;
        this.manualRejectedRepo  = manualRejectedRepo;
        this.cashLedgerRepo      = cashLedgerRepo;
    }

    // ── GET /api/reports/cash-flow/{date} ─────────────────────────────────────
    // The day's cash-on-hand ledger entries, for inclusion in the daily report.
    // Read-only; lives under /api/reports so daily-report viewers can access it.
    @GetMapping("/cash-flow/{date}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getCashFlowForDate(@PathVariable String date) {
        LocalDate d;
        try { d = LocalDate.parse(date); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("message", "Invalid date")); }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CashLedgerEntry e : cashLedgerRepo.findByEntryDateOrderByIdAsc(d)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",        e.getId());
            m.put("entryType", e.getEntryType());
            m.put("amount",    e.getAmount());
            m.put("note",      e.getNote());
            m.put("createdBy", e.getCreatedByName());
            m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    // --- Close daily sales ---
    @PostMapping("/close-daily")
    public ResponseEntity<?> closeDailySales(@RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String masterKey = (String) body.get("masterKey");
        if (masterKey == null || masterKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Master key is required"));
        }

        if (!masterKeyService.validateMasterKey(masterKey)) {
            return ResponseEntity.status(403).body(Map.of("message", "Invalid master key"));
        }

        // Extract userId from JWT — reject if no valid token is present.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("message", "Authentication required"));
        }
        Long userId;
        try {
            userId = jwtUtil.extractUserId(authHeader.substring(7));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid or expired token"));
        }
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid or expired token"));
        }
        User caller = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
        String userName = caller.getFullName();

        // Accept forceClose as a JSON boolean (true/false) OR a string ("true"/"false").
        // String.valueOf avoids a ClassCastException when the client sends a real JSON
        // boolean false — the previous (String) cast on a Boolean threw a 500.
        boolean forceClose    = Boolean.TRUE.equals(body.get("forceClose")) ||
                               "true".equalsIgnoreCase(String.valueOf(body.getOrDefault("forceClose", "false")));
        String adminKey       = (String) body.getOrDefault("adminSecurityKey", "");
        String superAdminKey  = (String) body.getOrDefault("superAdminSecurityKey", "");

        try {
            DailyReport report = dailyReportService.closeDailySales(
                    userId, userName, LocalDate.now(), forceClose, adminKey, superAdminKey);
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Daily sales closed successfully");
            response.put("report", report);
            if (report.getUnfulfilledOrders() > 0) {
                response.put("unfulfilledOrders", report.getUnfulfilledOrders());
                response.put("unfulfilledAmount", report.getUnfulfilledAmount());
            }
            return ResponseEntity.ok(response);
        } catch (OpenOrdersException e) {
            // Return a structured 409 so the frontend can show the override modal
            return ResponseEntity.status(409).body(Map.of(
                "error",   "ACTIVE_ORDERS",
                "message", e.getMessage(),
                "count",   e.getCount(),
                "amount",  e.getAmount()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // --- Check if today is already closed ---
    @GetMapping("/daily-status")
    public ResponseEntity<?> getDailyStatus() {
        var report = dailyReportService.getReportByDate(LocalDate.now());
        if (report.isPresent()) {
            DailyReport r = report.get();
            // N-1: resolve closedBy ID → name so the banner can show "Closed by …"
            String closedByName = null;
            if (r.getClosedBy() != null) {
                closedByName = userRepository.findById(r.getClosedBy())
                        .map(User::getFullName).orElse("Unknown");
            }
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("closed",       true);
            resp.put("report",       r);
            resp.put("closedByName", closedByName != null ? closedByName : "Unknown");
            return ResponseEntity.ok(resp);
        }
        return ResponseEntity.ok(Map.of("closed", false));
    }

    // --- Get daily report by date ---
    @GetMapping("/daily/{date}")
    public ResponseEntity<?> getDailyReport(@PathVariable String date) {
        LocalDate d = LocalDate.parse(date);
        var report = dailyReportService.getReportByDate(d);
        return report.map(r -> ResponseEntity.ok((Object) r))
                .orElse(ResponseEntity.ok(Map.of("message", "No report for this date")));
    }

    // --- Get reports for date range (weekly/monthly views) ---
    @GetMapping("/range")
    public ResponseEntity<?> getReportsRange(@RequestParam String start, @RequestParam String end) {
        return ResponseEntity.ok(dailyReportService.getReportsBetween(
                LocalDate.parse(start), LocalDate.parse(end)));
    }

    // --- Activity Log endpoints ---
    @GetMapping("/activity-log/today")
    public ResponseEntity<?> getTodayActivityLog() {
        return ResponseEntity.ok(activityLogService.getTodayLogs());
    }

    @GetMapping("/activity-log/{date}")
    public ResponseEntity<?> getActivityLog(@PathVariable String date) {
        return ResponseEntity.ok(activityLogService.getLogsByDate(LocalDate.parse(date)));
    }

    // --- Delivery Log endpoints ---
    @GetMapping("/deliveries")
    public ResponseEntity<?> getAllDeliveries() {
        return ResponseEntity.ok(deliveryLogRepo.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/deliveries/{date}")
    public ResponseEntity<?> getDeliveriesByDate(@PathVariable String date) {
        return ResponseEntity.ok(deliveryLogRepo.findByReportDateOrderByCreatedAtDesc(LocalDate.parse(date)));
    }

    // ── GET /api/reports/rejected-items?start=YYYY-MM-DD&end=YYYY-MM-DD ──────
    // Returns rejected items from all sources: delivery receiving, voids, cancels, and returns.
    // Defaults to the current calendar month when params are omitted.
    @GetMapping("/rejected-items")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getRejectedItems(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        LocalDate startDate = (start != null && !start.isBlank())
                ? LocalDate.parse(start)
                : LocalDate.now().withDayOfMonth(1);
        LocalDate endDate = (end != null && !end.isBlank())
                ? LocalDate.parse(end)
                : LocalDate.now();

        List<Map<String, Object>> items = new ArrayList<>();

        // Pull all three sources, then resolve product names + codes in one batch.
        List<DeliveryLogItem> deliveryItems = deliveryLogItemRepo.findRejectedByDateRange(startDate, endDate);
        List<InventoryMovement> movements = movementRepo.findRejectedMovementsByDateRange(
                startDate.atStartOfDay(),
                endDate.plusDays(1).atStartOfDay());
        List<ManualRejectedItem> manualItems =
                manualRejectedRepo.findByReportDateBetweenOrderByReportDateDesc(startDate, endDate);

        Set<Long> productIds = new HashSet<>();
        for (DeliveryLogItem i : deliveryItems)  if (i.getProductId()  != null) productIds.add(i.getProductId());
        for (InventoryMovement m : movements)    if (m.getProductId()  != null) productIds.add(m.getProductId());
        for (ManualRejectedItem mr : manualItems) if (mr.getProductId() != null) productIds.add(mr.getProductId());
        Map<Long, Product> productById = new HashMap<>();
        if (!productIds.isEmpty())
            productRepository.findAllById(productIds).forEach(p -> productById.put(p.getId(), p));

        // ── Source 1: Delivery rejections ────────────────────────────────────
        for (DeliveryLogItem item : deliveryItems) {
            DeliveryLog log = item.getDeliveryLog();
            Product prod = item.getProductId() != null ? productById.get(item.getProductId()) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date",         log.getReportDate() != null ? log.getReportDate().toString() : "");
            row.put("source",       "DELIVERY");
            row.put("reference",    log.getReceiptNumber());
            row.put("productCode",  prod != null ? prod.getProductCode() : null);
            row.put("productName",  item.getProductName());
            row.put("rejectedQty",  item.getRejectedQty());
            row.put("reason",       null);
            row.put("supplierName", log.getSupplierName());
            row.put("poNumber",     log.getPoNumber());
            row.put("receivedBy",   log.getReceivedBy());
            items.add(row);
        }

        // ── Source 2: Void / cancel / return rejections ───────────────────────
        for (InventoryMovement m : movements) {
            Product prod = m.getProductId() != null ? productById.get(m.getProductId()) : null;
            String source = "CANCEL_REJECTED".equals(m.getMovementType()) ? "CANCEL"
                          : "RETURN_REJECTED".equals(m.getMovementType()) ? "RETURN"
                          : "VOID";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date",        m.getCreatedAt().toLocalDate().toString());
            row.put("source",      source);
            row.put("reference",   m.getReferenceId());
            row.put("productCode", prod != null ? prod.getProductCode() : null);
            row.put("productName", prod != null ? prod.getName() : "Unknown");
            row.put("rejectedQty", m.getQuantity());
            row.put("reason",      m.getReason());
            items.add(row);
        }

        // ── Source 3: Manually-entered rejected items ─────────────────────────
        // Record-only entries (do not affect stock). Carry an "id" so the UI can
        // expose edit/delete for these rows only.
        for (ManualRejectedItem mr : manualItems) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",          mr.getId());
            row.put("date",        mr.getReportDate() != null ? mr.getReportDate().toString() : "");
            row.put("source",      "MANUAL");
            row.put("reference",   mr.getCreatedBy());
            row.put("productCode", mr.getProductCode());
            row.put("productName", mr.getProductName());
            row.put("rejectedQty", mr.getRejectedQty());
            row.put("reason",      mr.getReason());
            items.add(row);
        }

        // Sort merged list by date descending
        items.sort((a, b) -> ((String) b.get("date")).compareTo((String) a.get("date")));

        return ResponseEntity.ok(Map.of(
                "items", items,
                "count", items.size(),
                "start", startDate.toString(),
                "end",   endDate.toString()
        ));
    }

    // ── Manual rejected items: create / update / delete ───────────────────────
    // Restricted to accounting + super-admin (page access alone is not enough).

    /** Returns the caller User from the Bearer token, or null. */
    private User callerFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        Long uid = jwtUtil.extractUserId(authHeader.substring(7));
        return uid != null ? userRepository.findById(uid).orElse(null) : null;
    }

    /** True only for SUPER_ADMIN or ACCOUNTING. */
    private boolean canManageManualRejected(User u) {
        return u != null && ("SUPER_ADMIN".equals(u.getRole()) || "ACCOUNTING".equals(u.getRole()));
    }

    @PostMapping("/rejected-items/manual")
    @Transactional
    public ResponseEntity<?> createManualRejected(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User caller = callerFromHeader(authHeader);
        if (!canManageManualRejected(caller)) {
            return ResponseEntity.status(403).body(Map.of("message", "Only accounting and super-admin can add rejected items"));
        }
        // Product must be picked from inventory — name + code are taken from the
        // product record (authoritative), never from free-typed text.
        Long productId = body.get("productId") != null ? ((Number) body.get("productId")).longValue() : null;
        if (productId == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Select a product from inventory"));
        }
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Selected product was not found in inventory"));
        }
        int qty = body.get("rejectedQty") != null ? ((Number) body.get("rejectedQty")).intValue() : 0;
        if (qty <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Rejected quantity must be greater than 0"));
        }
        ManualRejectedItem mr = new ManualRejectedItem();
        mr.setReportDate(parseDateOrToday(body.get("date")));
        mr.setProductId(productId);
        mr.setProductCode(product.getProductCode());
        mr.setProductName(product.getName());
        mr.setRejectedQty(qty);
        mr.setReason(body.get("reason") != null ? body.get("reason").toString().trim() : null);
        mr.setCreatedBy(caller.getFullName());
        ManualRejectedItem saved = manualRejectedRepo.save(mr);
        activityLogService.log(caller.getId(), caller.getFullName(), "ADD_MANUAL_REJECTED",
            "Added manual rejected item: " + qty + " × " + product.getName(), "REJECTED_ITEM", String.valueOf(saved.getId()));
        return ResponseEntity.ok(Map.of("message", "Rejected item added", "id", saved.getId()));
    }

    @PutMapping("/rejected-items/manual/{id}")
    @Transactional
    public ResponseEntity<?> updateManualRejected(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User caller = callerFromHeader(authHeader);
        if (!canManageManualRejected(caller)) {
            return ResponseEntity.status(403).body(Map.of("message", "Only accounting and super-admin can edit rejected items"));
        }
        ManualRejectedItem mr = manualRejectedRepo.findById(id).orElse(null);
        if (mr == null) return ResponseEntity.notFound().build();
        // If a product is provided, re-derive name + code from inventory (authoritative).
        if (body.containsKey("productId")) {
            Long productId = body.get("productId") != null ? ((Number) body.get("productId")).longValue() : null;
            if (productId == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "Select a product from inventory"));
            }
            Product product = productRepository.findById(productId).orElse(null);
            if (product == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "Selected product was not found in inventory"));
            }
            mr.setProductId(productId);
            mr.setProductCode(product.getProductCode());
            mr.setProductName(product.getName());
        }
        if (body.containsKey("rejectedQty")) {
            int qty = body.get("rejectedQty") != null ? ((Number) body.get("rejectedQty")).intValue() : 0;
            if (qty <= 0) return ResponseEntity.badRequest().body(Map.of("message", "Rejected quantity must be greater than 0"));
            mr.setRejectedQty(qty);
        }
        if (body.containsKey("date"))      mr.setReportDate(parseDateOrToday(body.get("date")));
        if (body.containsKey("reason"))    mr.setReason(body.get("reason") != null ? body.get("reason").toString().trim() : null);
        manualRejectedRepo.save(mr);
        activityLogService.log(caller.getId(), caller.getFullName(), "EDIT_MANUAL_REJECTED",
            "Edited manual rejected item #" + id, "REJECTED_ITEM", String.valueOf(id));
        return ResponseEntity.ok(Map.of("message", "Rejected item updated"));
    }

    @DeleteMapping("/rejected-items/manual/{id}")
    @Transactional
    public ResponseEntity<?> deleteManualRejected(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        User caller = callerFromHeader(authHeader);
        if (!canManageManualRejected(caller)) {
            return ResponseEntity.status(403).body(Map.of("message", "Only accounting and super-admin can delete rejected items"));
        }
        ManualRejectedItem mr = manualRejectedRepo.findById(id).orElse(null);
        if (mr == null) return ResponseEntity.notFound().build();
        manualRejectedRepo.delete(mr);
        activityLogService.log(caller.getId(), caller.getFullName(), "DELETE_MANUAL_REJECTED",
            "Deleted manual rejected item #" + id + " (" + mr.getProductName() + ")", "REJECTED_ITEM", String.valueOf(id));
        return ResponseEntity.ok(Map.of("message", "Rejected item deleted"));
    }

    private LocalDate parseDateOrToday(Object raw) {
        if (raw == null || raw.toString().isBlank()) return LocalDate.now();
        try { return LocalDate.parse(raw.toString().trim()); }
        catch (Exception e) { return LocalDate.now(); }
    }
}
