package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/payables")
public class PayableController {

    private final PayableRepository          payableRepo;
    private final MasterKeyService           masterKeyService;
    private final ActivityLogRepository      activityLogRepo;
    private final JwtUtil                    jwtUtil;
    private final DeliveryLogItemRepository  deliveryLogItemRepo;
    private final UserRepository             userRepository;

    public PayableController(PayableRepository payableRepo,
                             MasterKeyService masterKeyService,
                             ActivityLogRepository activityLogRepo,
                             JwtUtil jwtUtil,
                             DeliveryLogItemRepository deliveryLogItemRepo,
                             UserRepository userRepository) {
        this.payableRepo          = payableRepo;
        this.masterKeyService     = masterKeyService;
        this.activityLogRepo      = activityLogRepo;
        this.jwtUtil              = jwtUtil;
        this.deliveryLogItemRepo  = deliveryLogItemRepo;
        this.userRepository       = userRepository;
    }

    // GET /api/payables — list all, newest first
    @GetMapping
    public List<Payable> getAll() {
        return payableRepo.findAllByOrderByCreatedAtDesc();
    }

    // GET /api/payables/{id} — single payable with delivery line items
    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id) {
        return payableRepo.findById(id).map(p -> {
            List<DeliveryLogItem> items = deliveryLogItemRepo.findByDeliveryLogId(p.getDeliveryLogId());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id",            p.getId());
            result.put("deliveryLogId", p.getDeliveryLogId());
            result.put("receiptNumber", p.getReceiptNumber());
            result.put("supplierName",  p.getSupplierName());
            result.put("totalAmount",   p.getTotalAmount());
            result.put("amountPaid",    p.getAmountPaid());
            result.put("status",        p.getStatus());
            result.put("notes",         p.getNotes());
            result.put("paidAt",        p.getPaidAt() != null ? p.getPaidAt().toString() : null);
            result.put("paidBy",        p.getPaidBy());
            result.put("createdAt",     p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
            result.put("createdBy",     p.getCreatedBy());
            result.put("items", items.stream().map(i -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productName", i.getProductName());
                row.put("quantity",    i.getQuantity());
                row.put("receivedQty", i.getReceivedQty());
                row.put("rejectedQty", i.getRejectedQty());
                row.put("unitCost",    i.getUnitCost());
                BigDecimal lineTotal = (i.getUnitCost() != null)
                    ? i.getUnitCost().multiply(BigDecimal.valueOf(
                        i.getReceivedQty() > 0 ? i.getReceivedQty() : i.getQuantity()))
                    : BigDecimal.ZERO;
                row.put("lineTotal", lineTotal);
                return row;
            }).collect(Collectors.toList()));

            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    // GET /api/payables/summary — total outstanding + pending count
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary() {
        BigDecimal outstanding = payableRepo.getTotalOutstanding();
        long pendingCount = payableRepo.findByStatus("PENDING").size();
        return ResponseEntity.ok(Map.of(
            "totalOutstanding", outstanding,
            "pendingCount",     pendingCount
        ));
    }

    // PATCH /api/payables/{id}/status — mark PAID or revert to PENDING
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // M-30: Role gate — only SUPER_ADMIN or ADMINISTRATOR may change payable status.
        // Previously any valid JWT was accepted (no role check).
        Long callerId = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try { callerId = jwtUtil.extractUserId(authHeader.substring(7)); } catch (Exception ignored) {}
        }
        if (callerId == null)
            return ResponseEntity.status(401).body(Map.of("message", "Authentication required"));
        User caller = userRepository.findById(callerId).orElse(null);
        if (caller == null
                || (!"SUPER_ADMIN".equals(caller.getRole()) && !"ADMINISTRATOR".equals(caller.getRole())))
            return ResponseEntity.status(403).body(Map.of("message", "Only administrators can update payable status"));

        String newStatus = body.getOrDefault("status", "").toUpperCase();
        if (!newStatus.equals("PAID") && !newStatus.equals("PENDING")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Status must be PAID or PENDING"));
        }

        return payableRepo.findById(id).map(p -> {
            String oldStatus = p.getStatus();
            p.setStatus(newStatus);

            if (newStatus.equals("PAID")) {
                p.setAmountPaid(p.getTotalAmount());
                p.setPaidAt(LocalDateTime.now());
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    try {
                        String actor = jwtUtil.extractUsername(authHeader.replace("Bearer ", ""));
                        p.setPaidBy(actor);
                    } catch (Exception ignored) {}
                }
            } else {
                p.setAmountPaid(BigDecimal.ZERO);
                p.setPaidAt(null);
                p.setPaidBy(null);
            }
            payableRepo.save(p);

            // Activity log
            ActivityLog log = new ActivityLog();
            log.setAction("PAYABLE_STATUS_CHANGED");
            log.setDescription("Payable #" + id + " (" + p.getReceiptNumber() + "): " + oldStatus + " → " + newStatus);
            log.setEntityType("PAYABLE");
            log.setEntityId(String.valueOf(id));
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                try {
                    String token = authHeader.replace("Bearer ", "");
                    log.setUserId(jwtUtil.extractUserId(token));
                    log.setUserName(jwtUtil.extractUsername(token));
                } catch (Exception ignored) {}
            }
            activityLogRepo.save(log);

            return ResponseEntity.ok(p);
        }).orElse(ResponseEntity.notFound().build());
    }

    // DELETE /api/payables/{id} — master key required
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePayable(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String masterKey = body.getOrDefault("masterKey", "");
        if (!masterKeyService.validateMasterKey(masterKey)) {
            return ResponseEntity.status(403).body(Map.of("message", "Invalid master key"));
        }

        return payableRepo.findById(id).map(p -> {
            payableRepo.delete(p);

            ActivityLog log = new ActivityLog();
            log.setAction("DELETE_PAYABLE");
            log.setDescription("Deleted payable #" + id + " (Receipt: " + p.getReceiptNumber() + ", Amount: ₱" + p.getTotalAmount() + ")");
            log.setEntityType("PAYABLE");
            log.setEntityId(String.valueOf(id));
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                try {
                    String token = authHeader.replace("Bearer ", "");
                    log.setUserId(jwtUtil.extractUserId(token));
                    log.setUserName(jwtUtil.extractUsername(token));
                } catch (Exception ignored) {}
            }
            activityLogRepo.save(log);

            return ResponseEntity.ok(Map.of("message", "Payable deleted"));
        }).orElse(ResponseEntity.notFound().build());
    }
}
