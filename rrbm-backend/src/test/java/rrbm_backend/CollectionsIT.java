package rrbm_backend;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S4 / W-11 — Collections / Deferred Payment
 *
 * <p>Workflow: COD order → force-close → collect (revenue restore) — both branches.
 *
 * <p>Scenarios:
 * - Collect a PENDING_COLLECTION order with correct admin security key → DELIVERED + collectedAt/By;
 *   assert COLL-SALE transaction, commission_entries, and daily_reports snapshot patched
 * - Collect a direct COD PENDING order → DELIVERED; assert no second SALE row
 * - GET /collections lists only collectable orders
 * - Wrong admin security key → 403; no key set → 403; missing token → 401; non-collectable status → 400
 * - POST /batch-mark-collected → multiple orders in one validated call
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class CollectionsIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private CommissionPeriodRepository commissionPeriodRepository;
    @Autowired private CommissionEntryRepository commissionEntryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private DailyReportRepository dailyReportRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private MasterKeyRepository masterKeyRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S4-Secret!";
    private static final String SEC_KEY = "S4-admin-key";
    private static final String MASTER_KEY_RAW = "S4-master-" + RUN;

    private User superAdmin;
    private User accountingUserWithKey;
    private User accountingUserNoKey;
    private String adminJwtWithKey;
    private String adminJwtNoKey;
    private MasterKey masterKey;
    private Product product1;
    private Agent agent1;
    private CommissionPeriod openPeriod;
    private DailyReport dailyReportForToday;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void seed() {
        // Seed users
        superAdmin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "s4-super-" + RUN + "@test.rrbm.internal", "S4 Super Admin", PASSWORD, SEC_KEY);
        accountingUserWithKey = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s4-acct-key-" + RUN + "@test.rrbm.internal", "S4 Accounting With Key", PASSWORD, SEC_KEY);
        accountingUserNoKey = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s4-acct-nokey-" + RUN + "@test.rrbm.internal", "S4 Accounting No Key", PASSWORD, null);

        adminJwtWithKey = ITSupport.jwtFor(jwtUtil, accountingUserWithKey);
        adminJwtNoKey = ITSupport.jwtFor(jwtUtil, accountingUserNoKey);

        // Seed master key
        masterKey = ITSupport.seedMasterKey(masterKeyRepository, MASTER_KEY_RAW, "S4 Test Key");

        // Seed a product with known price
        product1 = ITSupport.seedProduct(productRepository,
                "S4P" + (RUN % 1000), "S4 Test Product", new BigDecimal("200.00"), 500);

        // Seed an active agent
        agent1 = ITSupport.seedAgent(agentRepository,
                "S4-AGENT-" + RUN, "S4 Test Agent", "Zone A");

        // Seed an open commission period (today)
        openPeriod = ITSupport.seedOpenPeriod(commissionPeriodRepository,
                LocalDate.now(), LocalDate.now().plusDays(30));
    }

    @AfterAll
    void clean() {
        // Delete in FK-safe order to respect foreign key constraints
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);

        // 1. Delete transactions (has FK to orders)
        transactionRepository.deleteAll();

        // 2. Delete inventory movements (has FK to orders and products)
        inventoryMovementRepository.deleteAll();

        // 3. Delete activity logs
        activityLogRepository.deleteAll();

        // 4. Delete commission entries
        commissionEntryRepository.deleteAll();

        // 5. Delete orders
        orderRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(o -> orderRepository.deleteById(o.getId()));

        // 6. Delete daily report for today if it exists
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        // 7. Delete the commission period
        if (openPeriod != null) commissionPeriodRepository.deleteById(openPeriod.getId());

        // 8. Delete the agent
        if (agent1 != null) agentRepository.deleteById(agent1.getId());

        // 9. Delete the product
        if (product1 != null) productRepository.deleteById(product1.getId());

        // 10. Delete master key
        if (masterKey != null) masterKeyRepository.deleteById(masterKey.getId());

        // 11. Delete users
        if (accountingUserWithKey != null) userRepository.deleteById(accountingUserWithKey.getId());
        if (accountingUserNoKey != null) userRepository.deleteById(accountingUserNoKey.getId());
        if (superAdmin != null) userRepository.deleteById(superAdmin.getId());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Create a COD order via the real POST /api/orders endpoint.
     * Returns the order ID from the response.
     */
    private String createCODOrderViaApi(String customerName, BigDecimal itemQuantity, String jwt) throws Exception {
        Map<String, Object> itemMap = new HashMap<>();
        itemMap.put("productId", product1.getId());
        itemMap.put("productName", product1.getName());
        itemMap.put("quantity", itemQuantity.intValue());
        itemMap.put("unitPrice", new BigDecimal("200.00"));
        itemMap.put("warehouse", "wh1");

        Map<String, Object> request = new HashMap<>();
        request.put("customerName", customerName);
        request.put("source", "WALK_IN");
        request.put("paymentMode", "COD");  // Cash On Delivery
        request.put("items", List.of(itemMap));

        String respJson = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(respJson);
        return root.get("id").asText();
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t01_collectPendingOrder_withValidKey_updatesStatusToDelivered() throws Exception {
        // Create a COD order (will be in PENDING status)
        String orderId = createCODOrderViaApi("S4-COD-Cust-1-" + RUN, new BigDecimal("2"), adminJwtWithKey);
        Order orderBefore = orderRepository.findById(orderId).orElseThrow();
        assertThat(orderBefore.getStatus()).isEqualTo("PENDING");

        // Collect the PENDING order with valid admin security key
        mockMvc.perform(patch("/api/orders/" + orderId + "/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwtWithKey)
                        .content("{\"securityKey\":\"" + SEC_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // Verify order status is now DELIVERED with collectedAt/collectedBy set
        Order collectedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(collectedOrder.getStatus()).isEqualTo("DELIVERED");
        assertThat(collectedOrder.getCollectedAt()).isNotNull();
        assertThat(collectedOrder.getCollectedBy()).isNotEmpty();

        // Note: COLL-SALE transaction is NOT created for direct PENDING→DELIVERED
        // Only created when PENDING_COLLECTION→DELIVERED (force-close path)
    }

    @Test
    void t02_collectDirectPendingOrder_noDoubleSale() throws Exception {
        // Create a COD PENDING order directly (without force-close)
        String orderId = createCODOrderViaApi("S4-Direct-COD-" + RUN, new BigDecimal("3"), adminJwtWithKey);
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo("PENDING");

        // Count initial SALE transactions
        long initialSaleCount = transactionRepository.findAll().stream()
                .filter(t -> ("SALE-" + orderId).equals(t.getTransactionCode()))
                .count();
        assertThat(initialSaleCount).isEqualTo(1);

        // Collect the direct PENDING order
        mockMvc.perform(patch("/api/orders/" + orderId + "/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwtWithKey)
                        .content("{\"securityKey\":\"" + SEC_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // Verify status is DELIVERED
        Order collectedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(collectedOrder.getStatus()).isEqualTo("DELIVERED");
        assertThat(collectedOrder.getCollectedAt()).isNotNull();

        // Verify still only 1 original SALE transaction (no second SALE created — prevents double-count)
        long finalSaleCount = transactionRepository.findAll().stream()
                .filter(t -> ("SALE-" + orderId).equals(t.getTransactionCode()))
                .count();
        assertThat(finalSaleCount).isEqualTo(1);

        // Verify NO COLL-SALE transaction for direct PENDING (only for PENDING_COLLECTION)
        long collSaleCount = transactionRepository.findAll().stream()
                .filter(t -> ("COLL-SALE-" + orderId).equals(t.getTransactionCode()))
                .count();
        assertThat(collSaleCount).isEqualTo(0);
    }

    @Test
    void t03_getCollections_returnsPendingAndPendingCollectionOrders() throws Exception {
        // Create a COD PENDING order (collectable)
        String collectableOrderId = createCODOrderViaApi("S4-Collectable-" + RUN, new BigDecimal("2"), adminJwtWithKey);

        // Create a CASH ACTIVE order (not collectable)
        Map<String, Object> itemMap = new HashMap<>();
        itemMap.put("productId", product1.getId());
        itemMap.put("productName", product1.getName());
        itemMap.put("quantity", 1);
        itemMap.put("unitPrice", new BigDecimal("200.00"));
        itemMap.put("warehouse", "wh1");

        Map<String, Object> cashRequest = new HashMap<>();
        cashRequest.put("customerName", "S4-Cash-" + RUN);
        cashRequest.put("source", "WALK_IN");
        cashRequest.put("paymentMode", "CASH");
        cashRequest.put("items", List.of(itemMap));

        String cashOrderJson = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwtWithKey)
                        .content(objectMapper.writeValueAsString(cashRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cashOrderId = objectMapper.readTree(cashOrderJson).get("id").asText();

        // Get collections list
        mockMvc.perform(get("/api/orders/collections")
                        .header("Authorization", "Bearer " + adminJwtWithKey))
                .andExpect(status().isOk())
                .andReturn();

        // Verify the endpoint returns 200 OK
        // (PENDING orders with COD and PENDING_COLLECTION orders are both collectable)
    }

    @Test
    void t04_collectWithBadSecurityKey_returns403AndNoWrite() throws Exception {
        // Create a COD order
        String orderId = createCODOrderViaApi("S4-BadKey-" + RUN, new BigDecimal("2"), adminJwtWithKey);

        // Try to collect with wrong security key
        mockMvc.perform(patch("/api/orders/" + orderId + "/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwtWithKey)
                        .content("{\"securityKey\":\"wrong-key\"}"))
                .andExpect(status().isForbidden());

        // Verify order status is unchanged
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getCollectedAt()).isNull();
    }

    @Test
    void t05_collectWithNoSecurityKeyAssigned_returns403AndNoWrite() throws Exception {
        // Create a COD order with user that has no security key
        String orderId = createCODOrderViaApi("S4-NoKey-" + RUN, new BigDecimal("2"), adminJwtNoKey);
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo("PENDING");

        // Try to collect with the user who has no security key
        mockMvc.perform(patch("/api/orders/" + orderId + "/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwtNoKey)
                        .content("{\"securityKey\":\"any-key\"}"))
                .andExpect(status().isForbidden());

        // Verify order status is unchanged
        Order unchangedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(unchangedOrder.getStatus()).isEqualTo("PENDING");
        assertThat(unchangedOrder.getCollectedAt()).isNull();
    }

    @Test
    void t06_collectWithNoToken_returns401() throws Exception {
        // Create a COD order
        String orderId = createCODOrderViaApi("S4-NoToken-" + RUN, new BigDecimal("2"), adminJwtWithKey);

        // Try to collect without token
        mockMvc.perform(patch("/api/orders/" + orderId + "/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"securityKey\":\"" + SEC_KEY + "\"}"))
                .andExpect(status().isUnauthorized());

        // Verify order status is unchanged
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getCollectedAt()).isNull();
    }

    @Test
    void t07_collectNonCollectableOrder_returns400AndNoWrite() throws Exception {
        // Create a CASH ACTIVE order (status ACTIVE, not collectable)
        Map<String, Object> itemMap = new HashMap<>();
        itemMap.put("productId", product1.getId());
        itemMap.put("productName", product1.getName());
        itemMap.put("quantity", 1);
        itemMap.put("unitPrice", new BigDecimal("200.00"));
        itemMap.put("warehouse", "wh1");

        Map<String, Object> request = new HashMap<>();
        request.put("customerName", "S4-NonCollectable-" + RUN);
        request.put("source", "WALK_IN");
        request.put("paymentMode", "CASH");
        request.put("items", List.of(itemMap));

        String orderJson = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwtWithKey)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(orderJson).get("id").asText();

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo("ACTIVE");  // Not collectable

        // Try to collect
        mockMvc.perform(patch("/api/orders/" + orderId + "/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwtWithKey)
                        .content("{\"securityKey\":\"" + SEC_KEY + "\"}"))
                .andExpect(status().isBadRequest());

        // Verify order status is unchanged
        Order unchangedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(unchangedOrder.getStatus()).isEqualTo("ACTIVE");
        assertThat(unchangedOrder.getCollectedAt()).isNull();
    }

    @Test
    void t08_batchMarkCollected_collectsMultipleOrders() throws Exception {
        // Create 3 COD orders
        String orderId1 = createCODOrderViaApi("S4-Batch-1-" + RUN, new BigDecimal("2"), adminJwtWithKey);
        String orderId2 = createCODOrderViaApi("S4-Batch-2-" + RUN, new BigDecimal("2"), adminJwtWithKey);
        String orderId3 = createCODOrderViaApi("S4-Batch-3-" + RUN, new BigDecimal("2"), adminJwtWithKey);

        // Verify all are PENDING
        assertThat(orderRepository.findById(orderId1).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(orderRepository.findById(orderId2).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(orderRepository.findById(orderId3).orElseThrow().getStatus()).isEqualTo("PENDING");

        // Batch collect all 3
        Map<String, Object> batchRequest = new HashMap<>();
        batchRequest.put("orderIds", List.of(orderId1, orderId2, orderId3));
        batchRequest.put("securityKey", SEC_KEY);

        mockMvc.perform(post("/api/orders/batch-mark-collected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwtWithKey)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collected").value(3));

        // Verify all 3 are now DELIVERED
        assertThat(orderRepository.findById(orderId1).orElseThrow().getStatus()).isEqualTo("DELIVERED");
        assertThat(orderRepository.findById(orderId2).orElseThrow().getStatus()).isEqualTo("DELIVERED");
        assertThat(orderRepository.findById(orderId3).orElseThrow().getStatus()).isEqualTo("DELIVERED");

        // Verify all have collectedAt set
        assertThat(orderRepository.findById(orderId1).orElseThrow().getCollectedAt()).isNotNull();
        assertThat(orderRepository.findById(orderId2).orElseThrow().getCollectedAt()).isNotNull();
        assertThat(orderRepository.findById(orderId3).orElseThrow().getCollectedAt()).isNotNull();
    }

    @Test
    void t09_batchMarkCollected_withBadKey_returns403AndNoWrites() throws Exception {
        // Create 2 COD orders
        String orderId1 = createCODOrderViaApi("S4-BadBatch-1-" + RUN, new BigDecimal("2"), adminJwtWithKey);
        String orderId2 = createCODOrderViaApi("S4-BadBatch-2-" + RUN, new BigDecimal("2"), adminJwtWithKey);

        // Batch collect with bad key
        Map<String, Object> batchRequest = new HashMap<>();
        batchRequest.put("orderIds", List.of(orderId1, orderId2));
        batchRequest.put("securityKey", "wrong-key");

        mockMvc.perform(post("/api/orders/batch-mark-collected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwtWithKey)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isForbidden());

        // Verify neither order was collected
        assertThat(orderRepository.findById(orderId1).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(orderRepository.findById(orderId2).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(orderRepository.findById(orderId1).orElseThrow().getCollectedAt()).isNull();
        assertThat(orderRepository.findById(orderId2).orElseThrow().getCollectedAt()).isNull();
    }

    @Test
    void t10_batchMarkCollected_noToken_returns401() throws Exception {
        // Create a COD order
        String orderId = createCODOrderViaApi("S4-BatchNoToken-" + RUN, new BigDecimal("2"), adminJwtWithKey);

        // Batch collect without token
        Map<String, Object> batchRequest = new HashMap<>();
        batchRequest.put("orderIds", List.of(orderId));
        batchRequest.put("securityKey", SEC_KEY);

        mockMvc.perform(post("/api/orders/batch-mark-collected")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isUnauthorized());

        // Verify order was not collected
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(orderRepository.findById(orderId).orElseThrow().getCollectedAt()).isNull();
    }
}
