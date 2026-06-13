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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S11 — Dashboard (W-1)
 *
 * <p>Seeds two orders today (CASH WALK_IN → ACTIVE; COD WALK_IN → PENDING) using a
 * Pizza-Box-category product so all five dashboard widgets can be asserted on shape +
 * correctness. Auth gate (401) verified on every endpoint.
 *
 * <p>Run command (DB up + migrated):
 * <pre>mvn test -Dtest=DashboardIT</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class DashboardIT {

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

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private User user;
    private String jwt;
    private Product product;
    private Agent agent;
    private CommissionPeriod period;

    @BeforeAll
    void seed() throws Exception {
        user = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s11d-" + RUN + "@test.rrbm.internal", "S11 Dashboard", "S11D-Secret!", "S11D-key");
        jwt = ITSupport.jwtFor(jwtUtil, user);

        // Pizza Box category → stats.pizzaBoxQtyToday + product-analytics.pizzaQuota.actual
        product = ITSupport.seedProduct(productRepository,
                "S11D" + (RUN % 99), "Pizza Box S11-" + RUN, new BigDecimal("200.00"), 500);
        product.setCategory("Pizza Box");
        productRepository.save(product);

        agent  = ITSupport.seedAgent(agentRepository, "S11DA" + (RUN % 9999), "S11 D Agent", "Zone S11D");
        period = ITSupport.seedOpenPeriod(commissionPeriodRepository,
                LocalDate.now(), LocalDate.now().plusDays(30));

        // CASH WALK_IN → ACTIVE (5 pizza boxes × ₱200 = ₱1 000)
        createOrder("S11D-CASH-" + RUN, "CASH", "WALK_IN", null, 5);
        // COD WALK_IN → PENDING (3 pizza boxes × ₱200 = ₱600) — counts in codPending
        createOrder("S11D-COD-" + RUN,  "COD",  "WALK_IN", null, 3);
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

    // ── GET /api/dashboard/stats ──────────────────────────────────────────────

    @Test
    void t01_stats_daily_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/dashboard/stats")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.has("totalSales")).isTrue();
        assertThat(n.has("orderCount")).isTrue();
        assertThat(n.has("activeOrders")).isTrue();
        assertThat(n.has("pendingOrders")).isTrue();
        assertThat(n.has("lowStockCount")).isTrue();
        assertThat(n.has("pizzaBoxQtyToday")).isTrue();
        assertThat(n.has("paymentBreakdown")).isTrue();
        assertThat(n.get("paymentBreakdown").isObject()).isTrue();
        assertThat(n.has("salesTrend")).isTrue();
        assertThat(n.get("salesTrend").isArray()).isTrue();
        assertThat(n.get("salesTrend").size()).isEqualTo(7); // daily = last 7 days
    }

    @Test
    void t02_stats_daily_totalsReflectSeededOrders() throws Exception {
        String body = mockMvc.perform(get("/api/dashboard/stats")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        // CASH (₱1 000) + COD (₱600) — both non-CANCELLED → totalSales ≥ ₱1 600
        BigDecimal totalSales = n.get("totalSales").decimalValue();
        assertThat(totalSales).isGreaterThanOrEqualTo(new BigDecimal("1600.00"));

        // pizzaBoxQtyToday: 5 + 3 = 8 pizza boxes from our two orders
        long pizzaQty = n.get("pizzaBoxQtyToday").asLong();
        assertThat(pizzaQty).isGreaterThanOrEqualTo(8);

        // CASH order → ACTIVE; COD order → PENDING
        assertThat(n.get("activeOrders").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(n.get("pendingOrders").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void t03_stats_weekly_returns200() throws Exception {
        mockMvc.perform(get("/api/dashboard/stats?period=weekly")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
    }

    @Test
    void t04_stats_monthly_returns200() throws Exception {
        mockMvc.perform(get("/api/dashboard/stats?period=monthly")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
    }

    @Test
    void t05_stats_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/dashboard/stats"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/dashboard/top-products-today ─────────────────────────────────

    @Test
    void t06_topProductsToday_returns200WithRankedList() throws Exception {
        String body = mockMvc.perform(get("/api/dashboard/top-products-today")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();
        // Our product is sold today (5+3=8 units) — must appear in top-5
        assertThat(arr.size()).isGreaterThanOrEqualTo(1);

        JsonNode first = arr.get(0);
        assertThat(first.has("name")).isTrue();
        assertThat(first.has("qty")).isTrue();
        assertThat(first.has("revenue")).isTrue();
        assertThat(first.has("rank")).isTrue();
        assertThat(first.get("rank").asInt()).isEqualTo(1);

        // Find our product by name and verify qty ≥ 8
        boolean found = false;
        for (JsonNode entry : arr) {
            if (product.getName().equals(entry.get("name").asText())) {
                found = true;
                assertThat(entry.get("qty").asLong()).isGreaterThanOrEqualTo(8);
                break;
            }
        }
        assertThat(found).withFailMessage("Seeded product should appear in top-products-today").isTrue();
    }

    @Test
    void t07_topProductsToday_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/dashboard/top-products-today"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/dashboard/channel-summary ───────────────────────────────────

    @Test
    void t08_channelSummary_daily_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/dashboard/channel-summary")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.has("directOrders")).isTrue();
        assertThat(n.has("directRevenue")).isTrue();
        assertThat(n.has("directPizzaQty")).isTrue();
        assertThat(n.has("ecomOrders")).isTrue();
        assertThat(n.has("ecomRevenue")).isTrue();
        assertThat(n.has("ecomPlatforms")).isTrue();
        assertThat(n.get("ecomPlatforms").isArray()).isTrue();
        assertThat(n.get("ecomPlatforms").size()).isEqualTo(3); // SHOPEE / TIKTOK / LAZADA always present
        assertThat(n.has("codPending")).isTrue();
        assertThat(n.has("payablesOutstanding")).isTrue();
        assertThat(n.has("payablesPendingCount")).isTrue();
    }

    @Test
    void t09_channelSummary_codPendingReflectsSeededCodOrder() throws Exception {
        String body = mockMvc.perform(get("/api/dashboard/channel-summary")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        // Our COD PENDING order is not DELIVERED/CLOSED/CANCELLED → counts in codPending
        assertThat(n.get("codPending").asLong()).isGreaterThanOrEqualTo(1);
        // Our WALK_IN orders appear in directOrders/directRevenue
        assertThat(n.get("directOrders").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(n.get("directRevenue").decimalValue())
                .isGreaterThanOrEqualTo(new BigDecimal("1600.00"));
    }

    @Test
    void t10_channelSummary_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/dashboard/channel-summary"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/dashboard/product-analytics ──────────────────────────────────

    @Test
    void t11_productAnalytics_daily_returns200WithRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/dashboard/product-analytics")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.has("period")).isTrue();
        assertThat(n.has("totalQtySold")).isTrue();
        assertThat(n.has("pizzaQuota")).isTrue();
        assertThat(n.get("pizzaQuota").has("target")).isTrue();
        assertThat(n.get("pizzaQuota").has("actual")).isTrue();
        assertThat(n.has("categories")).isTrue();
        assertThat(n.get("categories").isArray()).isTrue();
        assertThat(n.has("topProducts")).isTrue();
        assertThat(n.get("topProducts").isArray()).isTrue();
        assertThat(n.has("trend")).isTrue();
        assertThat(n.get("trend").isArray()).isTrue();
        assertThat(n.get("trend").size()).isEqualTo(24); // daily = 24 hour buckets
    }

    @Test
    void t12_productAnalytics_pizzaQtyReflectsSeededOrders() throws Exception {
        String body = mockMvc.perform(get("/api/dashboard/product-analytics")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        // Our two orders: 5 + 3 = 8 Pizza Box units
        assertThat(n.get("totalQtySold").asLong()).isGreaterThanOrEqualTo(8);
        assertThat(n.get("pizzaQuota").get("actual").asLong()).isGreaterThanOrEqualTo(8);

        // "Pizza Box" category entry must appear in categories
        boolean foundCat = false;
        for (JsonNode cat : n.get("categories")) {
            if ("Pizza Box".equals(cat.get("name").asText())) {
                foundCat = true;
                assertThat(cat.get("qty").asLong()).isGreaterThanOrEqualTo(8);
                assertThat(cat.has("pct")).isTrue();
                assertThat(cat.has("subcategories")).isTrue();
                break;
            }
        }
        assertThat(foundCat).withFailMessage("Pizza Box category should appear in product-analytics").isTrue();
    }

    @Test
    void t13_productAnalytics_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/dashboard/product-analytics"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/dashboard/cashflow ───────────────────────────────────────────

    @Test
    void t14_cashflow_currentMonth_returns200WithMathIdentity() throws Exception {
        String body = mockMvc.perform(get("/api/dashboard/cashflow")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode n = MAPPER.readTree(body);
        assertThat(n.has("year")).isTrue();
        assertThat(n.has("month")).isTrue();
        assertThat(n.has("revenue")).isTrue();
        assertThat(n.has("expenses")).isTrue();
        assertThat(n.has("commissions")).isTrue();
        assertThat(n.has("net")).isTrue();

        // net = revenue − expenses − commissions
        BigDecimal revenue     = n.get("revenue").decimalValue();
        BigDecimal expenses    = n.get("expenses").decimalValue();
        BigDecimal commissions = n.get("commissions").decimalValue();
        BigDecimal net         = n.get("net").decimalValue();
        assertThat(net).isEqualByComparingTo(revenue.subtract(expenses).subtract(commissions));
    }

    @Test
    void t15_cashflow_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/dashboard/cashflow"))
                .andExpect(status().isUnauthorized());
    }
}
