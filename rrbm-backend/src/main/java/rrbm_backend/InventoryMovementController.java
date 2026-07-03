package rrbm_backend;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Inventory Movement Log (read-only reporting over the existing inventory_movements audit table).
 *
 * <p>Every stock change already writes an {@link InventoryMovement} row via
 * {@link InventoryService#logMovement}. This controller groups/lists those rows by day or week so
 * the Inventory page can show exactly what came IN and what went OUT each day — a safeguard for
 * spotting unexplained deductions. No new data is captured here; it is a live query.
 *
 * GET /api/inventory/movements?start=YYYY-MM-DD&end=YYYY-MM-DD
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryMovementController {

    private final JwtUtil                     jwtUtil;
    private final InventoryMovementRepository  movementRepository;
    private final ProductRepository            productRepository;

    public InventoryMovementController(JwtUtil jwtUtil,
                                       InventoryMovementRepository movementRepository,
                                       ProductRepository productRepository) {
        this.jwtUtil            = jwtUtil;
        this.movementRepository = movementRepository;
        this.productRepository  = productRepository;
    }

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    @GetMapping("/movements")
    public ResponseEntity<?> movements(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        if (userIdFromHeader(authHeader) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        LocalDate from = start != null ? start : LocalDate.now();
        LocalDate to   = end   != null ? end   : from;
        if (to.isBefore(from)) { LocalDate t = from; from = to; to = t; }

        LocalDateTime startDt = from.atStartOfDay();
        LocalDateTime endDt   = to.atTime(LocalTime.MAX);

        List<InventoryMovement> rows =
                movementRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDt, endDt);

        // Resolve product names/codes in one query
        Set<Long> productIds = rows.stream().map(InventoryMovement::getProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Product> products = new HashMap<>();
        productRepository.findAllById(productIds).forEach(p -> products.put(p.getId(), p));

        int totalIn = 0, totalOut = 0;
        // Per-day rollup (for the weekly view)
        Map<LocalDate, int[]> byDayAgg = new TreeMap<>(Comparator.reverseOrder()); // date → [in, out, count]

        List<Map<String, Object>> movements = new ArrayList<>();
        for (InventoryMovement m : rows) {
            int qty = m.getQuantity() != null ? m.getQuantity() : 0;
            if (qty >= 0) totalIn += qty; else totalOut += -qty;

            LocalDate day = m.getCreatedAt() != null ? m.getCreatedAt().toLocalDate() : from;
            int[] agg = byDayAgg.computeIfAbsent(day, d -> new int[3]);
            if (qty >= 0) agg[0] += qty; else agg[1] += -qty;
            agg[2] += 1;

            Product p = products.get(m.getProductId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",           m.getId());
            row.put("createdAt",    m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
            row.put("date",         day.toString());
            row.put("productId",    m.getProductId());
            row.put("productName",  p != null ? p.getName() : ("#" + m.getProductId()));
            row.put("productCode",  p != null ? p.getProductCode() : null);
            row.put("movementType", m.getMovementType());
            row.put("warehouse",    m.getWarehouse());
            row.put("quantity",     qty);
            row.put("direction",    qty >= 0 ? "IN" : "OUT");
            row.put("referenceId",  m.getReferenceId());
            row.put("reason",       m.getReason());
            movements.add(row);
        }

        List<Map<String, Object>> byDay = new ArrayList<>();
        for (Map.Entry<LocalDate, int[]> e : byDayAgg.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date",  e.getKey().toString());
            d.put("in",    e.getValue()[0]);
            d.put("out",   e.getValue()[1]);
            d.put("net",   e.getValue()[0] - e.getValue()[1]);
            d.put("count", e.getValue()[2]);
            byDay.add(d);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalIn",  totalIn);
        summary.put("totalOut", totalOut);
        summary.put("net",      totalIn - totalOut);
        summary.put("count",    rows.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start",     from.toString());
        result.put("end",       to.toString());
        result.put("summary",   summary);
        result.put("byDay",     byDay);
        result.put("movements", movements);
        return ResponseEntity.ok(result);
    }
}
