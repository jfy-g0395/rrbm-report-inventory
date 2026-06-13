package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S11 — Monthly Reports (W-8): 13 report aggregation endpoints.
 *
 * <p>Seeds two orders for the current month:
 * <ol>
 *   <li>CASH WALK_IN — 3 units × ₱200 = ₱600 (direct channel, non-pizza product)</li>
 *   <li>CASH ECOMMERCE/SHOPEE — 2 units × ₱200 = ₱400 (ecom channel)</li>
 * </ol>
 * The product carries {@code sellingTag=HOT} so the hot-selling report can be asserted.
 * Every endpoint is tested for shape + math identity (where applicable) + 401 gate.
 *
 * <p>Run command (DB up + migrated):
 * <pre>mvn test -Dtest=MonthlyReportIT</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class MonthlyReportIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private CommissionPeriodRepository commissionPeriodRepository;
    @Autowired private CommissionEntryRepository commissionEntryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long   RUN   = System.currentTimeMillis() % 100000;
    private static final String MONTH = YearMonth.now().toString(); // e.g. "2026-06"
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private User user;
    private String jwt;
    private Product product;
    private Agent agent;
    private CommissionPeriod period;

    @BeforeAll
    void seed() throws Exception {
        user = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s11r-" + RUN + "@test.rrbm.internal", "S11 Reports", "S11R-Secret!", "S11R-key");
        jwt = ITSupport.jwtFor(jwtUtil, user);

        // HOT-tagged product — hot-selling report requires sellingTag IN ('HOT','SELLING')
        product = ITSupport.seedProduct(productRepository,
                "S11R" + (RUN % 99), "S11 HOT Item " + RUN, new BigDecimal("200.00"), 500);
        product.setSellingTag("HOT");
        productRepository.save(product);

        agent  = ITSupport.seedAgent(agentRepository, "S11RA" + (RUN % 9999), "S11 R Agent", "Zone S11R");
        period = ITSupport.seedOpenPeriod(commissionPeriodRepository,
                LocalDate.now(), LocalDate.now().plusDays(30));

        // Order 1: CASH WALK_IN (direct channel) — 3 × ₱200 = ₱600
        createOrder("S11R-CASH-" + RUN, "CASH", "WALK_IN", null, 3);
        // Order 2: CASH ECOMMERCE SHOPEE — 2 × ₱200 = ₱400
        createOrder("S11R-ECOM-" + RUN, "CASH", "ECOMMERCE", "SHOPEE", 2);
    }

    private void createOrder(String customer, String payment, String source,
                              String platform, int qty) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("productId",   product.getId());
        item.put("productName", product.getName());
        item.put("quantity",    qty);
        item.put("unitPrice",   new BigDecimal("200.00"));
        item.put("warehouse",   "wh1");

        Map<String, Object> req = new HashMap<>();
        req.put("customerName", customer);
        req.put("paymentMode",  payment);
        req.put("source",       source);
        if (platform != null) req.put("ecommercePlatform", platform);
        req.put("items", List.of(item));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(MAPPER.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @AfterAll
    void clean() {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        LocalDateTime tomorrow  = LocalDateTime.now().plusDays(1);
        activityLogRepository.deleteAll();
        transactionRepository.deleteAll();
        inventoryMovementRepository.deleteAll();
        commissionEntryRepository.deleteAll();
        orderRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(o -> orderRepository.deleteById(o.getId()));
        if (period  != null) commissionPeriodRepository.deleteById(period.getId());
        if (agent   != null) agentRepository.deleteById(agent.getId());
        if (product != null) productRepository.deleteById(product.getId());
        if (user    != null) userRepository.deleteById(user.getId());
    }

    // ── GET /api/reports/insights-summary ────────────────────────────────────

    @Test
    void t01_insightsSummary_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/insights-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("totalOrders")).isTrue();
        assertThat(n.has("totalRevenue")).isTrue();
        assertThat(n.has("totalItemsSold")).isTrue();
        assertThat(n.has("totalExpenses")).isTrue();
        assertThat(n.has("prevMonth")).isTrue();
        assertThat(n.has("prevMonthOrders")).isTrue();
        assertThat(n.has("prevMonthRevenue")).isTrue();
        assertThat(n.has("topProducts")).isTrue();
        assertThat(n.get("topProducts").isArray()).isTrue();
        assertThat(n.has("dailyBreakdown")).isTrue();
        assertThat(n.get("dailyBreakdown").isArray()).isTrue();
    }

    @Test
    void t02_insightsSummary_seededOrdersReflectedInTotals() throws Exception {
        String body = mockMvc.perform(get("/api/reports/insights-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        // 2 non-cancelled, non-PENDING_COLLECTION orders with 3+2=5 items
        assertThat(n.get("totalOrders").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(n.get("totalItemsSold").asLong()).isGreaterThanOrEqualTo(5);
        // topProducts entries have required fields and are sorted by qty desc
        JsonNode top = n.get("topProducts");
        if (top.size() >= 2) {
            long firstQty  = top.get(0).get("qty").asLong();
            long secondQty = top.get(1).get("qty").asLong();
            assertThat(firstQty).isGreaterThanOrEqualTo(secondQty);
        }
        for (JsonNode p : top) {
            assertThat(p.has("name")).isTrue();
            assertThat(p.has("qty")).isTrue();
            assertThat(p.has("revenue")).isTrue();
            assertThat(p.has("rank")).isTrue();
        }
    }

    @Test
    void t03_insightsSummary_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/insights-summary?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void t04_insightsSummary_invalidMonthFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/reports/insights-summary?month=not-a-month")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/reports/accounting-summary ──────────────────────────────────

    @Test
    void t05_accountingSummary_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/accounting-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("grossSales")).isTrue();
        assertThat(n.has("refundsTotal")).isTrue();
        assertThat(n.has("adjustmentsTotal")).isTrue();
        assertThat(n.has("netSales")).isTrue();
        assertThat(n.has("dailyBreakdown")).isTrue();
        assertThat(n.get("dailyBreakdown").isArray()).isTrue();
    }

    @Test
    void t06_accountingSummary_mathIdentityHolds() throws Exception {
        String body = mockMvc.perform(get("/api/reports/accounting-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        BigDecimal grossSales  = n.get("grossSales").decimalValue();
        BigDecimal refunds     = n.get("refundsTotal").decimalValue();
        BigDecimal adjustments = n.get("adjustmentsTotal").decimalValue();
        BigDecimal netSales    = n.get("netSales").decimalValue();
        // netSales == grossSales + refundsTotal + adjustmentsTotal (always, regardless of DB contents)
        assertThat(netSales).isEqualByComparingTo(grossSales.add(refunds).add(adjustments));
    }

    @Test
    void t07_accountingSummary_grossSalesIncludesSeededOrders() throws Exception {
        String body = mockMvc.perform(get("/api/reports/accounting-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        // Both CASH orders auto-create SALE transactions: ₱600 + ₱400 = ₱1 000
        assertThat(n.get("grossSales").decimalValue())
                .isGreaterThanOrEqualTo(new BigDecimal("1000.00"));
    }

    @Test
    void t08_accountingSummary_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/accounting-summary?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/source-breakdown ────────────────────────────────────

    @Test
    void t09_sourceBreakdown_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/source-breakdown?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("totalRevenue")).isTrue();
        assertThat(n.has("breakdown")).isTrue();
        assertThat(n.get("breakdown").isArray()).isTrue();

        // WALK_IN and ECOMMERCE both present (our 2 orders)
        boolean foundWalkIn = false, foundEcom = false;
        for (JsonNode entry : n.get("breakdown")) {
            String src = entry.get("source").asText();
            if ("WALK_IN".equals(src))   foundWalkIn = true;
            if ("ECOMMERCE".equals(src)) foundEcom   = true;
            // every entry must have required fields
            assertThat(entry.has("orderCount")).isTrue();
            assertThat(entry.has("revenue")).isTrue();
            assertThat(entry.has("pct")).isTrue();
        }
        assertThat(foundWalkIn).withFailMessage("WALK_IN source should appear in breakdown").isTrue();
        assertThat(foundEcom).withFailMessage("ECOMMERCE source should appear in breakdown").isTrue();
    }

    @Test
    void t10_sourceBreakdown_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/source-breakdown?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/top-agents ───────────────────────────────────────────

    @Test
    void t11_topAgents_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/top-agents?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("agents")).isTrue();
        assertThat(n.get("agents").isArray()).isTrue();
        // Each entry must have required fields (if any agents present)
        for (JsonNode a : n.get("agents")) {
            assertThat(a.has("rank")).isTrue();
            assertThat(a.has("agentName")).isTrue();
            assertThat(a.has("source")).isTrue();
            assertThat(a.has("orderCount")).isTrue();
            assertThat(a.has("revenue")).isTrue();
        }
    }

    @Test
    void t12_topAgents_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/top-agents?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/top-dates ────────────────────────────────────────────

    @Test
    void t13_topDates_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/top-dates?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("dates")).isTrue();
        assertThat(n.get("dates").isArray()).isTrue();
        // Today has our 2 orders → must appear in top-3 dates
        assertThat(n.get("dates").size()).isGreaterThanOrEqualTo(1);
        JsonNode first = n.get("dates").get(0);
        assertThat(first.has("rank")).isTrue();
        assertThat(first.has("date")).isTrue();
        assertThat(first.has("orderCount")).isTrue();
        assertThat(first.has("revenue")).isTrue();
        assertThat(first.get("rank").asInt()).isEqualTo(1);
    }

    @Test
    void t14_topDates_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/top-dates?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/pizza-summary ───────────────────────────────────────

    @Test
    void t15_pizzaSummary_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/pizza-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("totalQty")).isTrue();
        assertThat(n.has("directQty")).isTrue();
        assertThat(n.has("ecomQty")).isTrue();
        assertThat(n.has("directRevenue")).isTrue();
        assertThat(n.has("ecomRevenue")).isTrue();
        assertThat(n.has("totalRevenue")).isTrue();
        assertThat(n.has("top5")).isTrue();
        assertThat(n.get("top5").isArray()).isTrue();
        // Channel split must sum to total
        long direct = n.get("directQty").asLong();
        long ecom   = n.get("ecomQty").asLong();
        long total  = n.get("totalQty").asLong();
        assertThat(direct + ecom).isEqualTo(total);
    }

    @Test
    void t16_pizzaSummary_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/pizza-summary?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/non-pizza-summary ────────────────────────────────────

    @Test
    void t17_nonPizzaSummary_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/non-pizza-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("directQty")).isTrue();
        assertThat(n.has("ecomQty")).isTrue();
        assertThat(n.has("totalQty")).isTrue();
        assertThat(n.has("totalRevenue")).isTrue();
        assertThat(n.has("topProducts")).isTrue();
        assertThat(n.get("topProducts").isArray()).isTrue();
        // Channel split must sum to total
        long direct = n.get("directQty").asLong();
        long ecom   = n.get("ecomQty").asLong();
        long total  = n.get("totalQty").asLong();
        assertThat(direct + ecom).isEqualTo(total);
    }

    @Test
    void t18_nonPizzaSummary_seededNonPizzaItemsAppearInTotals() throws Exception {
        String body = mockMvc.perform(get("/api/reports/non-pizza-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        // Product name "S11 HOT Item …" does not contain "Pizza Box" → it's non-pizza
        // 3 (WALK_IN) + 2 (ECOMMERCE) = 5 non-pizza items; totalQty & revenue include real data too
        assertThat(n.get("totalQty").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(n.get("totalRevenue").decimalValue())
                .isGreaterThanOrEqualTo(new BigDecimal("1000.00"));
        // topProducts entries have required fields and are sorted by totalQty desc
        JsonNode top = n.get("topProducts");
        if (top.size() >= 2) {
            long firstQty  = top.get(0).get("totalQty").asLong();
            long secondQty = top.get(1).get("totalQty").asLong();
            assertThat(firstQty).isGreaterThanOrEqualTo(secondQty);
        }
        for (JsonNode p : top) {
            assertThat(p.has("productName")).isTrue();
            assertThat(p.has("directQty")).isTrue();
            assertThat(p.has("ecomQty")).isTrue();
            assertThat(p.has("totalQty")).isTrue();
            assertThat(p.has("revenue")).isTrue();
        }
    }

    @Test
    void t19_nonPizzaSummary_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/non-pizza-summary?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/daily-order-summary ──────────────────────────────────

    @Test
    void t20_dailyOrderSummary_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/daily-order-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("days")).isTrue();
        assertThat(n.get("days").isArray()).isTrue();
        // Today has our 2 orders → at least one day row
        assertThat(n.get("days").size()).isGreaterThanOrEqualTo(1);
        JsonNode today = n.get("days").get(0);
        assertThat(today.has("date")).isTrue();
        assertThat(today.has("directOrders")).isTrue();
        assertThat(today.has("ecomOrders")).isTrue();
        assertThat(today.has("totalOrders")).isTrue();
        assertThat(today.has("pizzaBoxQty")).isTrue();
        assertThat(today.has("revenue")).isTrue();
    }

    @Test
    void t21_dailyOrderSummary_todayReflectsSeededOrders() throws Exception {
        String body = mockMvc.perform(get("/api/reports/daily-order-summary?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        String todayStr = LocalDate.now().toString();
        JsonNode todayRow = null;
        for (JsonNode day : n.get("days")) {
            if (todayStr.equals(day.get("date").asText())) {
                todayRow = day;
                break;
            }
        }
        assertThat(todayRow).withFailMessage("Today's row must exist in daily-order-summary").isNotNull();
        assertThat(todayRow.get("directOrders").asLong()).isGreaterThanOrEqualTo(1); // WALK_IN
        assertThat(todayRow.get("ecomOrders").asLong()).isGreaterThanOrEqualTo(1);   // ECOMMERCE
        assertThat(todayRow.get("totalOrders").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void t22_dailyOrderSummary_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/daily-order-summary?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/hot-selling ─────────────────────────────────────────

    @Test
    void t23_hotSelling_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/hot-selling?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("items")).isTrue();
        assertThat(n.get("items").isArray()).isTrue();
        for (JsonNode item : n.get("items")) {
            assertThat(item.has("rank")).isTrue();
            assertThat(item.has("productName")).isTrue();
            assertThat(item.has("sellingTag")).isTrue();
            assertThat(item.has("qty")).isTrue();
            assertThat(item.has("revenue")).isTrue();
        }
    }

    @Test
    void t24_hotSelling_allReturnedItemsHaveValidSellingTag() throws Exception {
        String body = mockMvc.perform(get("/api/reports/hot-selling?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        // The shared DB has real HOT/SELLING products; the report must be non-empty
        assertThat(n.get("items").size()).isGreaterThanOrEqualTo(1);
        // Every returned item must carry a valid sellingTag and be sorted by qty desc
        long prevQty = Long.MAX_VALUE;
        for (JsonNode item : n.get("items")) {
            String tag = item.get("sellingTag").asText();
            assertThat(tag).isIn("HOT", "SELLING");
            long qty = item.get("qty").asLong();
            assertThat(qty).isLessThanOrEqualTo(prevQty); // sorted desc
            prevQty = qty;
        }
    }

    @Test
    void t25_hotSelling_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/hot-selling?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/delivery-fees ───────────────────────────────────────

    @Test
    void t26_deliveryFees_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/delivery-fees?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("totalFees")).isTrue();
        assertThat(n.has("orderCount")).isTrue();
        assertThat(n.has("orders")).isTrue();
        assertThat(n.get("orders").isArray()).isTrue();
        assertThat(n.get("totalFees").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        // orderCount matches array length
        assertThat(n.get("orderCount").asInt()).isEqualTo(n.get("orders").size());
    }

    @Test
    void t27_deliveryFees_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/delivery-fees?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/expense-breakdown ───────────────────────────────────

    @Test
    void t28_expenseBreakdown_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/expense-breakdown?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("grandTotal")).isTrue();
        assertThat(n.has("breakdown")).isTrue();
        assertThat(n.get("breakdown").isArray()).isTrue();
        assertThat(n.get("grandTotal").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        for (JsonNode entry : n.get("breakdown")) {
            assertThat(entry.has("description")).isTrue();
            assertThat(entry.has("totalAmount")).isTrue();
            assertThat(entry.has("count")).isTrue();
            assertThat(entry.has("pct")).isTrue();
        }
    }

    @Test
    void t29_expenseBreakdown_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/expense-breakdown?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/ecommerce-breakdown ──────────────────────────────────

    @Test
    void t30_ecommerceBreakdown_currentMonth_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/ecommerce-breakdown?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.has("totalOrders")).isTrue();
        assertThat(n.has("totalRevenue")).isTrue();
        assertThat(n.has("totalItems")).isTrue();
        assertThat(n.has("avgOrder")).isTrue();
        assertThat(n.has("platforms")).isTrue();
        assertThat(n.get("platforms").isArray()).isTrue();
        for (JsonNode plat : n.get("platforms")) {
            assertThat(plat.has("platform")).isTrue();
            assertThat(plat.has("orderCount")).isTrue();
            assertThat(plat.has("revenue")).isTrue();
            assertThat(plat.has("topProducts")).isTrue();
        }
    }

    @Test
    void t31_ecommerceBreakdown_seededShopeeOrderReflected() throws Exception {
        String body = mockMvc.perform(get("/api/reports/ecommerce-breakdown?month=" + MONTH)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        // Our SHOPEE ECOMMERCE order: 2 × ₱200 = ₱400
        assertThat(n.get("totalOrders").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(n.get("totalRevenue").doubleValue()).isGreaterThanOrEqualTo(400.0);

        boolean foundShopee = false;
        for (JsonNode plat : n.get("platforms")) {
            if ("SHOPEE".equals(plat.get("platform").asText())) {
                foundShopee = true;
                assertThat(plat.get("orderCount").asLong()).isGreaterThanOrEqualTo(1);
                break;
            }
        }
        assertThat(foundShopee).withFailMessage("SHOPEE should appear in ecommerce-breakdown platforms").isTrue();
    }

    @Test
    void t32_ecommerceBreakdown_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/ecommerce-breakdown?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/reports/daily-reports-list ──────────────────────────────────

    @Test
    void t33_dailyReportsList_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/reports/daily-reports-list")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.has("reports")).isTrue();
        assertThat(n.get("reports").isArray()).isTrue();
        assertThat(n.has("total")).isTrue();
        // total field must equal the array length
        assertThat(n.get("total").asInt()).isEqualTo(n.get("reports").size());
        for (JsonNode r : n.get("reports")) {
            assertThat(r.has("reportDate")).isTrue();
            assertThat(r.has("closedBy")).isTrue();
            assertThat(r.has("closedByName")).isTrue();
            assertThat(r.has("totalOrders")).isTrue();
            assertThat(r.has("grossSales")).isTrue();
            assertThat(r.has("netSales")).isTrue();
        }
    }

    @Test
    void t34_dailyReportsList_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/daily-reports-list"))
                .andExpect(status().isUnauthorized());
    }
}
