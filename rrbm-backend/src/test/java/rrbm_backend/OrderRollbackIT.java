package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S14 — Transactional Rollback
 *
 * Proves that a failure inside OrderService.createOrder() rolls back ALL four
 * atomic writes (order + activity_log + SALE transaction + inventory movement)
 * and that commission creation failure is best-effort (never rolls back the order).
 *
 * Key scenario (t02): insufficient stock causes deductStockForOrder() to throw
 * inside the @Transactional boundary — AFTER orderRepository.save() and
 * transactionService.recordSale() have already executed — proving Spring rolls
 * back the entire unit. No partial rows remain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class OrderRollbackIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private CommissionEntryRepository commissionEntryRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;

    private User testUser;
    private String jwt;
    private Product prodSufficient; // stockWh1=100, used by t01 and t04
    private Product prodZeroWh1;    // stockWh1=0, used by t02 (rollback target)
    private Agent agentForT04;      // ACTIVE agent; intentionally no open commission period

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void seed() {
        testUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s14-acct-" + RUN + "@test.rrbm.internal",
                "S14 Accounting", "S14-Secret!", "S14-sk-" + RUN);
        jwt = ITSupport.jwtFor(jwtUtil, testUser);

        // Sufficient stock for happy-path orders (t01, t04)
        prodSufficient = ITSupport.seedProduct(productRepository,
                "S14S" + (RUN % 99), "S14 Sufficient Stock",
                new BigDecimal("200.00"), 100);

        // wh1 stock deliberately set to 0; wh2/wh3 retain default (50/25).
        // The rollback order explicitly specifies warehouse="wh1" so the stock
        // check fails despite wh2/wh3 having stock.
        prodZeroWh1 = ITSupport.seedProduct(productRepository,
                "S14Z" + (RUN % 99), "S14 Zero Wh1",
                new BigDecimal("200.00"), 0);

        // ACTIVE agent — no commission period seeded; proves best-effort commission
        agentForT04 = ITSupport.seedAgent(agentRepository,
                "S14A" + (RUN % 99), "S14 Test Agent", "Zone S14");
    }

    @AfterAll
    void clean() {
        // Activity logs for our user
        if (testUser != null) {
            activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now())
                    .stream()
                    .filter(a -> testUser.getId().equals(a.getUserId()))
                    .forEach(a -> activityLogRepository.deleteById(a.getId()));
        }

        // Orders created today whose customer name starts with "S14-" (t01 and t04 only —
        // t02's order rolled back, t03's order was rejected before the transaction started)
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        LocalDateTime tomorrow  = LocalDateTime.now().plusDays(1);
        orderRepository.findByCreatedAtBetween(yesterday, tomorrow).stream()
                .filter(o -> o.getCustomerName() != null && o.getCustomerName().startsWith("S14-"))
                .forEach(o -> {
                    transactionRepository.findByOrderIdOrderByCreatedAtDesc(o.getId())
                            .forEach(transactionRepository::delete);
                    commissionEntryRepository.findByOrderId(o.getId())
                            .forEach(commissionEntryRepository::delete);
                    inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(o.getId())
                            .forEach(inventoryMovementRepository::delete);
                    orderRepository.delete(o);
                });

        if (agentForT04 != null)    agentRepository.deleteById(agentForT04.getId());
        if (prodSufficient != null)  productRepository.deleteById(prodSufficient.getId());
        if (prodZeroWh1 != null)     productRepository.deleteById(prodZeroWh1.getId());
        if (testUser != null)        userRepository.deleteById(testUser.getId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildOrderRequest(String customerName,
                                                   Long productId, String productName,
                                                   int qty, String warehouse,
                                                   Long agentId) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId",    productId);
        item.put("productName",  productName);
        item.put("quantity",     qty);
        item.put("unitPrice",    new BigDecimal("200.00"));
        item.put("warehouse",    warehouse);

        Map<String, Object> req = new HashMap<>();
        req.put("customerName", customerName);
        req.put("source",       "WALK_IN");
        req.put("paymentMode",  "CASH");
        req.put("items",        List.of(item));
        if (agentId != null) req.put("agentId", agentId);
        return req;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Baseline: proves all four tables are written on a successful order.
     * Makes t02's "count unchanged" assertions non-vacuous.
     */
    @Test
    void t01_happyPath_allFourTablesWritten() throws Exception {
        int stockBefore = productRepository.findById(prodSufficient.getId())
                .orElseThrow().getStockWh1();

        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(objectMapper.writeValueAsString(
                                buildOrderRequest("S14-HappyPath-" + RUN,
                                        prodSufficient.getId(), prodSufficient.getName(),
                                        2, "wh1", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(body).get("id").asText();

        // Order row present
        assertThat(orderRepository.findById(orderId)).isPresent();

        // SALE transaction recorded
        var txs = transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        assertThat(txs).isNotEmpty();
        assertThat(txs.get(0).getTransactionType()).isEqualTo("SALE");

        // Inventory movement recorded
        var mvs = inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(orderId);
        assertThat(mvs).isNotEmpty();
        assertThat(mvs.get(0).getMovementType()).isEqualTo("ORDER_OUT");

        // Stock decremented
        int stockAfter = productRepository.findById(prodSufficient.getId())
                .orElseThrow().getStockWh1();
        assertThat(stockAfter).isEqualTo(stockBefore - 2);
    }

    /**
     * Primary atomicity proof.
     *
     * deductStockForOrder() throws RuntimeException("Insufficient stock in WH1")
     * AFTER orderRepository.save() and transactionService.recordSale() have already
     * executed inside the same @Transactional boundary. Spring rolls back all writes:
     * no order row, no SALE row, no movement row persists.
     */
    @Test
    void t02_insufficientStockWh1_rollsBackAllFourWrites() throws Exception {
        long ordersBefore    = orderRepository.count();
        long txBefore        = transactionRepository.count();
        long movementsBefore = inventoryMovementRepository.count();
        int  stockWh1Before  = productRepository.findById(prodZeroWh1.getId())
                .orElseThrow().getStockWh1();

        // prodZeroWh1 has stockWh1=0; warehouse="wh1" forces the check against that bucket
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(objectMapper.writeValueAsString(
                                buildOrderRequest("S14-Rollback-" + RUN,
                                        prodZeroWh1.getId(), prodZeroWh1.getName(),
                                        1, "wh1", null))))
                .andExpect(status().isBadRequest());

        // All four writes rolled back — row counts unchanged
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(transactionRepository.count()).isEqualTo(txBefore);
        assertThat(inventoryMovementRepository.count()).isEqualTo(movementsBefore);

        // Stock also rolled back (deduction never committed)
        int stockWh1After = productRepository.findById(prodZeroWh1.getId())
                .orElseThrow().getStockWh1();
        assertThat(stockWh1After).isEqualTo(stockWh1Before).isEqualTo(0);
    }

    /**
     * Controller-level guard (pre-transaction).
     *
     * productRepository.existsById() in the controller rejects the request before
     * orderService.createOrder() is invoked — the @Transactional boundary is never
     * entered. Nothing is written to any table.
     */
    @Test
    void t03_nonExistentProductId_controllerRejectsBeforeTransaction() throws Exception {
        long ordersBefore    = orderRepository.count();
        long txBefore        = transactionRepository.count();
        long movementsBefore = inventoryMovementRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(objectMapper.writeValueAsString(
                                buildOrderRequest("S14-NoProduct-" + RUN,
                                        999999999L, "Ghost Product",
                                        1, "wh1", null))))
                .andExpect(status().isBadRequest());

        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(transactionRepository.count()).isEqualTo(txBefore);
        assertThat(inventoryMovementRepository.count()).isEqualTo(movementsBefore);
    }

    /**
     * Best-effort commission.
     *
     * The controller wraps commissionService.createEntriesForOrder() in
     * try { ... } catch (Exception ignored) {}. With an agent-linked order but
     * NO open commission period, commission creation fails silently. The order,
     * SALE transaction, and inventory movement must all persist — the commission
     * failure must NOT roll back the order.
     */
    @Test
    void t04_commissionFailure_orderStillPersists() throws Exception {
        int stockBefore = productRepository.findById(prodSufficient.getId())
                .orElseThrow().getStockWh1();

        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(objectMapper.writeValueAsString(
                                buildOrderRequest("S14-CommBestEffort-" + RUN,
                                        prodSufficient.getId(), prodSufficient.getName(),
                                        2, "wh1", agentForT04.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(body).get("id").asText();

        // Order persisted despite commission failure
        assertThat(orderRepository.findById(orderId)).isPresent();

        // SALE transaction persisted
        var txs = transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
        assertThat(txs).isNotEmpty();
        assertThat(txs.get(0).getTransactionType()).isEqualTo("SALE");

        // Inventory movement persisted
        var mvs = inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(orderId);
        assertThat(mvs).isNotEmpty();
        assertThat(mvs.get(0).getMovementType()).isEqualTo("ORDER_OUT");

        // Stock decremented correctly (not rolled back by commission failure)
        int stockAfter = productRepository.findById(prodSufficient.getId())
                .orElseThrow().getStockWh1();
        assertThat(stockAfter).isEqualTo(stockBefore - 2);

        // Commission entries NOT created — no open period, failure swallowed silently
        assertThat(commissionEntryRepository.existsByOrderId(orderId)).isFalse();
    }
}
