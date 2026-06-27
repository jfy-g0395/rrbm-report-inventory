package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import rrbm_backend.dto.DeliveryRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository                productRepository;
    private final ActivityLogService               activityLogService;
    private final MasterKeyService                 masterKeyService;
    private final InventoryService                 inventoryService;
    private final JwtUtil                          jwtUtil;
    private final DeliveryLogRepository            deliveryLogRepository;
    private final DeliveryLogItemRepository        deliveryLogItemRepository;
    private final PayableRepository                payableRepository;
    private final PoItemRepository                 poItemRepository;
    private final PurchaseOrderRepository          purchaseOrderRepository;
    private final ProductSetComponentRepository    productSetComponentRepository;
    private final SupplierProductMappingRepository supplierMappingRepository;
    private final SupplierRepository               supplierRepository;
    private final DeliveryStockService             deliveryStockService;

    public ProductController(ProductRepository productRepository,
                             ActivityLogService activityLogService,
                             MasterKeyService masterKeyService,
                             InventoryService inventoryService,
                             JwtUtil jwtUtil,
                             DeliveryLogRepository deliveryLogRepository,
                             DeliveryLogItemRepository deliveryLogItemRepository,
                             PayableRepository payableRepository,
                             PoItemRepository poItemRepository,
                             PurchaseOrderRepository purchaseOrderRepository,
                             ProductSetComponentRepository productSetComponentRepository,
                             SupplierProductMappingRepository supplierMappingRepository,
                             SupplierRepository supplierRepository,
                             DeliveryStockService deliveryStockService) {
        this.productRepository             = productRepository;
        this.activityLogService            = activityLogService;
        this.masterKeyService              = masterKeyService;
        this.inventoryService              = inventoryService;
        this.jwtUtil                       = jwtUtil;
        this.deliveryLogRepository         = deliveryLogRepository;
        this.deliveryLogItemRepository     = deliveryLogItemRepository;
        this.payableRepository             = payableRepository;
        this.poItemRepository              = poItemRepository;
        this.purchaseOrderRepository       = purchaseOrderRepository;
        this.productSetComponentRepository = productSetComponentRepository;
        this.supplierMappingRepository     = supplierMappingRepository;
        this.supplierRepository            = supplierRepository;
        this.deliveryStockService          = deliveryStockService;
    }

    // GET /api/products — active products for order form dropdown
    @GetMapping
    public List<Product> getAllActiveProducts() {
        List<Product> products = productRepository.findByActiveTrueOrderByNameAsc();
        populateSetComponents(products);
        return products;
    }

    // GET /api/products/search?name=pizza
    @GetMapping("/search")
    public List<Product> searchProducts(@RequestParam String name) {
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(name);
    }

    // GET /api/products/categories
    @GetMapping("/categories")
    public List<String> getCategories() {
        return productRepository.findDistinctCategory();
    }

    // GET /api/products/sub-categories?category=Pizza+Box
    @GetMapping("/sub-categories")
    public List<String> getSubCategories(@RequestParam(required = false) String category) {
        if (category != null && !category.isBlank()) {
            return productRepository.findDistinctSubCategoryByCategory(category);
        }
        return productRepository.findDistinctSubCategory();
    }

    // GET /api/products/all — includes inactive (admin inventory view)
    @GetMapping("/all")
    public List<Product> getAllProducts() {
        List<Product> products = productRepository.findAll();
        populateSetComponents(products);
        return products;
    }

    /**
     * For each set product in the list, fetch its components from product_set_components
     * and attach them as a transient List<Map> on the Product object.
     * Non-set products are left untouched.
     */
    private void populateSetComponents(List<Product> products) {
        // Flag every product that is a component of at least one set (not independently
        // sellable in the order form). Single read-only query for the whole list.
        java.util.Set<Long> componentIds =
                new java.util.HashSet<>(productSetComponentRepository.findAllComponentProductIds());
        for (Product p : products) {
            p.setIsComponent(componentIds.contains(p.getId()));
            if (Boolean.TRUE.equals(p.getIsSet())) {
                List<ProductSetComponent> rows = productSetComponentRepository.findBySetProductId(p.getId());
                List<Map<String, Object>> comps = new ArrayList<>();
                for (ProductSetComponent row : rows) {
                    productRepository.findById(row.getComponentProductId()).ifPresent(comp -> {
                        comps.add(Map.of(
                            "componentProductId",   comp.getId(),
                            "componentProductName", comp.getName(),
                            "quantityPerSet",       row.getQuantityPerSet()
                        ));
                    });
                }
                p.setComponents(comps);
                // Authoritative available-set count (component stock across all warehouses)
                p.setSetAvailableQty(inventoryService.computeSetAvailableQty(p, rows));
            }
        }
    }

    // POST /api/products — create new product (master key required)
    @PostMapping
    public ResponseEntity<?> createProduct(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = (authHeader != null && authHeader.startsWith("Bearer "))
                ? jwtUtil.extractUserId(authHeader.substring(7)) : null;
        String masterKey = (String) body.get("masterKey");
        if (masterKey == null || masterKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Master key is required"));
        }
        if (!masterKeyService.validateMasterKey(masterKey)) {
            return ResponseEntity.status(403).body(Map.of("message", "Invalid master key"));
        }

        String name = body.get("name") != null ? body.get("name").toString().trim() : "";
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Product name is required"));
        }

        Product product = new Product();

        // Product Code — 6-char alphanumeric (new primary UI identifier)
        if (body.get("productCode") != null && !body.get("productCode").toString().isBlank()) {
            String code = body.get("productCode").toString().trim().toUpperCase();
            if (!code.matches("^[A-Za-z0-9]{1,6}$")) {
                return ResponseEntity.badRequest().body(Map.of("message", "Product Code must be 1–6 alphanumeric characters"));
            }
            product.setProductCode(code);
        }
        // Legacy SKU (kept for backward compat but not required from new UI)
        if (body.get("sku") != null && !body.get("sku").toString().isBlank()) {
            product.setSku(body.get("sku").toString().trim());
        }
        product.setName(name);
        if (body.get("category") != null) product.setCategory(body.get("category").toString().trim());
        if (body.get("subCategory") != null && !body.get("subCategory").toString().isBlank())
            product.setSubCategory(body.get("subCategory").toString().trim());
        if (body.get("description") != null && !body.get("description").toString().isBlank())
            product.setDescription(body.get("description").toString().trim());
        if (body.get("itemCode") != null && !body.get("itemCode").toString().isBlank())
            product.setItemCode(body.get("itemCode").toString().trim());
        product.setSellingTag("SELLING"); // always default to SELLING; tag editable from inventory UI
        if (body.get("unitPrice") != null) product.setUnitPrice(new BigDecimal(body.get("unitPrice").toString()));
        if (body.get("unitCost") != null) product.setUnitCost(new BigDecimal(body.get("unitCost").toString()));
        if (body.get("thresholdCritical") != null) product.setThresholdCritical(((Number) body.get("thresholdCritical")).intValue());
        if (body.get("thresholdLow") != null) product.setThresholdLow(((Number) body.get("thresholdLow")).intValue());
        if (body.get("stockWh1") != null) product.setStockWh1(((Number) body.get("stockWh1")).intValue());
        if (body.get("stockWh2") != null) product.setStockWh2(((Number) body.get("stockWh2")).intValue());
        if (body.get("stockWh3") != null) product.setStockWh3(((Number) body.get("stockWh3")).intValue());
        // Set product flag
        if (body.get("isSet") instanceof Boolean) product.setIsSet((Boolean) body.get("isSet"));
        product.setActive(true);

        Product saved = productRepository.save(product);

        // Persist set components if this is a set product
        if (Boolean.TRUE.equals(saved.getIsSet()) && body.get("components") instanceof List) {
            saveSetComponents(saved.getId(), (List<?>) body.get("components"));
            populateSetComponents(List.of(saved));
        }

        String encoderName = body.get("encodedByName") != null ? body.get("encodedByName").toString() : "Admin";
        String codeLabel = saved.getProductCode() != null ? " [" + saved.getProductCode() + "]" : "";
        activityLogService.log(userId, encoderName, "ADD_PRODUCT",
            "Added product: " + saved.getName() + codeLabel + (Boolean.TRUE.equals(saved.getIsSet()) ? " [SET]" : ""),
            "PRODUCT", String.valueOf(saved.getId()));

        return ResponseEntity.ok(saved);
    }

    // PATCH /api/products/{id}/tag — update selling tag (UI label only, no threshold impact)
    @PatchMapping("/{id}/tag")
    public ResponseEntity<?> updateTag(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = (authHeader != null && authHeader.startsWith("Bearer "))
                ? jwtUtil.extractUserId(authHeader.substring(7)) : null;
        String tag = body.get("sellingTag");
        if (tag == null || !List.of("HOT", "SELLING", "SLOW").contains(tag)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid tag. Must be HOT, SELLING, or SLOW"));
        }
        String userName = body.getOrDefault("userName", "Admin"); // #5 — actual admin name from frontend
        return productRepository.findById(id).map(product -> {
            product.setSellingTag(tag);
            productRepository.save(product);
            activityLogService.log(userId, userName, "UPDATE_PRODUCT_TAG",
                "Changed tag for \"" + product.getName() + "\" to " + tag, "PRODUCT", String.valueOf(id));
            return ResponseEntity.ok(Map.of("message", "Tag updated"));
        }).orElse(ResponseEntity.notFound().build());
    }

    // PATCH /api/products/{id} — edit product fields (master key required)
    @Transactional
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateProduct(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = (authHeader != null && authHeader.startsWith("Bearer "))
                ? jwtUtil.extractUserId(authHeader.substring(7)) : null;
        String masterKey = (String) body.get("masterKey");
        if (masterKey == null || masterKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Master key is required"));
        }
        if (!masterKeyService.validateMasterKey(masterKey)) {
            return ResponseEntity.status(403).body(Map.of("message", "Invalid master key"));
        }

        java.util.Optional<Product> opt = productRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Product product = opt.get();

        List<String> changes = new ArrayList<>();
        String encoderName = body.get("encodedByName") != null ? body.get("encodedByName").toString() : "Admin";

        // Name
        if (body.containsKey("name")) {
            String newVal = body.get("name").toString().trim();
            if (!newVal.isEmpty() && !newVal.equals(product.getName())) {
                changes.add("Name: \"" + product.getName() + "\" → \"" + newVal + "\"");
                product.setName(newVal);
            }
        }
        // Product Code
        if (body.containsKey("productCode")) {
            String raw = body.get("productCode") != null ? body.get("productCode").toString().trim().toUpperCase() : "";
            String newCode = raw.isEmpty() ? null : raw;
            if (newCode != null && !newCode.matches("^[A-Za-z0-9]{1,6}$")) {
                return ResponseEntity.badRequest().body(Map.of("message", "Product Code must be 1–6 alphanumeric characters"));
            }
            if (!Objects.equals(newCode, product.getProductCode())) {
                changes.add("Code: \"" + product.getProductCode() + "\" → \"" + newCode + "\"");
                product.setProductCode(newCode);
            }
        }
        // Category
        if (body.containsKey("category")) {
            String newVal = body.get("category").toString().trim();
            if (!newVal.isEmpty() && !newVal.equals(product.getCategory())) {
                changes.add("Category: \"" + product.getCategory() + "\" → \"" + newVal + "\"");
                product.setCategory(newVal);
            }
        }
        // Sub-category
        if (body.containsKey("subCategory")) {
            String newVal = body.get("subCategory") != null ? body.get("subCategory").toString().trim() : "";
            String newSub = newVal.isEmpty() ? null : newVal;
            if (!Objects.equals(newSub, product.getSubCategory())) {
                changes.add("SubCategory: \"" + product.getSubCategory() + "\" → \"" + newSub + "\"");
                product.setSubCategory(newSub);
            }
        }
        // Unit Price
        if (body.containsKey("unitPrice") && body.get("unitPrice") != null) {
            BigDecimal newPrice = new BigDecimal(body.get("unitPrice").toString());
            if (product.getUnitPrice() == null || newPrice.compareTo(product.getUnitPrice()) != 0) {
                changes.add("Price: ₱" + product.getUnitPrice() + " → ₱" + newPrice);
                product.setUnitPrice(newPrice);
            }
        }
        // Unit Cost
        if (body.containsKey("unitCost") && body.get("unitCost") != null) {
            BigDecimal newCost = new BigDecimal(body.get("unitCost").toString());
            if (product.getUnitCost() == null || newCost.compareTo(product.getUnitCost()) != 0) {
                changes.add("Cost: ₱" + product.getUnitCost() + " → ₱" + newCost);
                product.setUnitCost(newCost);
            }
        }
        // Threshold Critical
        if (body.containsKey("thresholdCritical") && body.get("thresholdCritical") != null) {
            int newVal = ((Number) body.get("thresholdCritical")).intValue();
            if (!Objects.equals(newVal, product.getThresholdCritical())) {
                changes.add("ThresholdCritical: " + product.getThresholdCritical() + " → " + newVal);
                product.setThresholdCritical(newVal);
            }
        }
        // Threshold Low
        if (body.containsKey("thresholdLow") && body.get("thresholdLow") != null) {
            int newVal = ((Number) body.get("thresholdLow")).intValue();
            if (!Objects.equals(newVal, product.getThresholdLow())) {
                changes.add("ThresholdLow: " + product.getThresholdLow() + " → " + newVal);
                product.setThresholdLow(newVal);
            }
        }
        // Active / Soft-delete
        if (body.containsKey("active") && body.get("active") != null) {
            Boolean newActive = (Boolean) body.get("active");
            if (!Objects.equals(newActive, product.getActive())) {
                changes.add("Active: " + product.getActive() + " → " + newActive);
                product.setActive(newActive);
            }
        }
        // Capture current stock values before any change so logMovement can write the signed delta
        int prevWh1 = product.getStockWh1() != null ? product.getStockWh1() : 0;
        int prevWh2 = product.getStockWh2() != null ? product.getStockWh2() : 0;
        int prevWh3 = product.getStockWh3() != null ? product.getStockWh3() : 0;

        // Stock WH1
        if (body.containsKey("stockWh1") && body.get("stockWh1") != null) {
            int newVal = ((Number) body.get("stockWh1")).intValue();
            if (!Objects.equals(newVal, product.getStockWh1())) {
                changes.add("StockWH1: " + product.getStockWh1() + " → " + newVal);
                product.setStockWh1(newVal);
            }
        }
        // Stock WH2
        if (body.containsKey("stockWh2") && body.get("stockWh2") != null) {
            int newVal = ((Number) body.get("stockWh2")).intValue();
            if (!Objects.equals(newVal, product.getStockWh2())) {
                changes.add("StockWH2: " + product.getStockWh2() + " → " + newVal);
                product.setStockWh2(newVal);
            }
        }
        // Stock WH3 (Santan)
        if (body.containsKey("stockWh3") && body.get("stockWh3") != null) {
            int newVal = ((Number) body.get("stockWh3")).intValue();
            if (!Objects.equals(newVal, product.getStockWh3())) {
                changes.add("StockSantan: " + product.getStockWh3() + " → " + newVal);
                product.setStockWh3(newVal);
            }
        }
        // Description
        if (body.containsKey("description")) {
            String raw = body.get("description") != null ? body.get("description").toString().trim() : "";
            String newDesc = raw.isEmpty() ? null : raw;
            if (!Objects.equals(newDesc, product.getDescription())) {
                changes.add("Description updated");
                product.setDescription(newDesc);
            }
        }
        // Item Code
        if (body.containsKey("itemCode")) {
            String raw = body.get("itemCode") != null ? body.get("itemCode").toString().trim() : "";
            String newCode = raw.isEmpty() ? null : raw;
            if (!Objects.equals(newCode, product.getItemCode())) {
                changes.add("ItemCode: \"" + product.getItemCode() + "\" → \"" + newCode + "\"");
                product.setItemCode(newCode);
            }
        }
        // isSet flag
        if (body.containsKey("isSet") && body.get("isSet") instanceof Boolean) {
            Boolean newIsSet = (Boolean) body.get("isSet");
            if (!Objects.equals(newIsSet, product.getIsSet())) {
                changes.add("IsSet: " + product.getIsSet() + " → " + newIsSet);
                product.setIsSet(newIsSet);
            }
        }

        productRepository.save(product);

        // Log any stock changes as MANUAL_ADJUST movements — delta is signed (positive = added, negative = removed)
        int newWh1 = product.getStockWh1() != null ? product.getStockWh1() : 0;
        int newWh2 = product.getStockWh2() != null ? product.getStockWh2() : 0;
        int newWh3 = product.getStockWh3() != null ? product.getStockWh3() : 0;
        String adjustReason = "Manual stock adjustment by " + encoderName;
        if (newWh1 != prevWh1)
            inventoryService.logMovement(id, "MANUAL_ADJUST", "wh1", newWh1 - prevWh1,
                String.valueOf(id), adjustReason, null);
        if (newWh2 != prevWh2)
            inventoryService.logMovement(id, "MANUAL_ADJUST", "wh2", newWh2 - prevWh2,
                String.valueOf(id), adjustReason, null);
        if (newWh3 != prevWh3)
            inventoryService.logMovement(id, "MANUAL_ADJUST", "wh3", newWh3 - prevWh3,
                String.valueOf(id), adjustReason, null);

        // Update set components if payload includes them
        if (body.containsKey("components") && body.get("components") instanceof List) {
            productSetComponentRepository.deleteBySetProductId(id);
            if (Boolean.TRUE.equals(product.getIsSet())) {
                saveSetComponents(id, (List<?>) body.get("components"));
                changes.add("Components updated");
            }
            populateSetComponents(List.of(product));
        }

        if (!changes.isEmpty()) {
            String codeLabel = product.getProductCode() != null ? " [" + product.getProductCode() + "]" : "";
            activityLogService.log(userId, encoderName, "EDIT_PRODUCT",
                "Edited: " + product.getName() + codeLabel + " | " + String.join("; ", changes),
                "PRODUCT", String.valueOf(id));
        }

        return ResponseEntity.ok(product);
    }

    // DELETE /api/products/{id} — soft-delete (deactivate) a product (master key required).
    // Soft-delete preserves every historical reference (orders, movements, reports).
    @Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = (authHeader != null && authHeader.startsWith("Bearer "))
                ? jwtUtil.extractUserId(authHeader.substring(7)) : null;
        String masterKey = body != null ? (String) body.get("masterKey") : null;
        if (masterKey == null || masterKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Master key is required"));
        }
        if (!masterKeyService.validateMasterKey(masterKey)) {
            return ResponseEntity.status(403).body(Map.of("message", "Invalid master key"));
        }
        java.util.Optional<Product> opt = productRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Product product = opt.get();
        if (Boolean.FALSE.equals(product.getActive())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Product is already inactive"));
        }
        String encoderName = body != null && body.get("encodedByName") != null
                ? body.get("encodedByName").toString() : "Admin";
        product.setActive(false);
        productRepository.save(product);
        String codeLabel = product.getProductCode() != null ? " [" + product.getProductCode() + "]" : "";
        activityLogService.log(userId, encoderName, "DEACTIVATE_PRODUCT",
            "Deactivated (deleted): " + product.getName() + codeLabel, "PRODUCT", String.valueOf(id));
        return ResponseEntity.ok(Map.of("message", "Product deactivated"));
    }

    /**
     * Persist a list of component rows for a set product.
     * Each element in rawList is expected to be a Map with keys:
     *   componentProductId (Number), quantityPerSet (Number, optional, default 1)
     */
    @SuppressWarnings("unchecked")
    private void saveSetComponents(Long setProductId, List<?> rawList) {
        for (Object entry : rawList) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) entry;
            if (m.get("componentProductId") == null) continue;
            Long compId = ((Number) m.get("componentProductId")).longValue();
            int qty = m.get("quantityPerSet") instanceof Number
                    ? ((Number) m.get("quantityPerSet")).intValue() : 1;

            ProductSetComponent psc = new ProductSetComponent();
            psc.setSetProductId(setProductId);
            psc.setComponentProductId(compId);
            psc.setQuantityPerSet(qty);
            productSetComponentRepository.save(psc);
        }
    }

    // GET /api/products/{productId}/suppliers — all supplier mappings for a product
    @GetMapping("/{productId}/suppliers")
    public ResponseEntity<?> getProductSuppliers(@PathVariable Long productId) {
        if (!productRepository.existsById(productId)) return ResponseEntity.notFound().build();
        List<SupplierProductMapping> mappings = supplierMappingRepository.findByProductId(productId);
        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id",                  m.getId());
            map.put("supplierId",          m.getSupplierId());
            map.put("supplierName",        supplierRepository.findById(m.getSupplierId())
                                                   .map(Supplier::getName).orElse(null));
            map.put("productId",           m.getProductId());
            map.put("supplierItemCode",    m.getSupplierItemCode());
            map.put("supplierDescription", m.getSupplierDescription());
            map.put("unitCost",            m.getUnitCost());
            map.put("isPreferred",         m.getIsPreferred());
            map.put("createdAt",           m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    // POST /api/products/delivery — process delivery receipt and update stock
    @Transactional
    @PostMapping("/delivery")
    public ResponseEntity<?> processDelivery(
            @RequestBody DeliveryRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = (authHeader != null && authHeader.startsWith("Bearer "))
                ? jwtUtil.extractUserId(authHeader.substring(7)) : null;
        if (request.getReceiptNumber() == null || !request.getReceiptNumber().matches("^[A-Za-z0-9\\-]{2,20}$")) {
            return ResponseEntity.badRequest().body(Map.of("message", "DR Number must be 2–20 characters (letters, numbers, hyphens only)"));
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "At least one item is required"));
        }

        // Duplicate receipt check — must run before any stock is touched so a
        // re-submission of an existing DR number is rejected atomically.
        if (deliveryLogRepository.existsByReceiptNumber(request.getReceiptNumber())) {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "DR Number " + request.getReceiptNumber() + " has already been processed"));
        }

        String receiverName = request.getReceiverName() != null && !request.getReceiverName().isBlank()
            ? request.getReceiverName() : "Unknown";

        // Build DeliveryLog record
        DeliveryLog log = new DeliveryLog();
        log.setReceiptNumber(request.getReceiptNumber());
        log.setSupplierName(request.getSupplierName() != null && !request.getSupplierName().isBlank()
            ? request.getSupplierName().trim() : "Unknown");
        log.setReceivedBy(receiverName);
        log.setVerifiedBy(request.getVerifierName());
        log.setEncodedByName(request.getEncodedByName());
        log.setEncodedByUserId(userId);
        log.setNotes(request.getNotes());
        if (request.getPoNumber() != null && !request.getPoNumber().isBlank()) {
            log.setPoNumber(request.getPoNumber().trim());
        }
        if (request.getTruckPlate() != null && !request.getTruckPlate().isBlank()) {
            log.setTruckPlate(request.getTruckPlate().trim());
        }
        if (request.getDriverName() != null && !request.getDriverName().isBlank()) {
            log.setDriverName(request.getDriverName().trim());
        }
        // @PrePersist handles createdAt and reportDate

        int totalQty = 0;
        for (DeliveryRequest.DeliveryItem item : request.getItems()) {
            if (item.getProductId() == null) continue;
            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product == null) continue;

            // Use actual received qty for stock; fall back to quantity for manual (non-PO) deliveries
            int received = (item.getReceived() != null && item.getReceived() > 0)
                    ? item.getReceived()
                    : (item.getQuantity() != null ? item.getQuantity() : 0);
            if (received == 0) continue; // nothing actually received — skip log item
            String wh = item.getWarehouse() != null ? item.getWarehouse().toLowerCase() : "wh1";

            int qty = item.getQuantity() != null ? item.getQuantity() : received;
            int rejected = item.getRejected() != null ? item.getRejected() : 0;
            // Use invoice unit cost from the DR if provided; fall back to stored product cost
            BigDecimal uc = (item.getUnitCost() != null && item.getUnitCost().compareTo(BigDecimal.ZERO) > 0)
                    ? item.getUnitCost()
                    : (product.getUnitCost() != null ? product.getUnitCost() : BigDecimal.ZERO);

            DeliveryLogItem logItem = new DeliveryLogItem();
            logItem.setDeliveryLog(log);
            logItem.setProductId(product.getId());
            logItem.setProductName(product.getName());
            logItem.setQuantity(qty);
            logItem.setReceivedQty(received);
            logItem.setRejectedQty(rejected);
            logItem.setUnitCost(uc);
            logItem.setWarehouse(wh);
            log.getItems().add(logItem);
            totalQty += received;
        }

        log.setTotalItems(log.getItems().size());
        log.setTotalQuantity(totalQty);
        deliveryLogRepository.save(log);

        // Apply stock add, PO auto-match/advance, and create the PENDING payable.
        deliveryStockService.applyEffects(log, userId);

        // #3 fix: actor is the logged-in encoder, not the receiver
        String encodedBy = (request.getEncodedByName() != null && !request.getEncodedByName().isBlank())
            ? request.getEncodedByName() : receiverName;
        String verifiedByInfo = (request.getVerifierName() != null && !request.getVerifierName().isBlank())
            ? request.getVerifierName() : "—";
        String activityDesc = "Receipt #" + request.getReceiptNumber()
            + " | Received by: " + receiverName
            + " | Verified by: " + verifiedByInfo
            + " | " + totalQty + " units across " + log.getItems().size() + " products";
        activityLogService.log(userId, encodedBy, "RECEIVE_STOCK",
            activityDesc, "DELIVERY", request.getReceiptNumber());

        return ResponseEntity.ok(Map.of("message", "Delivery processed successfully", "id", log.getId()));
    }
}
