package rrbm_backend;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private final SupplierRepository               supplierRepository;
    private final SupplierProductMappingRepository mappingRepository;
    private final ProductRepository                productRepository;
    private final ActivityLogService               activityLogService;
    private final JwtUtil                          jwtUtil;
    private final UserRepository                   userRepository;

    public SupplierController(SupplierRepository supplierRepository,
                              SupplierProductMappingRepository mappingRepository,
                              ProductRepository productRepository,
                              ActivityLogService activityLogService,
                              JwtUtil jwtUtil,
                              UserRepository userRepository) {
        this.supplierRepository = supplierRepository;
        this.mappingRepository  = mappingRepository;
        this.productRepository  = productRepository;
        this.activityLogService = activityLogService;
        this.jwtUtil            = jwtUtil;
        this.userRepository     = userRepository;
    }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    private String actorName(Long userId) {
        if (userId != null) {
            return userRepository.findById(userId)
                    .map(User::getFullName)
                    .orElse("Unknown");
        }
        return "Unknown";
    }

    // ── GET /api/suppliers?includeInactive=false ──────────────────────────────
    @GetMapping
    public List<Map<String, Object>> getAll(
            @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive) {
        List<Supplier> list = includeInactive
                ? supplierRepository.findAllByOrderByNameAsc()
                : supplierRepository.findByIsActiveTrueOrderByNameAsc();
        return list.stream().map(this::toMap).toList();
    }

    // ── GET /api/suppliers/{id} ───────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id) {
        return supplierRepository.findById(id)
                .map(s -> ResponseEntity.ok((Object) toMap(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── POST /api/suppliers ───────────────────────────────────────────────────
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String name = body.containsKey("name") ? body.get("name").toString().trim() : "";
        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Supplier name is required"));
        }

        Long userId = userIdFromHeader(authHeader);
        String actor = actorName(userId);

        Supplier s = new Supplier();
        s.setName(name);
        s.setAddress(strOrNull(body.get("address")));
        s.setContactNumber(strOrNull(body.get("contactNumber")));
        s.setContactPerson(strOrNull(body.get("contactPerson")));
        s.setPaymentTerms(strOrNull(body.get("paymentTerms")));
        s.setNotes(strOrNull(body.get("notes")));
        s.setIsActive(true);

        Supplier saved = supplierRepository.save(s);
        activityLogService.log(userId, actor, "CREATE_SUPPLIER",
                "Created supplier: " + saved.getName(),
                "SUPPLIER", String.valueOf(saved.getId()));

        return ResponseEntity.ok(toMap(saved));
    }

    // ── PATCH /api/suppliers/{id} ─────────────────────────────────────────────
    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Supplier s = supplierRepository.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();

        Long userId = userIdFromHeader(authHeader);
        String actor = actorName(userId);

        if (body.containsKey("name")) {
            String name = body.get("name").toString().trim();
            if (!name.isBlank()) s.setName(name);
        }
        if (body.containsKey("address"))       s.setAddress(strOrNull(body.get("address")));
        if (body.containsKey("contactNumber")) s.setContactNumber(strOrNull(body.get("contactNumber")));
        if (body.containsKey("contactPerson")) s.setContactPerson(strOrNull(body.get("contactPerson")));
        if (body.containsKey("paymentTerms"))  s.setPaymentTerms(strOrNull(body.get("paymentTerms")));
        if (body.containsKey("notes"))         s.setNotes(strOrNull(body.get("notes")));

        supplierRepository.save(s);
        activityLogService.log(userId, actor, "UPDATE_SUPPLIER",
                "Updated supplier: " + s.getName(),
                "SUPPLIER", String.valueOf(id));

        return ResponseEntity.ok(toMap(s));
    }

    // ── DELETE /api/suppliers/{id} — soft delete ──────────────────────────────
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> softDelete(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Supplier s = supplierRepository.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        if (!s.getIsActive()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Supplier is already inactive"));
        }

        Long userId = userIdFromHeader(authHeader);
        String actor = actorName(userId);

        s.setIsActive(false);
        supplierRepository.save(s);
        activityLogService.log(userId, actor, "DEACTIVATE_SUPPLIER",
                "Deactivated supplier: " + s.getName(),
                "SUPPLIER", String.valueOf(id));

        return ResponseEntity.ok(Map.of(
                "message",  "Supplier deactivated successfully",
                "id",       id,
                "isActive", false));
    }

    // ── GET /api/suppliers/{supplierId}/mappings ──────────────────────────────
    @GetMapping("/{supplierId}/mappings")
    public ResponseEntity<?> getMappings(@PathVariable Long supplierId) {
        if (!supplierRepository.existsById(supplierId)) return ResponseEntity.notFound().build();
        List<SupplierProductMapping> mappings = mappingRepository.findBySupplierId(supplierId);
        return ResponseEntity.ok(mappings.stream().map(this::toMappingMap).toList());
    }

    // ── POST /api/suppliers/{supplierId}/mappings ─────────────────────────────
    @PostMapping("/{supplierId}/mappings")
    @Transactional
    public ResponseEntity<?> addMapping(
            @PathVariable Long supplierId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Supplier supplier = supplierRepository.findById(supplierId).orElse(null);
        if (supplier == null) return ResponseEntity.notFound().build();

        if (!body.containsKey("productId") || body.get("productId") == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "productId is required"));
        }
        Long productId = ((Number) body.get("productId")).longValue();
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Product not found: " + productId));
        }

        boolean preferred = Boolean.TRUE.equals(body.get("isPreferred"));

        // Option A: clear preferred flag on all existing mappings for this product
        if (preferred) {
            clearPreferredForProduct(productId);
        }

        SupplierProductMapping m = new SupplierProductMapping();
        m.setSupplierId(supplierId);
        m.setProductId(productId);
        m.setSupplierItemCode(strOrNull(body.get("supplierItemCode")));
        m.setSupplierDescription(strOrNull(body.get("supplierDescription")));
        if (body.get("unitCost") != null) {
            m.setUnitCost(new BigDecimal(body.get("unitCost").toString()));
        }
        m.setIsPreferred(preferred);

        try {
            SupplierProductMapping saved = mappingRepository.save(m);
            Long userId = userIdFromHeader(authHeader);
            activityLogService.log(userId, actorName(userId), "CREATE_SUPPLIER_MAPPING",
                    "Mapped product '" + product.getName() + "' to supplier '" + supplier.getName() + "'",
                    "SUPPLIER_MAPPING", String.valueOf(saved.getId()));
            return ResponseEntity.ok(toMappingMap(saved));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "A mapping already exists for this supplier and product"));
        }
    }

    // ── PATCH /api/suppliers/{supplierId}/mappings/{mappingId} ────────────────
    @PatchMapping("/{supplierId}/mappings/{mappingId}")
    @Transactional
    public ResponseEntity<?> updateMapping(
            @PathVariable Long supplierId,
            @PathVariable Long mappingId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        SupplierProductMapping m = mappingRepository.findById(mappingId).orElse(null);
        if (m == null || !m.getSupplierId().equals(supplierId))
            return ResponseEntity.notFound().build();

        if (body.containsKey("supplierItemCode"))
            m.setSupplierItemCode(strOrNull(body.get("supplierItemCode")));
        if (body.containsKey("supplierDescription"))
            m.setSupplierDescription(strOrNull(body.get("supplierDescription")));
        if (body.containsKey("unitCost")) {
            m.setUnitCost(body.get("unitCost") != null
                    ? new BigDecimal(body.get("unitCost").toString()) : null);
        }
        if (body.containsKey("isPreferred")) {
            boolean preferred = Boolean.TRUE.equals(body.get("isPreferred"));
            // Option A: if flipping to preferred, clear all other preferred mappings for this product
            if (preferred && !Boolean.TRUE.equals(m.getIsPreferred())) {
                clearPreferredForProduct(m.getProductId());
            }
            m.setIsPreferred(preferred);
        }

        mappingRepository.save(m);
        Long userId = userIdFromHeader(authHeader);
        activityLogService.log(userId, actorName(userId), "UPDATE_SUPPLIER_MAPPING",
                "Updated supplier mapping id=" + mappingId,
                "SUPPLIER_MAPPING", String.valueOf(mappingId));
        return ResponseEntity.ok(toMappingMap(m));
    }

    // ── DELETE /api/suppliers/{supplierId}/mappings/{mappingId} ───────────────
    @DeleteMapping("/{supplierId}/mappings/{mappingId}")
    @Transactional
    public ResponseEntity<?> deleteMapping(
            @PathVariable Long supplierId,
            @PathVariable Long mappingId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        SupplierProductMapping m = mappingRepository.findById(mappingId).orElse(null);
        if (m == null || !m.getSupplierId().equals(supplierId))
            return ResponseEntity.notFound().build();

        mappingRepository.delete(m);
        Long userId = userIdFromHeader(authHeader);
        activityLogService.log(userId, actorName(userId), "DELETE_SUPPLIER_MAPPING",
                "Deleted supplier mapping id=" + mappingId,
                "SUPPLIER_MAPPING", String.valueOf(mappingId));
        return ResponseEntity.ok(Map.of("message", "Mapping deleted successfully", "id", mappingId));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Option A: clear is_preferred on all current mappings for a product
     *  before setting a new preferred mapping. */
    private void clearPreferredForProduct(Long productId) {
        mappingRepository.findByProductIdAndIsPreferredTrue(productId).forEach(existing -> {
            existing.setIsPreferred(false);
            mappingRepository.save(existing);
        });
    }

    /** Serialise a supplier to a response map. */
    private Map<String, Object> toMap(Supplier s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            s.getId());
        m.put("name",          s.getName());
        m.put("address",       s.getAddress());
        m.put("contactNumber", s.getContactNumber());
        m.put("contactPerson", s.getContactPerson());
        m.put("paymentTerms",  s.getPaymentTerms());
        m.put("notes",         s.getNotes());
        m.put("isActive",      s.getIsActive());
        m.put("createdAt",     s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        return m;
    }

    /** Serialise a mapping to a response map, resolving productName on the fly. */
    private Map<String, Object> toMappingMap(SupplierProductMapping m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",                  m.getId());
        map.put("supplierId",          m.getSupplierId());
        map.put("productId",           m.getProductId());
        map.put("productName",         productRepository.findById(m.getProductId())
                                              .map(Product::getName).orElse(null));
        map.put("supplierItemCode",    m.getSupplierItemCode());
        map.put("supplierDescription", m.getSupplierDescription());
        map.put("unitCost",            m.getUnitCost());
        map.put("isPreferred",         m.getIsPreferred());
        map.put("createdAt",           m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
        return map;
    }

    private String strOrNull(Object val) {
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
