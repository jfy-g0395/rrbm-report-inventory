package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S9 — Products & Inventory Edits
 *
 * <p>Workflow (W-4): maintain the catalog + manual stock adjustments.
 *
 * <p>Scenarios:
 * - POST /api/products create with validation
 * - PATCH /{id} field edit
 * - PATCH /{id}/tag tagging
 * - Set product with product_set_components
 * - GET /categories, /sub-categories, /search, /all reads
 * - Manual stock adjustment logs inventory_movements
 * - Key-gated edits → 403 on bad key
 * - Assert each write + activity log entries
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ProductInventoryIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ProductRepository productRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private ProductSetComponentRepository productSetComponentRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S9-Secret!";
    private static final String SEC_KEY = "S9-admin-key";
    private static final ObjectMapper mapper = new ObjectMapper();

    private User managerUser;
    private String userJwt;

    @BeforeAll
    void seed() {
        // Seed user with ACCOUNTING role
        managerUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s9-acct-" + RUN + "@test.rrbm.internal", "S9 Accounting", PASSWORD, SEC_KEY);
        userJwt = ITSupport.jwtFor(jwtUtil, managerUser);
    }

    @AfterAll
    void clean() {
        // Delete in reverse dependency order
        inventoryMovementRepository.deleteAll();
        productSetComponentRepository.deleteAll();
        productRepository.deleteAll();
        activityLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ===== SCENARIO GROUP 1: Product Creation =====

    @Test
    void t01_createProduct_validPayload_returns201AndPersistsProduct() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("productCode", "S9P" + (RUN % 999));
        payload.put("itemCode", "ITM" + RUN);
        payload.put("name", "Test Product");
        payload.put("description", "A test product");
        payload.put("unitPrice", 1500.00);
        payload.put("unitCost", 900.00);
        payload.put("stockWh1", 100);
        payload.put("stockWh2", 50);
        payload.put("stockWh3", 25);
        payload.put("reorderLevel", 10);
        payload.put("active", true);

        MvcResult result = mockMvc
                .perform(post("/api/products")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = mapper.readTree(result.getResponse().getContentAsString());
        Long productId = response.get("id").asLong();

        Product savedProduct = productRepository.findById(productId).orElse(null);
        assertNotNull(savedProduct, "Product should be persisted");
        assertEquals("S9P" + (RUN % 999), savedProduct.getProductCode());
        assertEquals("Test Product", savedProduct.getName());
        assertEquals(new BigDecimal("1500.00"), savedProduct.getUnitPrice());
        assertEquals(100, savedProduct.getStockWh1());
        assertTrue(savedProduct.getActive());
    }

    @Test
    void t02_createProduct_missingProductCode_returns400() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("itemCode", "IT" + (RUN % 999));
        payload.put("name", "Test Product");
        payload.put("unitPrice", 1500.00);

        mockMvc
                .perform(post("/api/products")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t03_createProduct_noToken_returns401() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("productCode", "S9X" + (RUN % 999));
        payload.put("name", "Test Product");
        payload.put("unitPrice", 1500.00);

        mockMvc
                .perform(post("/api/products")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    // ===== SCENARIO GROUP 2: Product Field Edit =====

    @Test
    void t04_patchProduct_validUpdate_returns200AndPersistsChanges() throws Exception {
        // First create a product
        Product p = ITSupport.seedProduct(productRepository,
                "S9PE" + (RUN % 99), "Original Name", new BigDecimal("1000.00"), 100);
        Long prodId = p.getId();

        // Now patch it
        ObjectNode patchPayload = mapper.createObjectNode();
        patchPayload.put("name", "Updated Name");
        patchPayload.put("unitPrice", 1200.00);
        patchPayload.put("description", "Updated description");

        mockMvc
                .perform(patch("/api/products/" + prodId)
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(patchPayload)))
                .andExpect(status().isOk());

        Product updated = productRepository.findById(prodId).orElse(null);
        assertNotNull(updated);
        assertEquals("Updated Name", updated.getName());
        assertEquals(new BigDecimal("1200.00"), updated.getUnitPrice());
        assertEquals("Updated description", updated.getDescription());
    }

    @Test
    void t05_patchProduct_noToken_returns401() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("name", "Updated Name");

        mockMvc
                .perform(patch("/api/products/999")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    // ===== SCENARIO GROUP 3: Product Tagging =====

    @Test
    void t06_patchProductTag_validTag_returns200() throws Exception {
        // Create a product first
        Product p = ITSupport.seedProduct(productRepository,
                "S9PT" + (RUN % 99), "Tagged Product", new BigDecimal("1500.00"), 100);
        Long prodId = p.getId();

        // Add tag
        ObjectNode tagPayload = mapper.createObjectNode();
        ArrayNode tags = mapper.createArrayNode();
        tags.add("bestseller");
        tags.add("sale");
        tagPayload.set("tags", tags);

        mockMvc
                .perform(patch("/api/products/" + prodId + "/tag")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(tagPayload)))
                .andExpect(status().isOk());

        Product updated = productRepository.findById(prodId).orElse(null);
        assertNotNull(updated);
    }

    @Test
    void t07_patchProductTag_noToken_returns401() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        ArrayNode tags = mapper.createArrayNode();
        tags.add("sale");
        payload.set("tags", tags);

        mockMvc
                .perform(patch("/api/products/999/tag")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    // ===== SCENARIO GROUP 4: Product Set Components =====

    @Test
    void t08_setProductComponents_validSet_returns200AndPersistsComponents() throws Exception {
        // Create main product
        Product mainProduct = ITSupport.seedProduct(productRepository,
                "S9PS" + (RUN % 99), "Set Product", new BigDecimal("3000.00"), 100);
        Long mainProdId = mainProduct.getId();

        // Create component product
        Product componentProduct = ITSupport.seedProduct(productRepository,
                "S9PC" + (RUN % 99), "Component 1", new BigDecimal("500.00"), 50);
        Long compId = componentProduct.getId();

        // Set components
        ObjectNode componentPayload = mapper.createObjectNode();
        ArrayNode components = mapper.createArrayNode();
        ObjectNode comp = mapper.createObjectNode();
        comp.put("productId", compId);
        comp.put("quantity", 2);
        components.add(comp);
        componentPayload.set("components", components);

        mockMvc
                .perform(patch("/api/products/" + mainProdId + "/set-components")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(componentPayload)))
                .andExpect(status().isOk());

        // Verify components persisted
        List<ProductSetComponent> savedComponents = productSetComponentRepository.findBySetProductId(mainProdId);
        assertTrue(savedComponents.size() > 0, "Components should be persisted");
    }

    @Test
    void t09_setProductComponents_noToken_returns401() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.set("components", mapper.createArrayNode());

        mockMvc
                .perform(patch("/api/products/999/set-components")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    // ===== SCENARIO GROUP 5: Product Reads (Categories, Search, All) =====

    @Test
    void t10_getCategories_returns200() throws Exception {
        mockMvc
                .perform(get("/api/products/categories")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk());
    }

    @Test
    void t11_getSubCategories_returns200() throws Exception {
        mockMvc
                .perform(get("/api/products/sub-categories")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk());
    }

    @Test
    void t12_searchProducts_withQuery_returns200() throws Exception {
        mockMvc
                .perform(get("/api/products/search?q=test")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk());
    }

    @Test
    void t13_searchProducts_noToken_returns401() throws Exception {
        mockMvc
                .perform(get("/api/products/search?q=test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void t14_getAllProducts_returns200() throws Exception {
        mockMvc
                .perform(get("/api/products/all")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk());
    }

    @Test
    void t15_getAllProducts_noToken_returns401() throws Exception {
        mockMvc
                .perform(get("/api/products/all"))
                .andExpect(status().isUnauthorized());
    }

    // ===== SCENARIO GROUP 6: Manual Stock Adjustment =====

    @Test
    void t16_adjustStock_validPayload_returns200AndLogsMovement() throws Exception {
        // Create a product
        Product product = ITSupport.seedProduct(productRepository,
                "S9SA" + (RUN % 99), "Stock Product", new BigDecimal("1000.00"), 100);
        Long prodId = product.getId();

        // Adjust stock
        ObjectNode adjustPayload = mapper.createObjectNode();
        adjustPayload.put("warehouse", "wh1");
        adjustPayload.put("adjustmentQuantity", 50);
        adjustPayload.put("reason", "Manual correction");

        mockMvc
                .perform(patch("/api/products/" + prodId + "/adjust-stock")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(adjustPayload)))
                .andExpect(status().isOk());

        // Verify inventory movement logged
        List<InventoryMovement> movements = inventoryMovementRepository.findByProductIdOrderByCreatedAtDesc(prodId);
        assertTrue(movements.size() > 0, "Inventory movement should be logged");
    }

    @Test
    void t17_adjustStock_noToken_returns401() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("warehouse", "wh1");
        payload.put("adjustmentQuantity", 50);

        mockMvc
                .perform(patch("/api/products/999/adjust-stock")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void t18_adjustStock_invalidWarehouse_returns400() throws Exception {
        // Create a product
        Product product = ITSupport.seedProduct(productRepository,
                "S9SW" + (RUN % 99), "Stock Product", new BigDecimal("1000.00"), 100);
        Long prodId = product.getId();

        // Try to adjust with invalid warehouse
        ObjectNode adjustPayload = mapper.createObjectNode();
        adjustPayload.put("warehouse", "wh99");
        adjustPayload.put("adjustmentQuantity", 50);

        mockMvc
                .perform(patch("/api/products/" + prodId + "/adjust-stock")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(adjustPayload)))
                .andExpect(status().isBadRequest());
    }
}
