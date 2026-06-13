package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S8 / W-16 — Ledger Adjustments & Transaction Reads
 *
 * <p>Workflow: create a real SALE via POST /api/orders → post a manual ADJUSTMENT
 * against that order → assert the ADJUSTMENT row is persisted; verify the ledger
 * read endpoints return correct totals and math.
 *
 * <p>Scenarios covered:
 * - POST /api/transactions/adjustment (order-linked) → 201, ADJUSTMENT row + correct fields
 * - POST adjustment standalone (no orderId) → 201, orderId null in DB
 * - POST adjustment, no token → 401, no new row
 * - POST adjustment, missing amount → 400, no new row
 * - POST adjustment, invalid amount format → 400, no new row
 * - GET /order/{orderId} → 200, SALE + ADJUSTMENT in array
 * - GET /order/{orderId} no token → 401
 * - GET /date-range today → 200, includes our SALE and ADJUSTMENT
 * - GET /date-range no token → 401
 * - GET /accounting-summary today → 200, correct grossSales + adjustmentsTotal + netSales math
 * - GET /accounting-summary no token → 401
 * - GET /ledger/report today → 200, SALE and ADJUSTMENT in breakdown; netSales identity holds
 * - GET /ledger/report no token → 401
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class TransactionLedgerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private CommissionPeriodRepository commissionPeriodRepository;
    @Autowired private CommissionEntryRepository commissionEntryRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN    = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S8-Secret!";
    private static final String SEC_KEY  = "S8-admin-key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private User user;
    private String jwt;
    private Product product;
    private Agent agent;
    private CommissionPeriod period;

    // Seeded via real POST /api/orders; String PK (DDMMYY-NNNNNN format)
    private String orderId;

    // IDs of adjustment rows created during tests (tracked for clean @AfterAll)
    private Long linkedAdjId;     // t01: order-linked ADJUSTMENT
    private Long standaloneAdjId; // t02: standalone ADJUSTMENT (no orderId)

    @BeforeAll
    void seed() throws Exception {
        user = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s8-acct-" + RUN + "@test.rrbm.internal", "S8 Accounting", PASSWORD, SEC_KEY);
        jwt = ITSupport.jwtFor(jwtUtil, user);

        product = ITSupport.seedProduct(productRepository,
                "S8P" + (RUN % 99), "S8 Product", new BigDecimal("500.00"), 200);

        agent = ITSupport.seedAgent(agentRepository,
                "S8A" + (RUN % 9999), "S8 Agent", "Zone S8");

        period = ITSupport.seedOpenPeriod(commissionPeriodRepository,
                LocalDate.now(), LocalDate.now().plusDays(30));

        // Arrive via real workflow: POST /api/orders → SALE transaction auto-created
        orderId = createOrderViaApi();
    }

    @AfterAll
    void clean() {
        // Activity log entries for our user today
        activityLogRepository
                .findByUserIdAndReportDateOrderByCreatedAtDesc(user.getId(), LocalDate.now())
                .forEach(a -> activityLogRepository.deleteById(a.getId()));

        // All transactions tied to our order (SALE + any linked ADJUSTMENT from t01)
        if (orderId != null) {
            transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                    .forEach(t -> transactionRepository.delete(t));
            commissionEntryRepository.findByOrderId(orderId)
                    .forEach(ce -> commissionEntryRepository.delete(ce));
            inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(orderId)
                    .forEach(im -> inventoryMovementRepository.delete(im));
            // CascadeType.ALL on items — deletes order_items automatically
            orderRepository.findByCreatedAtBetween(
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1))
                    .stream()
                    .filter(o -> orderId.equals(o.getId()))
                    .findFirst()
                    .ifPresent(orderRepository::delete);
        }

        // Standalone adjustment has no orderId → must be deleted explicitly
        if (standaloneAdjId != null) {
            transactionRepository.deleteById(standaloneAdjId);
        }

        if (period  != null) commissionPeriodRepository.deleteById(period.getId());
        if (agent   != null) agentRepository.deleteById(agent.getId());
        if (product != null) productRepository.deleteById(product.getId());
        if (user    != null) userRepository.deleteById(user.getId());
    }

    // ─── POST /api/transactions/adjustment ───────────────────────────────────

    @Test
    void t01_adjustment_withOrderReference_returns201AndPersistsRow() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("orderId", orderId);
        body.put("amount", "-250.00");
        body.put("reason", "Manual correction S8 test run " + RUN);

        MvcResult result = mockMvc.perform(post("/api/transactions/adjustment")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode resp = MAPPER.readTree(result.getResponse().getContentAsString());
        linkedAdjId = resp.get("id").asLong();

        Transaction saved = transactionRepository.findById(linkedAdjId).orElseThrow();
        assertThat(saved.getTransactionType()).isEqualTo("ADJUSTMENT");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("-250.00"));
        assertThat(saved.getOrderId()).isEqualTo(orderId);
        assertThat(saved.getNotes()).contains("Manual correction S8 test run");
        assertThat(saved.getCreatedBy()).isEqualTo(user.getId());
        assertThat(saved.getEffectiveDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void t02_adjustment_standaloneNoOrderId_returns201AndOrderIdIsNull() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("amount", "100.00");
        body.put("reason", "Standalone upward adjustment S8-" + RUN);

        MvcResult result = mockMvc.perform(post("/api/transactions/adjustment")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode resp = MAPPER.readTree(result.getResponse().getContentAsString());
        standaloneAdjId = resp.get("id").asLong();

        Transaction saved = transactionRepository.findById(standaloneAdjId).orElseThrow();
        assertThat(saved.getTransactionType()).isEqualTo("ADJUSTMENT");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(saved.getOrderId()).isNull();
    }

    @Test
    void t03_adjustment_noToken_returns401_noNewRow() throws Exception {
        long countBefore = transactionRepository.count();

        mockMvc.perform(post("/api/transactions/adjustment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("amount", "-50.00"))))
                .andExpect(status().isUnauthorized());

        assertThat(transactionRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void t04_adjustment_missingAmount_returns400_noNewRow() throws Exception {
        long countBefore = transactionRepository.count();

        Map<String, String> body = Map.of("orderId", orderId, "reason", "missing amount field");

        mockMvc.perform(post("/api/transactions/adjustment")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertThat(transactionRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void t05_adjustment_invalidAmountFormat_returns400_noNewRow() throws Exception {
        long countBefore = transactionRepository.count();

        Map<String, String> body = Map.of("amount", "not-a-number", "reason", "bad format");

        mockMvc.perform(post("/api/transactions/adjustment")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertThat(transactionRepository.count()).isEqualTo(countBefore);
    }

    // ─── GET /api/transactions/order/{orderId} ───────────────────────────────

    @Test
    void t06_getByOrder_existingOrder_returnsSaleAndAdjustmentEntries() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/transactions/order/" + orderId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode arr = MAPPER.readTree(result.getResponse().getContentAsString());
        // At least SALE (from order create) + ADJUSTMENT (from t01)
        assertThat(arr.size()).isGreaterThanOrEqualTo(2);

        boolean hasSale = false;
        boolean hasAdj  = false;
        for (JsonNode node : arr) {
            String type = node.get("transactionType").asText();
            if ("SALE".equals(type))       hasSale = true;
            if ("ADJUSTMENT".equals(type)) hasAdj  = true;
        }
        assertThat(hasSale).as("SALE transaction must be present for this order").isTrue();
        assertThat(hasAdj).as("ADJUSTMENT from t01 must be present").isTrue();
    }

    @Test
    void t07_getByOrder_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/transactions/order/" + orderId))
                .andExpect(status().isUnauthorized());
    }

    // ─── GET /api/transactions/date-range ────────────────────────────────────

    @Test
    void t08_getByDateRange_today_includesOurSaleTransaction() throws Exception {
        String today = LocalDate.now().toString();

        MvcResult result = mockMvc.perform(
                        get("/api/transactions/date-range?start=" + today + "&end=" + today)
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode arr = MAPPER.readTree(result.getResponse().getContentAsString());

        boolean foundSale = false;
        for (JsonNode node : arr) {
            if ("SALE".equals(node.get("transactionType").asText())
                    && orderId.equals(node.get("orderId").asText())) {
                foundSale = true;
                break;
            }
        }
        assertThat(foundSale)
                .as("SALE for our order must appear in today's date-range response").isTrue();
    }

    @Test
    void t09_getByDateRange_noToken_returns401() throws Exception {
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/api/transactions/date-range?start=" + today + "&end=" + today))
                .andExpect(status().isUnauthorized());
    }

    // ─── GET /api/transactions/accounting-summary ────────────────────────────

    @Test
    void t10_getAccountingSummary_today_returnsCorrectAggregatesAndMathIdentity() throws Exception {
        String today = LocalDate.now().toString();

        MvcResult result = mockMvc.perform(
                        get("/api/transactions/accounting-summary?date=" + today)
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(today))
                .andExpect(jsonPath("$.grossSales").isNumber())
                .andExpect(jsonPath("$.refundsTotal").isNumber())
                .andExpect(jsonPath("$.adjustmentsTotal").isNumber())
                .andExpect(jsonPath("$.netSales").isNumber())
                .andExpect(jsonPath("$.totalTransactions").isNumber())
                .andReturn();

        JsonNode resp = MAPPER.readTree(result.getResponse().getContentAsString());
        BigDecimal gross   = new BigDecimal(resp.get("grossSales").asText());
        BigDecimal refunds = new BigDecimal(resp.get("refundsTotal").asText());
        BigDecimal adjs    = new BigDecimal(resp.get("adjustmentsTotal").asText());
        BigDecimal net     = new BigDecimal(resp.get("netSales").asText());
        long count         = resp.get("totalTransactions").asLong();

        // Our order was 2 × ₱500 = ₱1000; grossSales must be at least that
        assertThat(gross).isGreaterThanOrEqualTo(new BigDecimal("1000.00"));

        // Accounting identity: netSales = grossSales + refundsTotal + adjustmentsTotal
        assertThat(net).isEqualByComparingTo(gross.add(refunds).add(adjs));

        // At minimum: SALE from order + ADJUSTMENT (t01) + ADJUSTMENT (t02) = 3
        assertThat(count).isGreaterThanOrEqualTo(3);
    }

    @Test
    void t11_getAccountingSummary_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/transactions/accounting-summary?date=" + LocalDate.now()))
                .andExpect(status().isUnauthorized());
    }

    // ─── GET /api/transactions/ledger/report ─────────────────────────────────

    @Test
    void t12_getLedgerReport_today_breakdownIncludesSaleAndAdjustment() throws Exception {
        String today = LocalDate.now().toString();

        MvcResult result = mockMvc.perform(
                        get("/api/transactions/ledger/report?start=" + today + "&end=" + today)
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value(today))
                .andExpect(jsonPath("$.endDate").value(today))
                .andExpect(jsonPath("$.grossSales").isNumber())
                .andExpect(jsonPath("$.adjustmentsTotal").isNumber())
                .andExpect(jsonPath("$.netSales").isNumber())
                .andExpect(jsonPath("$.breakdown").isArray())
                .andReturn();

        JsonNode resp      = MAPPER.readTree(result.getResponse().getContentAsString());
        JsonNode breakdown = resp.get("breakdown");

        boolean hasSale = false;
        boolean hasAdj  = false;
        for (JsonNode entry : breakdown) {
            String type = entry.get("type").asText();
            if ("SALE".equals(type))       hasSale = true;
            if ("ADJUSTMENT".equals(type)) hasAdj  = true;
        }
        assertThat(hasSale).as("SALE must appear in ledger report breakdown").isTrue();
        assertThat(hasAdj).as("ADJUSTMENT must appear in ledger report breakdown").isTrue();

        // Net sales identity: grossSales + voidTotal + returnTotal + adjustmentsTotal
        BigDecimal gross   = new BigDecimal(resp.get("grossSales").asText());
        BigDecimal voids   = new BigDecimal(resp.get("voidTotal").asText());
        BigDecimal returns = new BigDecimal(resp.get("returnTotal").asText());
        BigDecimal adjs    = new BigDecimal(resp.get("adjustmentsTotal").asText());
        BigDecimal net     = new BigDecimal(resp.get("netSales").asText());
        assertThat(net).isEqualByComparingTo(gross.add(voids).add(returns).add(adjs));
    }

    @Test
    void t13_getLedgerReport_noToken_returns401() throws Exception {
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/api/transactions/ledger/report?start=" + today + "&end=" + today))
                .andExpect(status().isUnauthorized());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String createOrderViaApi() throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("productId",   product.getId());
        item.put("productName", product.getName());
        item.put("quantity",    2);
        item.put("unitPrice",   new BigDecimal("500.00"));
        item.put("warehouse",   "wh1");

        Map<String, Object> payload = new HashMap<>();
        payload.put("customerName", "S8-Customer-" + RUN);
        payload.put("source",       "WALK_IN");
        payload.put("paymentMode",  "CASH");
        payload.put("agentId",      agent.getId());
        payload.put("items",        List.of(item));

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(payload)))
                .andExpect(status().is2xxSuccessful())
                .andReturn();

        JsonNode resp = MAPPER.readTree(result.getResponse().getContentAsString());
        return resp.get("id").asText();
    }
}
