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
 * Corporate monthly report — {@code GET /api/reports/monthly-corporate}.
 *
 * <p>Verifies the redesigned, reconciling report payload:
 * <ul>
 *   <li><b>Reconciliation:</b> grossSales + refundsTotal + adjustmentsTotal = netSales.</li>
 *   <li><b>Channels (net):</b> direct + ecommerce + unattributed = summary.netRevenue —
 *       so the channel parts always sum to the headline.</li>
 *   <li><b>Pizza by category:</b> a product whose <i>category</i> is {@code Pizza Box} but whose
 *       <i>name</i> lacks "Pizza Box" (e.g. "Hot Fresh Pizza") <b>is</b> counted (the bug fix);
 *       direct+ecom qty = total; per-platform qty sums to the ecommerce qty.</li>
 *   <li><b>Expenses:</b> a <i>voided</i> expense is excluded from the grand total (delta test);
 *       a normal expense increases it by exactly its amount.</li>
 *   <li><b>MoM:</b> a metrics array vs the previous month is present.</li>
 * </ul>
 *
 * <p>Run: {@code mvn test -Dtest=MonthlyCorporateReportIT}
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class MonthlyCorporateReportIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ExpenseRepository expenseRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long   RUN   = System.currentTimeMillis() % 100000;
    private static final String MONTH = YearMonth.now().toString();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Pizza qty seeded this month: WALK_IN 3 (direct) + SHOPEE 2 (ecom) = 5. */
    private static final int DIRECT_PIZZA_QTY = 3;
    private static final int ECOM_PIZZA_QTY   = 2;

    private User user;
    private String jwt;
    private Product pizzaProduct;

    @BeforeAll
    void seed() throws Exception {
        user = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "corp-" + RUN + "@test.rrbm.internal", "Corp Report", "Corp-Secret!", "Corp-key");
        jwt = ITSupport.jwtFor(jwtUtil, user);

        // Category = "Pizza Box" but NAME deliberately lacks "Pizza Box" — exercises the
        // category-based detection that the old name-only match would have missed.
        pizzaProduct = ITSupport.seedProduct(productRepository,
                "CORP" + (RUN % 99), "Hot Fresh Pizza " + RUN, new BigDecimal("200.00"), 500);
        pizzaProduct.setCategory("Pizza Box");
        productRepository.save(pizzaProduct);

        createOrder("CORP-CASH-" + RUN, "WALK_IN",   null,     DIRECT_PIZZA_QTY);
        createOrder("CORP-ECOM-" + RUN, "ECOMMERCE", "SHOPEE", ECOM_PIZZA_QTY);
    }

    private void createOrder(String customer, String source, String platform, int qty) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("productId",   pizzaProduct.getId());
        item.put("productName", pizzaProduct.getName());
        item.put("quantity",    qty);
        item.put("unitPrice",   new BigDecimal("200.00"));
        item.put("warehouse",   "wh1");

        Map<String, Object> req = new HashMap<>();
        req.put("customerName", customer);
        req.put("paymentMode",  "CASH");
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
        orderRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(o -> orderRepository.deleteById(o.getId()));
        if (pizzaProduct != null) productRepository.deleteById(pizzaProduct.getId());
        if (user != null) userRepository.deleteById(user.getId());
    }

    private JsonNode fetchReport(String month) throws Exception {
        String body = mockMvc.perform(get("/api/reports/monthly-corporate?month=" + month)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    @Test
    void t01_returns200WithAllSections() throws Exception {
        JsonNode n = fetchReport(MONTH);
        assertThat(n.get("month").asText()).isEqualTo(MONTH);
        assertThat(n.has("prevMonth")).isTrue();
        for (String section : List.of("summary", "reconciliation", "channels", "pizza", "expenses", "mom")) {
            assertThat(n.has(section)).withFailMessage("missing section: " + section).isTrue();
        }
        JsonNode s = n.get("summary");
        for (String f : List.of("totalOrders", "netRevenue", "totalExpenses", "netProfit", "totalItemsSold")) {
            assertThat(s.has(f)).withFailMessage("summary missing: " + f).isTrue();
        }
        // netProfit identity
        assertThat(s.get("netProfit").decimalValue())
                .isEqualByComparingTo(s.get("netRevenue").decimalValue()
                        .subtract(s.get("totalExpenses").decimalValue()));
    }

    @Test
    void t02_reconciliationIdentityHolds() throws Exception {
        JsonNode r = fetchReport(MONTH).get("reconciliation");
        BigDecimal gross = r.get("grossSales").decimalValue();
        BigDecimal refunds = r.get("refundsTotal").decimalValue();
        BigDecimal adj = r.get("adjustmentsTotal").decimalValue();
        BigDecimal net = r.get("netSales").decimalValue();
        assertThat(net).isEqualByComparingTo(gross.add(refunds).add(adj));
    }

    @Test
    void t03_channelPartsSumToNetRevenue() throws Exception {
        JsonNode n = fetchReport(MONTH);
        BigDecimal netRevenue = n.get("summary").get("netRevenue").decimalValue();
        JsonNode ch = n.get("channels");
        BigDecimal direct = ch.get("direct").get("netRevenue").decimalValue();
        BigDecimal ecom   = ch.get("ecommerce").get("netRevenue").decimalValue();
        BigDecimal unattr = ch.get("unattributed").decimalValue();
        // By construction: direct + ecommerce + unattributed = netSales = summary.netRevenue
        assertThat(direct.add(ecom).add(unattr)).isEqualByComparingTo(netRevenue);
        // reconciliation.netSales must equal summary.netRevenue too
        assertThat(n.get("reconciliation").get("netSales").decimalValue()).isEqualByComparingTo(netRevenue);
    }

    @Test
    void t04_pizzaCountedByCategoryEvenWhenNameLacksPizzaBox() throws Exception {
        JsonNode pz = fetchReport(MONTH).get("pizza");
        long total  = pz.get("totalQty").asLong();
        long direct = pz.get("directQty").asLong();
        long ecom   = pz.get("ecomQty").asLong();
        // The "Hot Fresh Pizza" product (category Pizza Box) must be picked up
        assertThat(total).isGreaterThanOrEqualTo(DIRECT_PIZZA_QTY + ECOM_PIZZA_QTY);
        assertThat(direct).isGreaterThanOrEqualTo(DIRECT_PIZZA_QTY);
        assertThat(ecom).isGreaterThanOrEqualTo(ECOM_PIZZA_QTY);
        // Channel split sums to total
        assertThat(direct + ecom).isEqualTo(total);
    }

    @Test
    void t05_pizzaPerPlatformSumsToEcomQty() throws Exception {
        JsonNode pz = fetchReport(MONTH).get("pizza");
        long ecom = pz.get("ecomQty").asLong();
        long platSum = 0;
        boolean foundShopee = false;
        for (JsonNode p : pz.get("platforms")) {
            platSum += p.get("qty").asLong();
            if ("SHOPEE".equals(p.get("platform").asText())) {
                foundShopee = true;
                assertThat(p.get("qty").asLong()).isGreaterThanOrEqualTo(ECOM_PIZZA_QTY);
            }
        }
        assertThat(platSum).isEqualTo(ecom);
        assertThat(foundShopee).withFailMessage("SHOPEE platform row expected").isTrue();
    }

    @Test
    void t06_expensesGrandTotalExcludesVoided() throws Exception {
        BigDecimal g0 = fetchReport(MONTH).get("expenses").get("grandTotal").decimalValue();

        Expense voided = newExpense(new BigDecimal("777.00"), true);
        Expense normal = null;
        try {
            BigDecimal g1 = fetchReport(MONTH).get("expenses").get("grandTotal").decimalValue();
            // Voided expense must NOT change the grand total
            assertThat(g1).isEqualByComparingTo(g0);

            normal = newExpense(new BigDecimal("777.00"), false);
            BigDecimal g2 = fetchReport(MONTH).get("expenses").get("grandTotal").decimalValue();
            // Non-voided expense increases the grand total by exactly its amount
            assertThat(g2).isEqualByComparingTo(g0.add(new BigDecimal("777.00")));
        } finally {
            if (voided != null) expenseRepository.deleteById(voided.getId());
            if (normal != null) expenseRepository.deleteById(normal.getId());
        }
    }

    private Expense newExpense(BigDecimal amount, boolean isVoided) {
        Expense e = new Expense();
        e.setDate(LocalDate.now());
        e.setAdminName("Corp IT " + RUN);
        e.setTotalAmount(amount);
        e.setVoided(isVoided);
        e.setStatus(isVoided ? "VOIDED" : "COMPLETED");
        return expenseRepository.save(e);
    }

    @Test
    void t07_momPresentWithMetrics() throws Exception {
        JsonNode mom = fetchReport(MONTH).get("mom");
        assertThat(mom.has("prevMonth")).isTrue();
        assertThat(mom.has("metrics")).isTrue();
        assertThat(mom.get("metrics").isArray()).isTrue();
        assertThat(mom.get("metrics").size()).isGreaterThanOrEqualTo(1);
        for (JsonNode m : mom.get("metrics")) {
            assertThat(m.has("metric")).isTrue();
            assertThat(m.has("current")).isTrue();
            assertThat(m.has("previous")).isTrue();
            assertThat(m.has("delta")).isTrue();
            assertThat(m.has("direction")).isTrue();
        }
    }

    @Test
    void t08_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/reports/monthly-corporate?month=" + MONTH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void t09_invalidMonth_returns400() throws Exception {
        mockMvc.perform(get("/api/reports/monthly-corporate?month=not-a-month")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isBadRequest());
    }
}
