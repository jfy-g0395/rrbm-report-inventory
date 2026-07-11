package rrbm_backend;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Resellers & Distributors registry (S-A1). Mirrors {@link AgentController} minus
 * commission machinery; adds a per-reseller product price map and an order-tracking view.
 *
 *  POST  /api/resellers                 — register (type RESELLER|DISTRIBUTOR)
 *  GET   /api/resellers?type=&status=&q= — list, enriched with outstanding-collection totals
 *  GET   /api/resellers/{id}            — detail
 *  PUT   /api/resellers/{id}            — update mutable fields
 *  PATCH /api/resellers/{id}/status     — toggle ACTIVE/INACTIVE
 *  GET   /api/resellers/{id}/prices     — price map
 *  PUT   /api/resellers/{id}/prices     — replace the price map
 *  GET   /api/resellers/{id}/orders     — that reseller's orders + outstanding summary
 */
@RestController
@RequestMapping("/api/resellers")
public class ResellerController {

    private final ResellerRepository             resellerRepository;
    private final ResellerProductPriceRepository priceRepository;
    private final OrderRepository                orderRepository;
    private final UserRepository                 userRepository;
    private final ActivityLogService             activityLogService;
    private final JwtUtil                         jwtUtil;

    public ResellerController(ResellerRepository resellerRepository,
                              ResellerProductPriceRepository priceRepository,
                              OrderRepository orderRepository,
                              UserRepository userRepository,
                              ActivityLogService activityLogService,
                              JwtUtil jwtUtil) {
        this.resellerRepository = resellerRepository;
        this.priceRepository    = priceRepository;
        this.orderRepository    = orderRepository;
        this.userRepository     = userRepository;
        this.activityLogService = activityLogService;
        this.jwtUtil            = jwtUtil;
    }

    // ── POST /api/resellers ────────────────────────────────────────────────

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        String type          = upper(trim(body, "type"));
        String name          = trim(body, "name");
        String contactPerson = trim(body, "contactPerson");
        String contactNumber = trim(body, "contactNumber");
        String address       = trim(body, "address");

        if (!"RESELLER".equals(type) && !"DISTRIBUTOR".equals(type))
            return ResponseEntity.badRequest().body(Map.of("error", "type must be RESELLER or DISTRIBUTOR"));
        if (isBlank(name))          return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        if (isBlank(contactPerson)) return ResponseEntity.badRequest().body(Map.of("error", "contactPerson is required"));
        if (isBlank(contactNumber)) return ResponseEntity.badRequest().body(Map.of("error", "contactNumber is required"));
        if (isBlank(address))       return ResponseEntity.badRequest().body(Map.of("error", "address is required"));

        int year      = LocalDate.now().getYear();
        String codePfx = ("RESELLER".equals(type) ? "RSL-" : "DST-") + year + "-";
        int nextSeq   = resellerRepository.maxSequenceForPrefix(codePfx + "%") + 1;

        Reseller r = new Reseller();
        r.setResellerCode(String.format("%s%04d", codePfx, nextSeq));
        r.setType(type);
        r.setName(name);
        r.setContactPerson(contactPerson);
        r.setContactNumber(contactNumber);
        r.setAddress(address);
        r.setNotes(trim(body, "notes"));
        r.setDeliveryDays(trim(body, "deliveryDays"));
        r.setDeliveryTimeWindow(trim(body, "deliveryTimeWindow"));
        r.setCreatedBy(adminId);

        Reseller saved = resellerRepository.save(r);
        return ResponseEntity.status(201).body(toMap(saved));
    }

    // ── GET /api/resellers?type=&status=&q= ────────────────────────────────

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "type",   defaultValue = "ALL") String type,
            @RequestParam(value = "status", defaultValue = "ALL") String status,
            @RequestParam(value = "q",      required = false) String q) {

        boolean allType   = "ALL".equalsIgnoreCase(type);
        boolean allStatus = "ALL".equalsIgnoreCase(status);

        List<Reseller> resellers;
        if (allType && allStatus) {
            resellers = resellerRepository.findAll(Sort.by("name").ascending());
        } else if (allType) {
            resellers = resellerRepository.findByStatusOrderByNameAsc(status.toUpperCase());
        } else if (allStatus) {
            resellers = resellerRepository.findByTypeOrderByNameAsc(type.toUpperCase());
        } else {
            resellers = resellerRepository.findByTypeAndStatusOrderByNameAsc(type.toUpperCase(), status.toUpperCase());
        }

        if (q != null && !q.isBlank()) {
            final String needle = q.trim().toLowerCase();
            resellers = resellers.stream()
                    .filter(r -> (r.getName() != null && r.getName().toLowerCase().contains(needle))
                              || (r.getContactPerson() != null && r.getContactPerson().toLowerCase().contains(needle))
                              || (r.getResellerCode() != null && r.getResellerCode().toLowerCase().contains(needle)))
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> result = resellers.stream()
                .map(this::toMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // ── GET /api/resellers/{id} ────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        Reseller r = resellerRepository.findById(id).orElse(null);
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "Reseller not found"));
        return ResponseEntity.ok(toMap(r));
    }

    // ── PUT /api/resellers/{id} ────────────────────────────────────────────

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Reseller r = resellerRepository.findById(id).orElse(null);
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "Reseller not found"));

        // code, type, status, dates are immutable here (status via PATCH).
        if (!isBlank(trim(body, "name")))          r.setName(trim(body, "name"));
        if (!isBlank(trim(body, "contactPerson"))) r.setContactPerson(trim(body, "contactPerson"));
        if (!isBlank(trim(body, "contactNumber"))) r.setContactNumber(trim(body, "contactNumber"));
        if (!isBlank(trim(body, "address")))       r.setAddress(trim(body, "address"));
        if (body.containsKey("notes"))              r.setNotes(trim(body, "notes"));
        if (body.containsKey("deliveryDays"))       r.setDeliveryDays(trim(body, "deliveryDays"));
        if (body.containsKey("deliveryTimeWindow")) r.setDeliveryTimeWindow(trim(body, "deliveryTimeWindow"));

        return ResponseEntity.ok(toMap(resellerRepository.save(r)));
    }

    // ── PATCH /api/resellers/{id}/status ──────────────────────────────────

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Reseller r = resellerRepository.findById(id).orElse(null);
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "Reseller not found"));

        String newStatus = upper(trim(body, "status"));
        if (!"ACTIVE".equals(newStatus) && !"INACTIVE".equals(newStatus))
            return ResponseEntity.badRequest().body(Map.of("error", "status must be ACTIVE or INACTIVE"));

        r.setStatus(newStatus);
        Reseller saved = resellerRepository.save(r);

        User admin = userRepository.findById(adminId).orElse(null);
        String adminName = admin != null ? admin.getFullName() : "Unknown";
        activityLogService.log(adminId, adminName, "RESELLER_STATUS_CHANGED",
                "Reseller " + r.getResellerCode() + " status changed to " + newStatus,
                "RESELLER", String.valueOf(id));

        return ResponseEntity.ok(toMap(saved));
    }

    // ── GET /api/resellers/{id}/prices ─────────────────────────────────────

    @GetMapping("/{id}/prices")
    public ResponseEntity<?> getPrices(@PathVariable Long id) {
        if (!resellerRepository.existsById(id))
            return ResponseEntity.status(404).body(Map.of("error", "Reseller not found"));

        List<Map<String, Object>> prices = priceRepository.findByResellerId(id).stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("productId", p.getProductId());
                    m.put("unitPrice", p.getUnitPrice());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(prices);
    }

    // ── PUT /api/resellers/{id}/prices — replace the whole map ──────────────

    @PutMapping("/{id}/prices")
    @Transactional
    public ResponseEntity<?> putPrices(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        if (!resellerRepository.existsById(id))
            return ResponseEntity.status(404).body(Map.of("error", "Reseller not found"));

        Object rawPrices = body.get("prices");
        if (!(rawPrices instanceof List<?> list))
            return ResponseEntity.badRequest().body(Map.of("error", "prices array is required"));

        priceRepository.deleteByResellerId(id);
        List<ResellerProductPrice> toSave = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) continue;
            Object pid   = row.get("productId");
            Object price = row.get("unitPrice");
            if (pid == null || price == null) continue;
            Long productId    = Long.valueOf(pid.toString());
            BigDecimal amount = new BigDecimal(price.toString());
            if (amount.compareTo(BigDecimal.ZERO) < 0) continue;
            toSave.add(new ResellerProductPrice(id, productId, amount));
        }
        priceRepository.saveAll(toSave);

        return ResponseEntity.ok(Map.of("saved", toSave.size()));
    }

    // ── GET /api/resellers/{id}/orders?status= ─────────────────────────────

    @GetMapping("/{id}/orders")
    public ResponseEntity<?> getOrders(
            @PathVariable Long id,
            @RequestParam(value = "status", required = false) String status) {

        Reseller r = resellerRepository.findById(id).orElse(null);
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "Reseller not found"));

        List<Order> orders = orderRepository.findByResellerIdWithItems(id);
        if (status != null && !status.isBlank()) {
            final String fs = status.toUpperCase();
            orders = orders.stream().filter(o -> fs.equals(o.getStatus())).collect(Collectors.toList());
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        int outstandingCount = 0;
        BigDecimal outstandingAmount = BigDecimal.ZERO;

        List<Map<String, Object>> ordersList = new ArrayList<>();
        for (Order o : orders) {
            BigDecimal total = o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO;
            totalAmount = totalAmount.add(total);
            if ("PENDING_COLLECTION".equals(o.getStatus())) {
                outstandingCount++;
                outstandingAmount = outstandingAmount.add(total);
            }

            List<Map<String, Object>> items = new ArrayList<>();
            for (var it : o.getItems()) {
                items.add(Map.of(
                        "productName", it.getProductName() != null ? it.getProductName() : "",
                        "quantity",    it.getQuantity()    != null ? it.getQuantity()    : 0,
                        "unitPrice",   it.getUnitPrice()   != null ? it.getUnitPrice()   : BigDecimal.ZERO));
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId",       o.getId());
            m.put("date",          o.getCreatedAt() != null ? o.getCreatedAt().toLocalDate().toString() : "");
            m.put("status",        o.getStatus()    != null ? o.getStatus()    : "");
            m.put("paymentStatus", o.getPaymentStatus() != null ? o.getPaymentStatus() : "");
            m.put("total",         total);
            m.put("items",         items);
            ordersList.add(m);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOrders",       orders.size());
        summary.put("totalAmount",       totalAmount);
        summary.put("outstandingCount",  outstandingCount);
        summary.put("outstandingAmount", outstandingAmount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", ordersList);
        result.put("summary", summary);
        return ResponseEntity.ok(result);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    private String trim(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? v.toString().trim() : null;
    }

    private String upper(String s) { return s != null ? s.toUpperCase() : null; }

    private boolean isBlank(String s) { return s == null || s.isEmpty(); }

    private Map<String, Object> toMap(Reseller r) {
        List<Object[]> outstandingRows = resellerRepository.outstandingForReseller(r.getId());
        Object[] outstanding = (outstandingRows != null && !outstandingRows.isEmpty()) ? outstandingRows.get(0) : null;
        long outCount = outstanding != null && outstanding[0] != null ? ((Number) outstanding[0]).longValue() : 0L;
        BigDecimal outAmt = outstanding != null && outstanding[1] instanceof BigDecimal
                ? (BigDecimal) outstanding[1] : BigDecimal.ZERO;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                 r.getId());
        m.put("resellerCode",       r.getResellerCode());
        m.put("type",               r.getType());
        m.put("name",               r.getName());
        m.put("contactPerson",      r.getContactPerson());
        m.put("contactNumber",      r.getContactNumber());
        m.put("address",            r.getAddress());
        m.put("notes",              r.getNotes());
        m.put("deliveryDays",       r.getDeliveryDays());
        m.put("deliveryTimeWindow", r.getDeliveryTimeWindow());
        m.put("status",             r.getStatus());
        m.put("registrationDate",   r.getRegistrationDate() != null ? r.getRegistrationDate().toString() : null);
        m.put("totalOrders",        resellerRepository.countOrdersByResellerId(r.getId()));
        m.put("outstandingCount",   outCount);
        m.put("outstandingAmount",  outAmt);
        return m;
    }
}
