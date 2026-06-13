package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S9 — Products & Inventory Edits
 *
 * <p>All write operations funnel through two endpoints:
 * <ul>
 *   <li>POST  /api/products            — create (master key in body, returns 200)
 *   <li>PATCH /api/products/{id}       — edit fields / stock adjustment / set-components
 *                                        (master key in body; stock delta auto-logged as MANUAL_ADJUST)
 *   <li>PATCH /api/products/{id}/tag   — update sellingTag (JWT only, no master key)
 * </ul>
 * There are no separate /adjust-stock or /set-components endpoints — both operations
 * are handled by the standard PATCH /{id} with the appropriate fields in the payload.
 *
 * <p>Uses the live {@code rrbm2024} master key already active in the dev DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ProductInventoryIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private ProductSetComponentRepository productSetComponentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String MASTER_KEY = "rrbm2024";
    private static final ObjectMapper mapper = new ObjectMapper();

    private User testUser;
    private String userJwt;
    private Long readProdId;         // @BeforeAll product for read-test assertions
    private Long softDeletedProdId;  // set in t12, checked in t21/t22

    @BeforeAll
    void seed() {
        testUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s9-" + RUN + "@test.rrbm.internal", "S9 Accounting " + RUN,
                "S9-Secret!", "S9-admin-key");
        userJwt = ITSupport.jwtFor(jwtUtil, testUser);

        // Pre-seeded product for read tests — category/subCategory added after ITSupport creates it
        Product readProd = ITSupport.seedProduct(productRepository,
                "S9R" + (RUN % 999), "S9 Read Product " + RUN,
                new BigDecimal("1200.00"), 200);
        readProd.setCategory("Pizza Box");
        readProd.setSubCategory("Plain");
        productRepository.save(readProd);
        readProdId = readProd.getId();
    }

    @AfterAll
    void clean() {
        // FK-safe order: movements / activity log before products; orders before products (safety)
        inventoryMovementRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productSetComponentRepository.deleteAll();
        productRepository.deleteAll();
        activityLogRepository.deleteAll();
        userRepository.deleteById(testUser.getId()); // only our test user — real users reference daily_reports
    }

    // ==================== GROUP 1: POST /api/products — Create ====================

    @Test
    void t01_createProduct_validPayload_returns200AndPersists() throws Exception {
        String code = "S9C" + (RUN % 999);

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("productCode", code);
        body.put("name", "S9 Create Product " + RUN);
        body.put("category", "Pizza Box");
        body.put("unitPrice", 1500.00);
        body.put("unitCost", 900.00);
        body.put("stockWh1", 100);
        body.put("stockWh2", 50);
        body.put("stockWh3", 25);
        body.put("encodedByName", "S9 Tester");

        MvcResult result = mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        Long prodId = resp.get("id").asLong();

        Product saved = productRepository.findById(prodId).orElse(null);
        assertNotNull(saved, "product should be persisted");
        assertEquals(code, saved.getProductCode());
        assertEquals("S9 Create Product " + RUN, saved.getName());
        assertEquals(new BigDecimal("1500.00"), saved.getUnitPrice());
        assertEquals(100, saved.getStockWh1());
        assertTrue(saved.getActive());
        assertEquals("SELLING", saved.getSellingTag(), "sellingTag always defaults to SELLING on create");

        List<ActivityLog> logs = activityLogRepository
                .findByUserIdAndReportDateOrderByCreatedAtDesc(testUser.getId(), LocalDate.now());
        assertTrue(logs.stream().anyMatch(l -> "ADD_PRODUCT".equals(l.getAction())),
                "ADD_PRODUCT activity log entry expected");
    }

    @Test
    void t02_createProduct_missingMasterKey_returns400NoWrite() throws Exception {
        long before = productRepository.count();

        ObjectNode body = mapper.createObjectNode();
        body.put("productCode", "S9T2" + (RUN % 99));
        body.put("name", "No Key Product");
        body.put("unitPrice", 500.00);

        mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertEquals(before, productRepository.count(), "no product should be written on missing masterKey");
    }

    @Test
    void t03_createProduct_badMasterKey_returns403NoWrite() throws Exception {
        long before = productRepository.count();

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", "wrong-key-xyz");
        body.put("productCode", "S9T3" + (RUN % 99));
        body.put("name", "Bad Key Product");
        body.put("unitPrice", 500.00);

        mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        assertEquals(before, productRepository.count(), "no product should be written on invalid masterKey");
    }

    @Test
    void t04_createProduct_missingName_returns400NoWrite() throws Exception {
        long before = productRepository.count();

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("productCode", "S9T4" + (RUN % 99));
        body.put("unitPrice", 500.00);
        // no "name" field

        mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertEquals(before, productRepository.count(), "no product should be written on missing name");
    }

    @Test
    void t05_createProduct_productCodeTooLong_returns400NoWrite() throws Exception {
        long before = productRepository.count();

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("productCode", "TOOLONGCODE"); // 11 chars — violates 1-6 alphanumeric rule
        body.put("name", "Oversized Code Product");
        body.put("unitPrice", 500.00);

        mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertEquals(before, productRepository.count(), "no product should be written on invalid productCode");
    }

    @Test
    void t06_createProduct_noToken_returns401NoWrite() throws Exception {
        long before = productRepository.count();

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("productCode", "S9T6" + (RUN % 99));
        body.put("name", "No Token Product");
        body.put("unitPrice", 500.00);

        mockMvc.perform(post("/api/products")
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());

        assertEquals(before, productRepository.count(), "no product should be written without auth token");
    }

    // ==================== GROUP 2: PATCH /api/products/{id} — Field Edit ====================

    @Test
    void t07_patchProduct_validUpdate_persistsChanges_and_logsEditActivity() throws Exception {
        Product p = ITSupport.seedProduct(productRepository,
                "S9E" + (RUN % 999), "Original Name " + RUN, new BigDecimal("1000.00"), 100);

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("name", "Updated Name " + RUN);
        body.put("unitPrice", 1250.00);
        body.put("description", "Updated description for S9");
        body.put("encodedByName", "S9 Tester");

        mockMvc.perform(patch("/api/products/" + p.getId())
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        Product updated = productRepository.findById(p.getId()).orElseThrow();
        assertEquals("Updated Name " + RUN, updated.getName());
        assertEquals(new BigDecimal("1250.00"), updated.getUnitPrice());
        assertEquals("Updated description for S9", updated.getDescription());

        List<ActivityLog> logs = activityLogRepository
                .findByUserIdAndReportDateOrderByCreatedAtDesc(testUser.getId(), LocalDate.now());
        assertTrue(logs.stream().anyMatch(l -> "EDIT_PRODUCT".equals(l.getAction())),
                "EDIT_PRODUCT activity log entry expected");
    }

    @Test
    void t08_patchProduct_missingMasterKey_returns400NoChange() throws Exception {
        Product p = ITSupport.seedProduct(productRepository,
                "S9M8" + (RUN % 99), "Unchanged Name " + RUN, new BigDecimal("800.00"), 50);

        ObjectNode body = mapper.createObjectNode();
        body.put("name", "Should Not Change");
        // no masterKey

        mockMvc.perform(patch("/api/products/" + p.getId())
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertEquals("Unchanged Name " + RUN,
                productRepository.findById(p.getId()).orElseThrow().getName());
    }

    @Test
    void t09_patchProduct_badMasterKey_returns403NoChange() throws Exception {
        Product p = ITSupport.seedProduct(productRepository,
                "S9M9" + (RUN % 99), "Guard Name " + RUN, new BigDecimal("800.00"), 50);

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", "invalid-master");
        body.put("name", "Should Not Change");

        mockMvc.perform(patch("/api/products/" + p.getId())
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        assertEquals("Guard Name " + RUN,
                productRepository.findById(p.getId()).orElseThrow().getName());
    }

    @Test
    void t10_patchProduct_noToken_returns401NoChange() throws Exception {
        Product p = ITSupport.seedProduct(productRepository,
                "S9MA" + (RUN % 99), "Auth Guard " + RUN, new BigDecimal("800.00"), 50);

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("name", "Should Not Change");

        mockMvc.perform(patch("/api/products/" + p.getId())
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());

        assertEquals("Auth Guard " + RUN,
                productRepository.findById(p.getId()).orElseThrow().getName());
    }

    @Test
    void t11_patchProduct_unknownId_returns404() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("name", "Ghost Product");

        mockMvc.perform(patch("/api/products/999999999")
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    // ==================== GROUP 3: PATCH /{id} — Soft Delete ====================

    @Test
    void t12_patchProduct_deactivate_softDeletesProduct() throws Exception {
        Product p = ITSupport.seedProduct(productRepository,
                "S9D" + (RUN % 999), "Deactivate Me " + RUN, new BigDecimal("600.00"), 30);
        softDeletedProdId = p.getId();

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("active", false);

        mockMvc.perform(patch("/api/products/" + p.getId())
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        Product deactivated = productRepository.findById(p.getId()).orElseThrow();
        assertFalse(deactivated.getActive(), "product should be soft-deleted (active=false)");
    }

    // ==================== GROUP 4: PATCH /{id} — Manual Stock Adjustment ====================

    @Test
    void t13_patchProduct_increaseStockWh2_updatesStock_and_logsManualAdjustMovement() throws Exception {
        // ITSupport seeds stockWh1=100, stockWh2=50
        Product p = ITSupport.seedProduct(productRepository,
                "S9SA" + (RUN % 99), "Stock Up " + RUN, new BigDecimal("1000.00"), 100);
        int newWh2 = p.getStockWh2() + 30; // 50 → 80
        int expectedDelta = 30;

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("stockWh2", newWh2);
        body.put("encodedByName", "S9 Tester");

        mockMvc.perform(patch("/api/products/" + p.getId())
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        Product updated = productRepository.findById(p.getId()).orElseThrow();
        assertEquals(newWh2, updated.getStockWh2(), "stockWh2 should reflect the new value");
        assertEquals(100, updated.getStockWh1(), "stockWh1 should be unchanged");

        List<InventoryMovement> movements = inventoryMovementRepository
                .findByProductIdOrderByCreatedAtDesc(p.getId());
        assertTrue(movements.stream().anyMatch(m ->
                "MANUAL_ADJUST".equals(m.getMovementType())
                && "wh2".equals(m.getWarehouse())
                && m.getQuantity() == expectedDelta),
                "MANUAL_ADJUST movement for wh2 with quantity=+" + expectedDelta + " expected");
    }

    @Test
    void t14_patchProduct_decreaseStockWh1_updatesStock_and_logsNegativeMovement() throws Exception {
        // ITSupport seeds stockWh1=100
        Product p = ITSupport.seedProduct(productRepository,
                "S9SB" + (RUN % 99), "Stock Down " + RUN, new BigDecimal("1000.00"), 100);
        int newWh1 = p.getStockWh1() - 20; // 100 → 80
        int expectedDelta = -20;

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("stockWh1", newWh1);
        body.put("encodedByName", "S9 Tester");

        mockMvc.perform(patch("/api/products/" + p.getId())
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        Product updated = productRepository.findById(p.getId()).orElseThrow();
        assertEquals(newWh1, updated.getStockWh1(), "stockWh1 should reflect the reduced value");
        assertEquals(50, updated.getStockWh2(), "stockWh2 should be unchanged");

        List<InventoryMovement> movements = inventoryMovementRepository
                .findByProductIdOrderByCreatedAtDesc(p.getId());
        assertTrue(movements.stream().anyMatch(m ->
                "MANUAL_ADJUST".equals(m.getMovementType())
                && "wh1".equals(m.getWarehouse())
                && m.getQuantity() == expectedDelta),
                "MANUAL_ADJUST movement for wh1 with quantity=" + expectedDelta + " expected");
    }

    // ==================== GROUP 5: PATCH /api/products/{id}/tag — Selling Tag ====================

    @Test
    void t15_patchTag_validTag_updatesSellingTag_and_logsActivity() throws Exception {
        Product p = ITSupport.seedProduct(productRepository,
                "S9G" + (RUN % 999), "Taggable " + RUN, new BigDecimal("700.00"), 80);
        assertEquals("SELLING", p.getSellingTag(), "freshly seeded product should have default tag SELLING");

        ObjectNode body = mapper.createObjectNode();
        body.put("sellingTag", "HOT");
        body.put("userName", "S9 Tester");
        // note: no masterKey required for this endpoint

        mockMvc.perform(patch("/api/products/" + p.getId() + "/tag")
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        Product updated = productRepository.findById(p.getId()).orElseThrow();
        assertEquals("HOT", updated.getSellingTag());

        List<ActivityLog> logs = activityLogRepository
                .findByUserIdAndReportDateOrderByCreatedAtDesc(testUser.getId(), LocalDate.now());
        assertTrue(logs.stream().anyMatch(l -> "UPDATE_PRODUCT_TAG".equals(l.getAction())),
                "UPDATE_PRODUCT_TAG activity log entry expected");
    }

    @Test
    void t16_patchTag_invalidTagValue_returns400NoChange() throws Exception {
        Product p = ITSupport.seedProduct(productRepository,
                "S9GT" + (RUN % 99), "Invalid Tag " + RUN, new BigDecimal("700.00"), 80);

        ObjectNode body = mapper.createObjectNode();
        body.put("sellingTag", "GREAT"); // not a valid enum — must be HOT, SELLING, or SLOW

        mockMvc.perform(patch("/api/products/" + p.getId() + "/tag")
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertEquals("SELLING",
                productRepository.findById(p.getId()).orElseThrow().getSellingTag(),
                "sellingTag should remain SELLING after rejected tag update");
    }

    @Test
    void t17_patchTag_noToken_returns401() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("sellingTag", "HOT");

        mockMvc.perform(patch("/api/products/999/tag")
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void t18_patchTag_unknownId_returns404() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("sellingTag", "HOT");

        mockMvc.perform(patch("/api/products/999999999/tag")
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    // ==================== GROUP 6: PATCH /{id} — Set Product Components ====================

    @Test
    void t19_patchProduct_setComponents_persistsComponentRows() throws Exception {
        // There is no /set-components endpoint — components are saved via PATCH /{id}
        // with isSet:true + components list in the same payload.
        Product setProduct = ITSupport.seedProduct(productRepository,
                "S9H" + (RUN % 999), "Set Product " + RUN, new BigDecimal("3000.00"), 0);
        Product component = ITSupport.seedProduct(productRepository,
                "S9I" + (RUN % 999), "Component A " + RUN, new BigDecimal("500.00"), 200);

        ObjectNode body = mapper.createObjectNode();
        body.put("masterKey", MASTER_KEY);
        body.put("isSet", true);
        ArrayNode comps = mapper.createArrayNode();
        ObjectNode comp = mapper.createObjectNode();
        comp.put("componentProductId", component.getId());
        comp.put("quantityPerSet", 2);
        comps.add(comp);
        body.set("components", comps);

        mockMvc.perform(patch("/api/products/" + setProduct.getId())
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        Product updated = productRepository.findById(setProduct.getId()).orElseThrow();
        assertTrue(updated.getIsSet(), "isSet should be true after PATCH");

        List<ProductSetComponent> rows = productSetComponentRepository
                .findBySetProductId(setProduct.getId());
        assertEquals(1, rows.size(), "exactly one component row should be persisted");
        assertEquals(component.getId(), rows.get(0).getComponentProductId());
        assertEquals(2, rows.get(0).getQuantityPerSet());
    }

    @Test
    void t20_patchProduct_replaceComponents_deletesOldRowsPersistsNew() throws Exception {
        Product setProduct = ITSupport.seedProduct(productRepository,
                "S9J" + (RUN % 999), "Replace Set " + RUN, new BigDecimal("4000.00"), 0);
        Product compA = ITSupport.seedProduct(productRepository,
                "S9K" + (RUN % 999), "Component B " + RUN, new BigDecimal("400.00"), 100);
        Product compB = ITSupport.seedProduct(productRepository,
                "S9L" + (RUN % 999), "Component C " + RUN, new BigDecimal("600.00"), 100);

        // First PATCH: isSet=true with compA (qty 1)
        ObjectNode first = mapper.createObjectNode();
        first.put("masterKey", MASTER_KEY);
        first.put("isSet", true);
        ArrayNode firstComps = mapper.createArrayNode();
        ObjectNode ca = mapper.createObjectNode();
        ca.put("componentProductId", compA.getId());
        ca.put("quantityPerSet", 1);
        firstComps.add(ca);
        first.set("components", firstComps);

        mockMvc.perform(patch("/api/products/" + setProduct.getId())
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(first)))
                .andExpect(status().isOk());

        assertEquals(1, productSetComponentRepository.findBySetProductId(setProduct.getId()).size(),
                "one component row after first PATCH");

        // Second PATCH: replace with compB (qty 3) — controller deletes old rows first
        ObjectNode second = mapper.createObjectNode();
        second.put("masterKey", MASTER_KEY);
        second.put("isSet", true);
        ArrayNode secondComps = mapper.createArrayNode();
        ObjectNode cb = mapper.createObjectNode();
        cb.put("componentProductId", compB.getId());
        cb.put("quantityPerSet", 3);
        secondComps.add(cb);
        second.set("components", secondComps);

        mockMvc.perform(patch("/api/products/" + setProduct.getId())
                .header("Authorization", "Bearer " + userJwt)
                .contentType("application/json")
                .content(mapper.writeValueAsString(second)))
                .andExpect(status().isOk());

        List<ProductSetComponent> rows = productSetComponentRepository
                .findBySetProductId(setProduct.getId());
        assertEquals(1, rows.size(), "old component should be replaced — still exactly 1 row");
        assertEquals(compB.getId(), rows.get(0).getComponentProductId(),
                "surviving component should be compB, not compA");
        assertEquals(3, rows.get(0).getQuantityPerSet());
    }

    // ==================== GROUP 7: Product Reads ====================

    @Test
    void t21_getActiveProducts_includesActive_excludesSoftDeleted() throws Exception {
        // readProdId is always active; softDeletedProdId (from t12) should be absent
        MvcResult result = mockMvc.perform(get("/api/products")
                .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(resp.isArray(), "response should be an array");

        boolean foundActive = false;
        boolean foundDeleted = false;
        for (JsonNode node : resp) {
            long id = node.get("id").asLong();
            if (id == readProdId) foundActive = true;
            if (softDeletedProdId != null && id == softDeletedProdId) foundDeleted = true;
        }
        assertTrue(foundActive, "active read-test product should be in GET /api/products");
        assertFalse(foundDeleted, "soft-deleted product should NOT be in GET /api/products (active-only)");
    }

    @Test
    void t22_getAllProducts_includesBothActiveAndSoftDeleted() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products/all")
                .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(resp.isArray(), "response should be an array");

        boolean foundActive = false;
        boolean foundDeleted = false;
        for (JsonNode node : resp) {
            long id = node.get("id").asLong();
            if (id == readProdId) foundActive = true;
            if (softDeletedProdId != null && id == softDeletedProdId) foundDeleted = true;
        }
        assertTrue(foundActive, "active product should appear in GET /api/products/all");
        if (softDeletedProdId != null) {
            assertTrue(foundDeleted, "soft-deleted product should appear in GET /api/products/all");
        }
    }

    @Test
    void t23_getCategories_includesSeededCategory() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products/categories")
                .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(resp.isArray(), "categories should be a string array");
        boolean found = false;
        for (JsonNode node : resp) {
            if ("Pizza Box".equals(node.asText())) { found = true; break; }
        }
        assertTrue(found, "Pizza Box (seeded in @BeforeAll) should be in categories list");
    }

    @Test
    void t24_getSubCategories_byCategory_includesSeededSubCategory() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/products/sub-categories")
                .param("category", "Pizza Box")
                .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(resp.isArray(), "sub-categories should be a string array");
        boolean found = false;
        for (JsonNode node : resp) {
            if ("Plain".equals(node.asText())) { found = true; break; }
        }
        assertTrue(found, "Plain (seeded in @BeforeAll under Pizza Box) should be in sub-categories");
    }

    @Test
    void t25_searchProducts_byNameFragment_findsMatch_noiseReturnsEmpty() throws Exception {
        // Partial name match — "S9 Read Product" is contained in our seeded product's name
        MvcResult result = mockMvc.perform(
                get("/api/products/search")
                .param("name", "S9 Read Product")
                .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(resp.isArray());
        boolean found = false;
        for (JsonNode node : resp) {
            if (node.get("id").asLong() == readProdId) { found = true; break; }
        }
        assertTrue(found, "GET /api/products/search?name= should find our seeded product by name fragment");

        // Noise string unique enough to guarantee no match
        MvcResult noiseResult = mockMvc.perform(
                get("/api/products/search")
                .param("name", "ZZZZNOMATCH" + RUN)
                .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode noiseResp = mapper.readTree(noiseResult.getResponse().getContentAsString());
        assertTrue(noiseResp.isArray());
        assertEquals(0, noiseResp.size(), "noise search should return an empty array");
    }

    @Test
    void t26_readEndpoints_noToken_return401() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/products/all"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/products/search?name=test"))
                .andExpect(status().isUnauthorized());
    }
}
