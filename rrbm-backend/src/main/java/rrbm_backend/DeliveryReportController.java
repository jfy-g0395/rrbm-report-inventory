package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/delivery-reports")
public class DeliveryReportController {

    private final DeliveryLogRepository    repo;
    private final ProductRepository        productRepository;
    private final MasterKeyService         masterKeyService;
    private final PurchaseOrderRepository  purchaseOrderRepository;
    private final PoItemRepository         poItemRepository;
    private final PayableRepository        payableRepository;
    private final JwtUtil                  jwtUtil;
    private final UserRepository           userRepository;
    private final ActivityLogService       activityLogService;
    private final DeliveryStockService     deliveryStockService;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwordEncoder
            = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    public DeliveryReportController(DeliveryLogRepository repo,
                                    ProductRepository productRepository,
                                    MasterKeyService masterKeyService,
                                    PurchaseOrderRepository purchaseOrderRepository,
                                    PoItemRepository poItemRepository,
                                    PayableRepository payableRepository,
                                    JwtUtil jwtUtil,
                                    UserRepository userRepository,
                                    ActivityLogService activityLogService,
                                    DeliveryStockService deliveryStockService) {
        this.repo                    = repo;
        this.productRepository       = productRepository;
        this.masterKeyService        = masterKeyService;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.poItemRepository        = poItemRepository;
        this.payableRepository       = payableRepository;
        this.jwtUtil                 = jwtUtil;
        this.userRepository          = userRepository;
        this.activityLogService      = activityLogService;
        this.deliveryStockService    = deliveryStockService;
    }

    @GetMapping
    public List<DeliveryLog> getAll() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return repo.findById(id)
                .map(r -> ResponseEntity.ok((Object) r))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Edit a delivery report (admin-security-key gated, reason required) ─────
    // Editable: truck plate, driver, received-by, verified-by, notes — and, when an
    // `items` array is supplied, the delivered line items (quantity + product). Editing
    // items does a full re-sync: inventory stock, PO fulfillment, and the supplier payable
    // are reversed for the old items and re-applied for the new ones. Every edit requires
    // a non-empty `reason` and is appended to the report's change log (who/what/when/reason).
    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> editDeliveryReport(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // ── 1. Resolve caller ──────────────────────────────────────────────
        Long userId = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try { userId = jwtUtil.extractUserId(authHeader.substring(7)); } catch (Exception ignored) {}
        }
        if (userId == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));

        // ── 2. Validate admin security key (same BCrypt check as AuthController) ──
        String providedKey = body.get("securityKey") != null ? body.get("securityKey").toString().trim() : "";
        if (caller.getAdminSecurityKey() == null) {
            return ResponseEntity.status(403).body(Map.of(
                "message", "No admin security key has been set for your account. Ask your Super Admin to assign one."));
        }
        if (providedKey.isEmpty() || !passwordEncoder.matches(providedKey, caller.getAdminSecurityKey())) {
            return ResponseEntity.status(403).body(Map.of("message", "Incorrect admin security key"));
        }

        // ── 3. Mandatory change reason ──────────────────────────────────────
        String reason = body.get("reason") != null ? body.get("reason").toString().trim() : "";
        if (reason.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "A reason is required to save changes"));
        }

        // ── 4. Load record ─────────────────────────────────────────────────
        DeliveryLog log = repo.findById(id).orElse(null);
        if (log == null) return ResponseEntity.notFound().build();

        boolean editItems = body.containsKey("items") && body.get("items") instanceof List;

        // ── 5. Item-edit guards ─────────────────────────────────────────────
        if (editItems) {
            if ("CANCELLED".equals(log.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of(
                    "message", "Cannot edit items on a cancelled delivery"));
            }
            if (deliveryStockService.hasSettledPayable(log)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "message", "Cannot edit items — the supplier payable for this delivery is already paid"));
            }
        }

        List<String> changes = new ArrayList<>();

        // ── 6. Metadata changes (track diffs for the change log) ────────────
        if (body.containsKey("truckPlate"))  trackMeta(changes, "Truck plate", log.getTruckPlate(), blankToNull(body.get("truckPlate")), log::setTruckPlate);
        if (body.containsKey("driverName"))  trackMeta(changes, "Driver",      log.getDriverName(), blankToNull(body.get("driverName")), log::setDriverName);
        if (body.containsKey("verifiedBy"))  trackMeta(changes, "Verified by", log.getVerifiedBy(), blankToNull(body.get("verifiedBy")), log::setVerifiedBy);
        if (body.containsKey("notes"))       trackMeta(changes, "Notes",       log.getNotes(),      blankToNull(body.get("notes")),      log::setNotes);
        // received_by is NOT NULL in the schema — keep the existing value if blank is sent
        if (body.containsKey("receivedBy")) {
            String rb = blankToNull(body.get("receivedBy"));
            if (rb != null) trackMeta(changes, "Received by", log.getReceivedBy(), rb, log::setReceivedBy);
        }

        // ── 7. Item edits — full re-sync (stock + PO + payable) ─────────────
        if (editItems) {
            // Parse + validate the new items first so we fail before touching anything.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawItems = (List<Map<String, Object>>) body.get("items");
            if (rawItems.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "A delivery must have at least one item"));
            }
            List<DeliveryLogItem> newItems = new ArrayList<>();
            for (Map<String, Object> ri : rawItems) {
                Long productId = asLong(ri.get("productId"));
                if (productId == null) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Each item must reference a product"));
                }
                Product product = productRepository.findById(productId).orElse(null);
                if (product == null) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "message", "Unknown product (not in inventory): id " + productId));
                }
                int received = asInt(ri.get("receivedQty"), -1);
                if (received < 0) received = asInt(ri.get("quantity"), 0);
                int quantity = asInt(ri.get("quantity"), received);
                int rejected = asInt(ri.get("rejectedQty"), 0);
                if (received <= 0) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "message", "Quantity must be greater than 0 for " + product.getName()));
                }
                String wh = ri.get("warehouse") != null ? ri.get("warehouse").toString().toLowerCase() : "wh1";
                if (!wh.equals("wh1") && !wh.equals("wh2") && !wh.equals("wh3")) wh = "wh1";
                BigDecimal uc = asBigDecimal(ri.get("unitCost"));
                if (uc == null || uc.compareTo(BigDecimal.ZERO) <= 0) {
                    uc = product.getUnitCost() != null ? product.getUnitCost() : BigDecimal.ZERO;
                }

                DeliveryLogItem li = new DeliveryLogItem();
                li.setDeliveryLog(log);
                li.setProductId(product.getId());
                li.setProductName(product.getName());
                li.setQuantity(quantity);
                li.setReceivedQty(received);
                li.setRejectedQty(rejected);
                li.setUnitCost(uc);
                li.setWarehouse(wh);
                newItems.add(li);
            }

            // Re-sync only when the stock/PO/payable-affecting fields change
            // (product, received qty, warehouse, or unit cost) — keyed by productId so two
            // same-named products are never conflated. A metadata-only save (which still
            // submits the unchanged items) leaves stock and the payable untouched.
            String oldSig = itemsSignature(log.getItems());
            String newSig = itemsSignature(newItems);

            if (!oldSig.equals(newSig)) {
                // Detached copies of the current lines so we can compute the per-line delta
                // after the persisted item set is swapped.
                List<DeliveryLogItem> oldSnapshot = new ArrayList<>();
                for (DeliveryLogItem it : log.getItems()) {
                    DeliveryLogItem c = new DeliveryLogItem();
                    c.setProductId(it.getProductId());
                    c.setProductName(it.getProductName());
                    c.setQuantity(it.getQuantity());
                    c.setReceivedQty(it.getReceivedQty());
                    c.setUnitCost(it.getUnitCost());
                    c.setWarehouse(it.getWarehouse());
                    oldSnapshot.add(c);
                }

                // Swap the persisted item set, then apply ONLY the net change per line.
                // Unchanged lines are never touched — this replaces the old reverse-everything /
                // re-apply-everything path that corrupted sold-down siblings (DR 208390 incident).
                log.getItems().clear();
                log.getItems().addAll(newItems);
                int totalReceived = newItems.stream().mapToInt(DeliveryLogItem::getReceivedQty).sum();
                log.setTotalItems(newItems.size());
                log.setTotalQuantity(totalReceived);
                repo.save(log);

                deliveryStockService.applyItemEdit(log, oldSnapshot, newItems, userId);
                appendItemChanges(changes, oldSnapshot, newItems);
            }
        }

        // ── 8. Append change-log entry (who / what / when / reason) ─────────
        String summary = changes.isEmpty() ? "No field changes" : String.join("; ", changes);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String entry = "[" + stamp + "] " + caller.getFullName() + " — " + summary + ". Reason: " + reason;
        String existing = log.getChangeLog();
        log.setChangeLog(existing != null && !existing.isBlank() ? existing + "\n" + entry : entry);

        repo.save(log);
        activityLogService.log(userId, caller.getFullName(), "EDIT_DELIVERY_REPORT",
            "Edited delivery report " + log.getReceiptNumber() + " — " + summary + ". Reason: " + reason,
            "DELIVERY_LOG", String.valueOf(id));
        return ResponseEntity.ok(log);
    }

    /** Apply a metadata change via the setter and record a human-readable diff when it actually changes. */
    private void trackMeta(List<String> changes, String label, String oldVal, String newVal,
                           java.util.function.Consumer<String> setter) {
        String o = oldVal == null ? "" : oldVal;
        String n = newVal == null ? "" : newVal;
        if (!o.equals(n)) {
            changes.add(label + " \"" + o + "\"→\"" + n + "\"");
        }
        setter.accept(newVal);
    }

    /** Order-independent signature of the stock/PO/payable-affecting fields, keyed by productId. */
    private String itemsSignature(List<DeliveryLogItem> items) {
        return items.stream()
                .map(i -> i.getProductId() + ":" + (i.getReceivedQty() > 0 ? i.getReceivedQty() : i.getQuantity())
                        + ":" + (i.getWarehouse() == null ? "wh1" : i.getWarehouse())
                        + ":" + (i.getUnitCost() == null ? "0" : i.getUnitCost().stripTrailingZeros().toPlainString()))
                .sorted()
                .collect(Collectors.joining("|"));
    }

    /**
     * Append a concise, human-readable per-line diff to the change log — only the lines that
     * actually changed (added / removed / qty changed / cost changed), keyed by (productId,
     * warehouse). Replaces the old "[full old list] → [full new list]" dump.
     */
    private void appendItemChanges(List<String> changes,
                                   List<DeliveryLogItem> oldItems, List<DeliveryLogItem> newItems) {
        Map<String, int[]>        qty  = new LinkedHashMap<>();   // key -> [oldQty, newQty]
        Map<String, BigDecimal[]> cost = new HashMap<>();         // key -> [oldCost, newCost]
        Map<String, String>       name = new LinkedHashMap<>();
        Map<String, String>       wh   = new HashMap<>();
        for (DeliveryLogItem it : oldItems) {
            String w = it.getWarehouse() == null ? "wh1" : it.getWarehouse();
            String k = it.getProductId() + "|" + w;
            qty.computeIfAbsent(k, x -> new int[2])[0] += rcv(it);
            cost.computeIfAbsent(k, x -> new BigDecimal[2])[0] = it.getUnitCost();
            name.putIfAbsent(k, it.getProductName());
            wh.put(k, w);
        }
        for (DeliveryLogItem it : newItems) {
            String w = it.getWarehouse() == null ? "wh1" : it.getWarehouse();
            String k = it.getProductId() + "|" + w;
            qty.computeIfAbsent(k, x -> new int[2])[1] += rcv(it);
            cost.computeIfAbsent(k, x -> new BigDecimal[2])[1] = it.getUnitCost();
            name.put(k, it.getProductName());
            wh.put(k, w);
        }
        for (String k : qty.keySet()) {
            int o = qty.get(k)[0], n = qty.get(k)[1];
            String nm = name.get(k) == null ? "item" : name.get(k);
            String tag = "wh1".equals(wh.get(k)) ? "" : " @" + wh.get(k);
            if (o > 0 && n == 0) {
                changes.add("Removed " + nm + " (" + o + tag + ")");
            } else if (o == 0 && n > 0) {
                changes.add("Added " + nm + " (" + n + tag + ")");
            } else if (o != n) {
                changes.add(nm + " qty " + o + " → " + n + tag);
            } else {
                BigDecimal[] c = cost.get(k);
                if (c != null && c[0] != null && c[1] != null && c[0].compareTo(c[1]) != 0) {
                    changes.add(nm + " cost ₱" + c[0].stripTrailingZeros().toPlainString()
                            + " → ₱" + c[1].stripTrailingZeros().toPlainString() + tag);
                }
            }
        }
    }

    private int rcv(DeliveryLogItem it) {
        return it.getReceivedQty() > 0 ? it.getReceivedQty() : it.getQuantity();
    }

    private String blankToNull(Object val) {
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString().trim()); } catch (Exception e) { return null; }
    }

    private int asInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return def; }
    }

    private BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString().trim()); } catch (Exception e) { return null; }
    }

    @PatchMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<?> cancelDelivery(@PathVariable Long id,
                                            @RequestBody Map<String, String> body) {

        // ── 1. Master key validation ───────────────────────────────────────
        String masterKey = body != null ? body.get("masterKey") : null;
        if (masterKey == null || masterKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Master key is required to cancel a delivery"));
        }
        if (!masterKeyService.validateMasterKey(masterKey)) {
            return ResponseEntity.status(403)
                    .body(Map.of("message", "Invalid master key"));
        }

        // ── 2. Load delivery log ───────────────────────────────────────────
        DeliveryLog log = repo.findById(id).orElse(null);
        if (log == null) return ResponseEntity.notFound().build();
        if ("CANCELLED".equals(log.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Delivery is already cancelled"));

        // ── 3. Reverse all delivery effects (stock, PO items, payable) ─────
        deliveryStockService.reverseEffects(log, null);

        // ── 4. Mark delivery as cancelled ─────────────────────────────────
        log.setStatus("CANCELLED");
        repo.save(log);
        return ResponseEntity.ok(Map.of("message", "Delivery cancelled — stock, PO items, and payable reversed"));
    }

}
