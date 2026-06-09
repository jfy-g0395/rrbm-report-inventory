package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A2 integration tests — O.P. fields on order items and agent-linked order flow.
 *
 * Lifecycle:
 *   @BeforeAll  — create test user + JWT, test agent, test product (with stock)
 *   @AfterAll   — delete transactions → orders → agent → product → user
 *
 * Tests run in declaration order via @TestMethodOrder so later tests can
 * rely on state (order IDs) captured by earlier ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA2Test {

    @Autowired private MockMvc                      mockMvc;
    @Autowired private UserRepository               userRepository;
    @Autowired private AgentRepository              agentRepository;
    @Autowired private ProductRepository            productRepository;
    @Autowired private OrderRepository              orderRepository;
    @Autowired private TransactionRepository        transactionRepository;
    @Autowired private InventoryMovementRepository  inventoryMovementRepository;
    @Autowired private CommissionEntryRepository    entryRepository;
    @Autowired private JwtUtil                      jwtUtil;

    private final ObjectMapper    mapper          = new ObjectMapper();
    private       User            testUser;
    private       String          jwt;
    private       Agent           testAgent;
    private       Product         testProduct;
    private final List<String>    createdOrderIds = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());

        testUser = new User();
        testUser.setEmail("a2-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A2 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        testAgent = new Agent();
        testAgent.setAgentCode("AGENT-TEST-" + suffix.substring(suffix.length() - 4));
        testAgent.setFullName("A2 Test Agent " + suffix);
        testAgent.setContactNumber("09170000001");
        testAgent.setTerritory("Test Territory");
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);

        testProduct = new Product();
        testProduct.setName("A2 Test Product " + suffix);
        testProduct.setSku("A2SKU-" + suffix.substring(suffix.length() - 6));
        testProduct.setUnitPrice(new BigDecimal("50.00"));
        testProduct.setStockWh1(9999);
        testProduct.setActive(true);
        testProduct = productRepository.save(testProduct);
    }

    @AfterAll
    void tearDownAll() {
        createdOrderIds.forEach(orderId -> {
            entryRepository.findByOrderId(orderId)
                    .forEach(e -> entryRepository.deleteById(e.getId()));
        });
        // Delete transactions and inventory movements before deleting orders (FK constraint)
        createdOrderIds.forEach(orderId -> {
            transactionRepository.deleteAll(
                    transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId));
            inventoryMovementRepository.deleteAll(
                    inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(orderId));
        });
        createdOrderIds.forEach(orderId ->
                orderRepository.findById(orderId).ifPresent(orderRepository::delete));

        agentRepository.delete(testAgent);

        // Remove inventory movements that reference the product before deleting it
        inventoryMovementRepository.deleteAll(
                inventoryMovementRepository.findByProductIdOrderByCreatedAtDesc(testProduct.getId()));
        productRepository.delete(testProduct);

        userRepository.delete(testUser);
    }

    private Map<String, Object> validOrderBody(Long agentId,
                                                BigDecimal basePrice,
                                                BigDecimal opPerUnit) {
        Map<String, Object> item = new java.util.HashMap<>();
        item.put("productId",   testProduct.getId());
        item.put("productName", testProduct.getName());
        item.put("quantity",    2);
        item.put("unitPrice",   "50.00");
        item.put("warehouse",   "wh1");
        if (basePrice  != null) item.put("basePrice",  basePrice);
        if (opPerUnit  != null) item.put("opPerUnit",  opPerUnit);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("customerName", "A2 Test Customer");
        body.put("source",       "AGENT");
        body.put("paymentMode",  "CASH");
        body.put("items",        List.of(item));
        if (agentId != null) body.put("agentId", agentId);
        return body;
    }

    // ── A2-a: POST with valid agentId → 201; response.agentId = that id ──────

    @Test
    @Order(1)
    void createOrder_withValidAgentId_returns201AndAgentIdInResponse() throws Exception {
        String body = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validOrderBody(testAgent.getId(), null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);
        String orderId = (String) resp.get("id");
        assertNotNull(orderId, "Response must contain order id");
        createdOrderIds.add(orderId);

        Number agentIdInResp = (Number) resp.get("agentId");
        assertNotNull(agentIdInResp, "Response must include agentId");
        assertEquals(testAgent.getId(), agentIdInResp.longValue(),
                "Response agentId must equal the supplied agent id");
    }

    // ── A2-b: POST with invalid/INACTIVE agentId → 400 ───────────────────────

    @Test
    @Order(2)
    void createOrder_withInvalidAgentId_returns400() throws Exception {
        // Non-existent id
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validOrderBody(999999999L, null, null))))
                .andExpect(status().isBadRequest());

        // INACTIVE agent
        testAgent.setStatus("INACTIVE");
        agentRepository.save(testAgent);
        try {
            mockMvc.perform(post("/api/orders")
                            .header("Authorization", "Bearer " + jwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(validOrderBody(testAgent.getId(), null, null))))
                    .andExpect(status().isBadRequest());
        } finally {
            testAgent.setStatus("ACTIVE");
            agentRepository.save(testAgent);
        }
    }

    // ── A2-c: POST with agentId + basePrice + opRate → items carry opAmount ──

    @Test
    @Order(3)
    void createOrder_withAgentAndOpFields_itemsHaveCorrectOpAmount() throws Exception {
        BigDecimal basePrice = new BigDecimal("40.00");
        BigDecimal opPerUnit = new BigDecimal("6.00");
        // expected opAmount = 6.00 * 2 = 12.00

        String body = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                validOrderBody(testAgent.getId(), basePrice, opPerUnit))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);
        String orderId = (String) resp.get("id");
        assertNotNull(orderId);
        createdOrderIds.add(orderId);

        List<?> items = (List<?>) resp.get("items");
        assertNotNull(items, "Response must contain items");
        assertFalse(items.isEmpty(), "Response items must not be empty");

        Map<?, ?> firstItem = (Map<?, ?>) items.get(0);
        Number opAmount = (Number) firstItem.get("opAmount");
        assertNotNull(opAmount, "Item must include opAmount when basePrice and opRate are supplied");
        assertEquals(0, new BigDecimal("12.00").compareTo(new BigDecimal(opAmount.toString())),
                "opAmount must equal opPerUnit * qty = 6.00 * 2 = 12.00");
    }

    // ── A2-d: Receipt suppression — printOrderReceipt must not interpolate agentName ──

    @Test
    @Order(4)
    void receiptSuppression_appJs_agentSourceHasNoNameInterpolation() throws Exception {
        Path appJs = Paths.get(System.getProperty("user.dir"),
                "..", "rrbm_frontend", "rrbm-frontend", "js", "app.js");
        assertTrue(Files.exists(appJs), "app.js must exist at expected path: " + appJs);

        String content = Files.readString(appJs);
        assertFalse(content.contains("'Agent' + (order.agentName"),
                "printOrderReceipt must not append agentName after the A2 suppression patch — "
                        + "AGENT source should display only 'Agent'");
    }
}
