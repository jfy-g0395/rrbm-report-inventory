package rrbm_backend;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Import history (read-only).
 *
 * GET /api/import/history            — imported batches grouped by date + uploader
 * GET /api/import/history/batch      — imported orders + expenses for one date
 * GET /api/import/history/{importRef}— single imported order detail
 * GET /api/import/history/logs       — commit-log summaries
 * GET /api/import/history/logs/{id}  — single commit-log detail
 *
 * The legacy CSV upload/validate/commit pipeline was removed (2026-07-24) — it was
 * superseded by the e-commerce importer (POST /api/orders/batch) and the backdated
 * "Add Records" flow (POST /api/backdated/commit), and was no longer reachable from
 * the UI. Only these read-only history endpoints remain.
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private static final Set<String> UPLOAD_ROLES =
            Set.of("ACCOUNTING", "ADMIN", "ADMINISTRATOR", "SUPER_ADMIN");

    private final UserRepository            userRepository;
    private final JwtUtil                   jwtUtil;
    private final OrderRepository           orderRepository;
    private final ExpenseRepository         expenseRepository;
    private final ImportCommitLogRepository importCommitLogRepository;

    public ImportController(UserRepository userRepository,
                            JwtUtil jwtUtil,
                            OrderRepository orderRepository,
                            ExpenseRepository expenseRepository,
                            ImportCommitLogRepository importCommitLogRepository) {
        this.userRepository            = userRepository;
        this.jwtUtil                   = jwtUtil;
        this.orderRepository           = orderRepository;
        this.expenseRepository         = expenseRepository;
        this.importCommitLogRepository = importCommitLogRepository;
    }

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    private User resolveUploadUser(String authHeader) {
        Long userId = userIdFromHeader(authHeader);
        if (userId == null) return null;
        return userRepository.findById(userId).orElse(null);
    }

    private ResponseEntity<?> checkRole(User user) {
        if (!UPLOAD_ROLES.contains(user.getRole())) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Upload permission requires ACCOUNTING or ADMIN role"));
        }
        return null;
    }

    // ── GET /api/import/history/batch ─────────────────────────────────────────

    @GetMapping("/history/batch")
    public ResponseEntity<?> getImportBatchDetail(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        List<Order>   orders   = orderRepository.findImportedOrdersWithCreatorByDateRange(date, date);
        List<Expense> expenses = expenseRepository.findImportedExpensesByDateRange(date, date);

        List<Map<String, Object>> orderMaps = orders.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId",   o.getId());
            m.put("customer",  o.getCustomerName());
            m.put("source",    o.getSource());
            m.put("total",     o.getTotal());
            m.put("importRef", o.getImportRef());
            return m;
        }).collect(Collectors.toList());

        List<Map<String, Object>> expenseMaps = expenses.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date",            e.getDate() != null ? e.getDate().toString() : null);
            m.put("totalAmount",     e.getTotalAmount());
            m.put("paymentMethod",   e.getPaymentMethod());
            m.put("referenceNumber", e.getReferenceNumber());
            m.put("importRef",       e.getImportRef());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("date",     date.toString());
        response.put("orders",   orderMaps);
        response.put("expenses", expenseMaps);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/import/history ───────────────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<?> getImportHistory(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        LocalDate endDate   = end   != null ? end   : LocalDate.now();
        LocalDate startDate = start != null ? start : endDate.minusDays(30);

        List<Order>   importedOrders   = orderRepository.findImportedOrdersWithCreatorByDateRange(startDate, endDate);
        List<Expense> importedExpenses = expenseRepository.findImportedExpensesByDateRange(startDate, endDate);

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        for (Order o : importedOrders) {
            LocalDate d    = o.getCreatedAt().toLocalDate();
            String    name = o.getCreatedBy() != null ? o.getCreatedBy().getFullName() : "Unknown";
            String    key  = d + "|" + name;
            Map<String, Object> batch = merged.computeIfAbsent(key, k -> initBatch(d, name));
            batch.put("ordersCount",     (int) batch.get("ordersCount") + 1);
            BigDecimal cur = (BigDecimal) batch.get("totalOrderValue");
            batch.put("totalOrderValue", cur.add(o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO));
        }

        for (Expense e : importedExpenses) {
            LocalDate d    = e.getDate();
            String    name = e.getAdminName() != null ? e.getAdminName() : "Unknown";
            String    key  = d + "|" + name;
            Map<String, Object> batch = merged.computeIfAbsent(key, k -> initBatch(d, name));
            batch.put("expensesCount",      (int) batch.get("expensesCount") + 1);
            BigDecimal cur = (BigDecimal) batch.get("totalExpenseAmount");
            batch.put("totalExpenseAmount", cur.add(e.getTotalAmount() != null ? e.getTotalAmount() : BigDecimal.ZERO));
        }

        List<Map<String, Object>> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(m -> (String) m.get("importDate"), Comparator.reverseOrder()));

        return ResponseEntity.ok(result);
    }

    private static Map<String, Object> initBatch(LocalDate date, String adminName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("importDate",         date.toString());
        m.put("importedBy",         adminName);
        m.put("ordersCount",        0);
        m.put("expensesCount",      0);
        m.put("totalOrderValue",    BigDecimal.ZERO);
        m.put("totalExpenseAmount", BigDecimal.ZERO);
        return m;
    }

    // ── GET /api/import/history/{importRef} ───────────────────────────────────

    @GetMapping("/history/{importRef}")
    public ResponseEntity<?> getImportDetail(
            @PathVariable String importRef,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        Optional<Order> optOrder = orderRepository.findByImportRefWithItems(importRef);
        if (optOrder.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error",
                    "No imported order found with importRef: " + importRef));
        }

        Order order = optOrder.get();
        List<Map<String, Object>> itemMaps = order.getItems().stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productName", item.getProductName());
            m.put("quantity",    item.getQuantity());
            m.put("unitPrice",   item.getUnitPrice());
            m.put("subtotal",    item.getSubtotal());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("importRef",  order.getImportRef());
        response.put("orderId",    order.getId());
        response.put("orderDate",  order.getCreatedAt() != null
                ? order.getCreatedAt().toLocalDate().toString() : null);
        response.put("customer",   order.getCustomerName());
        response.put("total",      order.getTotal());
        response.put("isImported", order.isImported());
        response.put("items",      itemMaps);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/import/history/logs ───────────────────────────────────────────

    @GetMapping("/history/logs")
    public ResponseEntity<?> getCommitLogList(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        List<Map<String, Object>> result = new ArrayList<>();
        for (ImportCommitLog log : importCommitLogRepository.findAllByOrderByCommittedAtDesc()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", log.getId());
            entry.put("committedAt", log.getCommittedAt().toString());
            entry.put("batchDate", log.getBatchDate().toString());
            entry.put("committedBy", log.getCommittedById());

            // Extract lightweight counts from stored JSON
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> json = new ObjectMapper().readValue(log.getResultJson(), Map.class);
                entry.put("ordersCount", ((List<?>) json.getOrDefault("committedOrders", List.of())).size());
                entry.put("expensesCount", ((List<?>) json.getOrDefault("committedExpenses", List.of())).size());
                entry.put("skippedCount", ((List<?>) json.getOrDefault("skippedOrders", List.of())).size()
                        + ((List<?>) json.getOrDefault("skippedExpenses", List.of())).size());
            } catch (Exception e) {
                entry.put("ordersCount", 0);
                entry.put("expensesCount", 0);
                entry.put("skippedCount", 0);
            }

            result.add(entry);
        }

        return ResponseEntity.ok(result);
    }

    // ── GET /api/import/history/logs/{id} ─────────────────────────────────────

    @GetMapping("/history/logs/{id}")
    public ResponseEntity<?> getCommitLogDetail(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        ImportCommitLog log = importCommitLogRepository.findById(id).orElse(null);
        if (log == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Commit log not found"));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", log.getId());
        response.put("importRef", log.getImportRef());
        response.put("committedAt", log.getCommittedAt().toString());
        response.put("batchDate", log.getBatchDate().toString());
        response.put("committedBy", log.getCommittedById());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(log.getResultJson(), Map.class);
            response.put("result", parsed);
        } catch (Exception e) {
            response.put("result", Map.of());
        }

        return ResponseEntity.ok(response);
    }
}
