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

    public DailyReportController(DailyReportService dailyReportService,
                                  ActivityLogService activityLogService,
                                  DeliveryLogRepository deliveryLogRepo,
                                  MasterKeyService masterKeyService,
                                  DeliveryLogItemRepository deliveryLogItemRepo,
                                  JwtUtil jwtUtil,
                                  UserRepository userRepository,
                                  InventoryMovementRepository movementRepo,
                                  ProductRepository productRepository) {
        this.dailyReportService  = dailyReportService;
        this.activityLogService  = activityLogService;
        this.deliveryLogRepo     = deliveryLogRepo;
        this.masterKeyService    = masterKeyService;
        this.deliveryLogItemRepo = deliveryLogItemRepo;
        this.jwtUtil             = jwtUtil;
        this.userRepository      = userRepository;
        this.movementRepo        = movementRepo;
        this.productRepository   = productRepository;
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

        boolean forceClose    = Boolean.TRUE.equals(body.get("forceClose")) ||
                               "true".equalsIgnoreCase((String) body.getOrDefault("forceClose", "false"));
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

        // ── Source 1: Delivery rejections (unchanged query) ──────────────────
        for (DeliveryLogItem item : deliveryLogItemRepo.findRejectedByDateRange(startDate, endDate)) {
            DeliveryLog log = item.getDeliveryLog();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date",         log.getReportDate() != null ? log.getReportDate().toString() : "");
            row.put("source",       "DELIVERY");
            row.put("reference",    log.getReceiptNumber());
            row.put("productName",  item.getProductName());
            row.put("rejectedQty",  item.getRejectedQty());
            row.put("reason",       null);
            row.put("supplierName", log.getSupplierName());
            row.put("poNumber",     log.getPoNumber());
            row.put("receivedBy",   log.getReceivedBy());
            items.add(row);
        }

        // ── Source 2: Void / cancel / return rejections ───────────────────────
        List<InventoryMovement> movements = movementRepo.findRejectedMovementsByDateRange(
                startDate.atStartOfDay(),
                endDate.plusDays(1).atStartOfDay());

        Set<Long> productIds = new HashSet<>();
        for (InventoryMovement m : movements) productIds.add(m.getProductId());
        Map<Long, String> productNames = new HashMap<>();
        if (!productIds.isEmpty())
            productRepository.findAllById(productIds).forEach(p -> productNames.put(p.getId(), p.getName()));

        for (InventoryMovement m : movements) {
            String source = "CANCEL_REJECTED".equals(m.getMovementType()) ? "CANCEL"
                          : "RETURN_REJECTED".equals(m.getMovementType()) ? "RETURN"
                          : "VOID";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date",        m.getCreatedAt().toLocalDate().toString());
            row.put("source",      source);
            row.put("reference",   m.getReferenceId());
            row.put("productName", productNames.getOrDefault(m.getProductId(), "Unknown"));
            row.put("rejectedQty", m.getQuantity());
            row.put("reason",      m.getReason());
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
}
