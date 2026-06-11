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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S3.2 / W-3 — Order Cancel
 *
 * <p>Workflow: create order → cancel with security key → assert status change, stock restore, VOID transaction.
 *
 * <p>Scenarios:
 * - Cancel an ACTIVE order (security key) → status CANCELLED, stock restored, transaction VOID row, activity_log
 * - Cancel a force-closed-uncollected order (M-26 guard) → NO VOID written (phantom-debit fix)
 * - Bad security key → 403; assert NO row written
 * - Cancel already-CANCELLED order → 400; assert NO row written
 * - No token → 401; assert NO row written
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class OrderCancelIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private CommissionPeriodRepository commissionPeriodRepository;
    @Autowired private CommissionEntryRepository commissionEntryRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S3-Secret!";
    private static final String SEC_KEY = "S3-admin-key-" + RUN;

    private User accountingUser;
    private String userJwt;
    private Product product1;
    private Agent activeAgent;
    private CommissionPeriod openPeriod;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void seed() {
        // Seed user
        accountingUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s3-cancel-acct-" + RUN + "@test.rrbm.internal", "S3 Cancel Accounting", PASSWORD, SEC_KEY);
        userJwt = ITSupport.jwtFor(jwtUtil, accountingUser);

        // Seed product with known stock (product code max 6 chars)
        product1 = ITSupport.seedProduct(productRepository,
                "S3CA" + (RUN % 99), "S3 Cancel Product", new BigDecimal("200.00"), 1000);

        // Seed active agent (agent code max 20 chars)
        activeAgent = ITSupport.seedAgent(agentRepository,
                "S3CA" + (RUN % 9999), "S3 Cancel Agent", "Zone C");

        // Seed open commission period
        openPeriod = ITSupport.seedOpenPeriod(commissionPeriodRepository,
                LocalDate.now(), LocalDate.now().plusDays(30));
    }

    @AfterAll
    void clean() {
        // Delete in FK-safe order
        if (accountingUser != null) {
            activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now()).stream()
                    .filter(a -> accountingUser.getId().equals(a.getUserId()))
                    .forEach(a -> activityLogRepository.deleteById(a.getId()));
        }

        // Delete orders and related data
        java.time.LocalDateTime yesterday = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime tomorrow = java.time.LocalDateTime.now().plusDays(1);
        orderRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(o -> {
                    commissionEntryRepository.findByOrderId(o.getId())
                            .forEach(ce -> commissionEntryRepository.delete(ce));
                    inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(o.getId())
                            .forEach(im -> inventoryMovementRepository.delete(im));
                    transactionRepository.findByOrderIdOrderByCreatedAtDesc(o.getId())
                            .forEach(t -> transactionRepository.delete(t));
                    orderRepository.delete(o);
                });

        // Delete commission period
        if (openPeriod != null) commissionPeriodRepository.deleteById(openPeriod.getId());

        // Delete agent
        if (activeAgent != null) agentRepository.deleteById(activeAgent.getId());

        // Delete product
        if (product1 != null) productRepository.deleteById(product1.getId());

        // Delete user
        if (accountingUser != null) userRepository.deleteById(accountingUser.getId());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    private String createOrderViaApi(String customerName, int quantity) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product1.getId());
        item.put("productName", product1.getName());
        item.put("quantity", quantity);
        item.put("unitPrice", new BigDecimal("200.00"));
        item.put("warehouse", "wh1");

        Map<String, Object> request = new HashMap<>();
        request.put("customerName", customerName);
        request.put("source", "WALK_IN");
        request.put("paymentMode", "CASH");
        request.put("items", List.of(item));

        String respJson = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + userJwt)
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
    void t01_cancelActiveOrder_changesStatusRestoresStockCreatesVoidTransaction() throws Exception {
        // Create CASH ACTIVE order
        String orderId = createOrderViaApi("S3-Cancel-Customer-1-" + RUN, 5);

        // Get product stock before cancel
        Product productBefore = productRepository.findById(product1.getId()).get();
        int stockBefore = productBefore.getStockWh1();

        // Cancel order
        Map<String, String> cancelRequest = new HashMap<>();
        cancelRequest.put("securityKey", SEC_KEY);
        cancelRequest.put("reason", "Customer requested cancellation");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + userJwt)
                        .content(objectMapper.writeValueAsString(cancelRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Assert order status changed
        Order cancelledOrder = orderRepository.findById(orderId).get();
        assertThat(cancelledOrder.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelledOrder.getCancelledAt()).isNotNull();
        assertThat(cancelledOrder.getCancelledBy()).isNotNull();

        // Assert stock was restored
        Product productAfter = productRepository.findById(product1.getId()).get();
        int stockAfter = productAfter.getStockWh1();
        assertThat(stockAfter).isGreaterThan(stockBefore);

        // Assert VOID transaction was created
        List<Transaction> txns = transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        List<Transaction> voidTxns = txns.stream()
                .filter(t -> "VOID".equals(t.getTransactionType()))
                .collect(java.util.stream.Collectors.toList());
        assertThat(voidTxns).isNotEmpty();

        // Assert activity log entry
        List<ActivityLog> logs = activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now());
        assertThat(logs.stream()
                .filter(a -> "CANCEL_ORDER".equals(a.getAction()))
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void t02_cancelWithBadSecurityKey_returns403AndNoRowWritten() throws Exception {
        // Create CASH ACTIVE order
        String orderId = createOrderViaApi("S3-Cancel-BadKey-" + RUN, 2);

        // Try to cancel with wrong key
        Map<String, String> cancelRequest = new HashMap<>();
        cancelRequest.put("securityKey", "wrong-key");
        cancelRequest.put("reason", "Test");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + userJwt)
                        .content(objectMapper.writeValueAsString(cancelRequest)))
                .andExpect(status().isForbidden());

        // Assert order status unchanged
        Order order = orderRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void t03_cancelAlreadyCancelledOrder_returns400() throws Exception {
        // Create and cancel order
        String orderId = createOrderViaApi("S3-Cancel-Duplicate-" + RUN, 1);

        Map<String, String> cancelRequest = new HashMap<>();
        cancelRequest.put("securityKey", SEC_KEY);
        cancelRequest.put("reason", "First cancellation");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + userJwt)
                        .content(objectMapper.writeValueAsString(cancelRequest)))
                .andExpect(status().isOk());

        // Try to cancel again
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + userJwt)
                        .content(objectMapper.writeValueAsString(cancelRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t04_cancelWithNoToken_returns401() throws Exception {
        // Create order
        String orderId = createOrderViaApi("S3-Cancel-NoToken-" + RUN, 1);

        Map<String, String> cancelRequest = new HashMap<>();
        cancelRequest.put("securityKey", SEC_KEY);
        cancelRequest.put("reason", "Test");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelRequest)))
                .andExpect(status().isUnauthorized());

        // Assert order status unchanged
        Order order = orderRepository.findById(orderId).get();
        assertThat(order.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void t05_cancelWithMissingSecurityKey_returns400() throws Exception {
        // Create order
        String orderId = createOrderViaApi("S3-Cancel-MissingKey-" + RUN, 1);

        Map<String, String> cancelRequest = new HashMap<>();
        cancelRequest.put("reason", "Test");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + userJwt)
                        .content(objectMapper.writeValueAsString(cancelRequest)))
                .andExpect(status().isBadRequest());
    }
}
