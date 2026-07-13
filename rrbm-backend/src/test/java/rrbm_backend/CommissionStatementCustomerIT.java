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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Agent-commission statement display (customer name + "O.P. Total" column rename).
 *
 * <p>A commission entry stores only the orderId; the statement JSON and every export format
 * must resolve and show the customer name alongside the Order ID, and label the O.P. amount
 * column "O.P. Total". This exercises the in-app statement JSON and the PDF/CSV/Excel exports.
 *
 * <p>Run: {@code mvn test -Dtest=CommissionStatementCustomerIT}
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class CommissionStatementCustomerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private CommissionPeriodRepository commissionPeriodRepository;
    @Autowired private CommissionEntryRepository commissionEntryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private CashLedgerRepository cashLedgerRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CUSTOMER = "CS-Cust-Bakeville-" + RUN;

    private User admin;
    private String jwt;
    private Product product;
    private Agent agent;
    private CommissionPeriod period;
    private String orderId;

    @BeforeAll
    void seed() throws Exception {
        admin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "cs-" + RUN + "@test.rrbm.internal", "CS Admin " + RUN, "CS-Secret!", "CS-key-" + RUN);
        jwt = ITSupport.jwtFor(jwtUtil, admin);
        product = ITSupport.seedProduct(productRepository, "CS" + (RUN % 10000), "CS Widget " + RUN,
                new BigDecimal("100.00"), 500);
        agent = ITSupport.seedAgent(agentRepository, "CS-AGENT-" + RUN, "CS Test Agent", "Zone A");
        period = ITSupport.seedOpenPeriod(commissionPeriodRepository, LocalDate.now(), LocalDate.now().plusDays(30));

        // Create a real order so the entry's orderId resolves to a customer name.
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product.getId());
        item.put("productName", product.getName());
        item.put("quantity", 2);
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("warehouse", "wh1");
        Map<String, Object> req = new HashMap<>();
        req.put("customerName", CUSTOMER);
        req.put("source", "WALK_IN");
        req.put("paymentMode", "CASH");
        req.put("items", List.of(item));
        String json = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(MAPPER.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        orderId = MAPPER.readTree(json).get("id").asText();

        // Persist a commission entry pointing at that order (later date).
        CommissionEntry e = new CommissionEntry();
        e.setPeriodId(period.getId());
        e.setAgentId(agent.getId());
        e.setOrderId(orderId);
        e.setOrderDate(LocalDate.now());
        e.setProductName("LaterWidget-" + RUN);
        e.setQuantity(2);
        e.setBasePrice(new BigDecimal("100.00000"));
        e.setOpRate(new BigDecimal("0.0500"));
        e.setOpPerUnit(new BigDecimal("5.00"));
        e.setOpAmount(new BigDecimal("1234.50000"));
        e.setStatus("PENDING");
        commissionEntryRepository.save(e);

        // A second entry with an EARLIER order date — must sort before the later one.
        CommissionEntry e2 = new CommissionEntry();
        e2.setPeriodId(period.getId());
        e2.setAgentId(agent.getId());
        e2.setOrderId(orderId);
        e2.setOrderDate(LocalDate.now().minusDays(5));
        e2.setProductName("EarlierWidget-" + RUN);
        e2.setQuantity(1);
        e2.setBasePrice(new BigDecimal("50.00000"));
        e2.setOpRate(new BigDecimal("0.0500"));
        e2.setOpPerUnit(new BigDecimal("2.50"));
        e2.setOpAmount(new BigDecimal("2.50000"));
        e2.setStatus("PENDING");
        commissionEntryRepository.save(e2);
    }

    @AfterAll
    void clean() {
        try { commissionEntryRepository.deleteAll(); } catch (Exception ignored) {}
        try { transactionRepository.deleteAll(); } catch (Exception ignored) {}
        try { inventoryMovementRepository.deleteAll(); } catch (Exception ignored) {}
        try { cashLedgerRepository.deleteAll(); } catch (Exception ignored) {}
        try { activityLogRepository.deleteAll(); } catch (Exception ignored) {}
        try {
            orderRepository.findAll().stream()
                    .filter(o -> o.getCustomerName() != null && o.getCustomerName().startsWith("CS-Cust-"))
                    .forEach(o -> orderRepository.deleteById(o.getId()));
        } catch (Exception ignored) {}
        try { if (period != null) commissionPeriodRepository.deleteById(period.getId()); } catch (Exception ignored) {}
        try { if (agent != null) agentRepository.deleteById(agent.getId()); } catch (Exception ignored) {}
        try { if (product != null) productRepository.deleteById(product.getId()); } catch (Exception ignored) {}
        try { if (admin != null) userRepository.deleteById(admin.getId()); } catch (Exception ignored) {}
    }

    private String export(String format) throws Exception {
        return mockMvc.perform(get("/api/commissions/periods/" + period.getId()
                        + "/agents/" + agent.getId() + "/statement/export?format=" + format)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void t01_statementJson_includesCustomerName() throws Exception {
        String json = mockMvc.perform(get("/api/commissions/periods/" + period.getId()
                        + "/agents/" + agent.getId() + "/statement")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode entries = MAPPER.readTree(json).get("entries");
        assertThat(entries).isNotEmpty();
        JsonNode row = entries.get(0);
        assertThat(row.get("orderId").asText()).isEqualTo(orderId);
        assertThat(row.get("customerName").asText()).isEqualTo(CUSTOMER);
    }

    @Test
    void t02_pdfExport_hasCustomerAndOpTotalHeader() throws Exception {
        String pdf = export("pdf");
        assertThat(pdf).contains(CUSTOMER);
        assertThat(pdf).contains("O.P. Total");
        assertThat(pdf).doesNotContain("O.P. Amount");
    }

    @Test
    void t05_pdfExport_dropsRateColumn_currency3dp_andSortsByDate() throws Exception {
        String pdf = export("pdf");
        // Rate and Status columns removed from the PDF.
        assertThat(pdf).doesNotContain(">Rate</th>");
        assertThat(pdf).doesNotContain(">Status</th>");
        // Amounts are peso currency with exactly 3 decimals (₱1,234.500 not 1234.50000).
        assertThat(pdf).contains("₱1,234.500");
        assertThat(pdf).contains("₱100.000");
        assertThat(pdf).doesNotContain("1234.50000");
        // Entries sorted by order date ascending — earlier product row comes first.
        int earlier = pdf.indexOf("EarlierWidget-" + RUN);
        int later   = pdf.indexOf("LaterWidget-" + RUN);
        assertThat(earlier).isGreaterThan(-1);
        assertThat(later).isGreaterThan(-1);
        assertThat(earlier).isLessThan(later);
    }

    @Test
    void t03_csvExport_hasCustomerColumnAndOpTotalHeader() throws Exception {
        String csv = export("csv");
        assertThat(csv).contains("customerName");
        assertThat(csv).contains("opTotal");
        assertThat(csv).contains(CUSTOMER);
    }

    @Test
    void t04_excelExport_hasCustomerAndOpTotalHeader() throws Exception {
        String xls = export("excel");
        assertThat(xls).contains("Customer");
        assertThat(xls).contains("O.P. Total");
        assertThat(xls).contains(CUSTOMER);
    }
}
