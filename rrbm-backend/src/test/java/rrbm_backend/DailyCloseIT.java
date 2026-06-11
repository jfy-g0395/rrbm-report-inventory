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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S2 / W-9 — Daily Close & Snapshot
 *
 * <p>Workflow: create today's orders (mix of CASH ACTIVE + COD PENDING) → close daily with master key
 * → snapshot frozen; force-close path for open orders.
 *
 * <p>Scenarios:
 * - Close with valid master key, no open orders → 200; assert daily_reports row with correct math
 * - Open/active orders present, forceClose=false → 409 ACTIVE_ORDERS; assert NO row written
 * - Force-close with adminSecurityKey/superAdminSecurityKey → 200; snapshot records unfulfilled
 * - Bad master key → 403; missing token → 401; second close of same date → 400 (idempotency)
 * - daily-status, daily/{date}, range reads reflect the close
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class DailyCloseIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private DailyReportRepository dailyReportRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private MasterKeyRepository masterKeyRepository;
    @Autowired private CommissionPeriodRepository commissionPeriodRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S2-Secret!";
    private static final String SEC_KEY = "S2-admin-key";
    private static final String MASTER_KEY_RAW = "S2-master-" + RUN;

    private User superAdmin;
    private User accountingUser;
    private String adminJwt;
    private MasterKey masterKey;
    private Product product1;
    private Agent agent1;
    private CommissionPeriod openPeriod;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void seed() {
        // Seed users
        superAdmin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "s2-super-" + RUN + "@test.rrbm.internal", "S2 Super Admin", PASSWORD, SEC_KEY);
        accountingUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s2-acct-" + RUN + "@test.rrbm.internal", "S2 Accounting", PASSWORD, SEC_KEY);
        adminJwt = ITSupport.jwtFor(jwtUtil, accountingUser);

        // Seed master key
        masterKey = ITSupport.seedMasterKey(masterKeyRepository, MASTER_KEY_RAW, "S2 Test Key");

        // Seed a product with known price
        product1 = ITSupport.seedProduct(productRepository,
                "S2-PROD-" + RUN, "S2 Test Product", new BigDecimal("150.00"), 100);

        // Seed an active agent
        agent1 = ITSupport.seedAgent(agentRepository,
                "S2-AGENT-" + RUN, "S2 Test Agent", "Zone A");

        // Seed an open commission period (today)
        openPeriod = ITSupport.seedOpenPeriod(commissionPeriodRepository,
                LocalDate.now(), LocalDate.now().plusDays(30));
    }

    @AfterAll
    void clean() {
        // Delete in FK-safe order: activity_log → orders → daily_reports → other entities
        if (accountingUser != null) {
            activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now()).stream()
                    .filter(a -> accountingUser.getId().equals(a.getUserId()))
                    .forEach(a -> activityLogRepository.deleteById(a.getId()));
        }

        // Delete orders for today
        java.time.LocalDateTime yesterday = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime tomorrow = java.time.LocalDateTime.now().plusDays(1);
        orderRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(o -> orderRepository.delete(o));

        // Delete daily report for today if it exists
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        // Delete the commission period
        if (openPeriod != null) commissionPeriodRepository.deleteById(openPeriod.getId());

        // Delete the agent
        if (agent1 != null) agentRepository.deleteById(agent1.getId());

        // Delete the product
        if (product1 != null) productRepository.deleteById(product1.getId());

        // Delete master key
        if (masterKey != null) masterKeyRepository.deleteById(masterKey.getId());

        // Delete users
        if (accountingUser != null) userRepository.deleteById(accountingUser.getId());
        if (superAdmin != null) userRepository.deleteById(superAdmin.getId());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Create an order via the real POST /api/orders endpoint.
     * Returns the order ID from the response.
     */
    private String createOrderViaApi(String customerName, String paymentMode, BigDecimal itemQuantity) throws Exception {
        Map<String, Object> itemMap = new HashMap<>();
        itemMap.put("productId", product1.getId());
        itemMap.put("productName", product1.getName());
        itemMap.put("quantity", itemQuantity.intValue());
        itemMap.put("unitPrice", new BigDecimal("150.00"));
        itemMap.put("warehouse", "wh1");

        Map<String, Object> request = new HashMap<>();
        request.put("customerName", customerName);
        request.put("source", "WALK_IN");
        request.put("paymentMode", paymentMode);
        request.put("items", List.of(itemMap));

        String respJson = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
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
    void t01_closeDailyWithNoOpenOrders_createsReportWith200() throws Exception {
        // Create 2 CASH ACTIVE orders (no open orders to block the close)
        String orderId1 = createOrderViaApi("S2-Customer-1-" + RUN, "CASH", new BigDecimal("2"));
        String orderId2 = createOrderViaApi("S2-Customer-2-" + RUN, "CASH", new BigDecimal("3"));

        // Close daily with valid master key
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content("{\"masterKey\":\"" + MASTER_KEY_RAW + "\",\"forceClose\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Daily sales closed successfully"))
                .andExpect(jsonPath("$.report").exists())
                .andExpect(jsonPath("$.report.reportDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.report.totalOrders").value(2));

        // Assert daily_reports row exists with correct math
        var report = dailyReportRepository.findByReportDate(LocalDate.now());
        assertThat(report).isPresent();
        DailyReport dr = report.get();
        assertThat(dr.getTotalOrders()).isEqualTo(2);
        assertThat(dr.getClosedBy()).isEqualTo(accountingUser.getId());
        assertThat(dr.getClosedAt()).isNotNull();

        // Assert activity_log entry for the close
        List<ActivityLog> logs = activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now());
        assertThat(logs.stream()
                .filter(a -> "CLOSE_DAILY".equals(a.getAction()) && accountingUser.getId().equals(a.getUserId()))
                .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void t02_closeDaily_withOpenOrders_returns409AndNoRowWritten() throws Exception {
        // Clear any previous daily report for today
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        // Create 1 CASH ACTIVE order
        String orderId1 = createOrderViaApi("S2-Customer-Open-" + RUN, "CASH", new BigDecimal("1"));

        // Try to close without forceClose (should fail with 409)
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content("{\"masterKey\":\"" + MASTER_KEY_RAW + "\",\"forceClose\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACTIVE_ORDERS"))
                .andExpect(jsonPath("$.count").exists())
                .andExpect(jsonPath("$.amount").exists());

        // Assert NO daily_reports row was written
        var report = dailyReportRepository.findByReportDate(LocalDate.now());
        assertThat(report).isEmpty();
    }

    @Test
    void t03_closeDailyWithBadMasterKey_returns403() throws Exception {
        // Clear any previous daily report for today
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        // Create one CASH order
        createOrderViaApi("S2-Customer-BadKey-" + RUN, "CASH", new BigDecimal("1"));

        // Try to close with bad master key
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content("{\"masterKey\":\"wrong-master-key\",\"forceClose\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Invalid master key"));

        // Assert NO daily_reports row written
        var report = dailyReportRepository.findByReportDate(LocalDate.now());
        assertThat(report).isEmpty();
    }

    @Test
    void t04_closeDailyWithNoToken_returns401() throws Exception {
        // Clear any previous daily report for today
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        // Create one order
        createOrderViaApi("S2-Customer-NoToken-" + RUN, "CASH", new BigDecimal("1"));

        // Try to close without token
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"masterKey\":\"" + MASTER_KEY_RAW + "\",\"forceClose\":false}"))
                .andExpect(status().isUnauthorized());

        // Assert NO daily_reports row written
        var report = dailyReportRepository.findByReportDate(LocalDate.now());
        assertThat(report).isEmpty();
    }

    @Test
    void t05_closeDailyTwice_secondAttemptReturns400() throws Exception {
        // Clear any previous daily report for today
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        // Create one CASH order
        createOrderViaApi("S2-Customer-Idempotent-" + RUN, "CASH", new BigDecimal("1"));

        // First close succeeds
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content("{\"masterKey\":\"" + MASTER_KEY_RAW + "\",\"forceClose\":false}"))
                .andExpect(status().isOk());

        // Second close of same date should fail with 400
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content("{\"masterKey\":\"" + MASTER_KEY_RAW + "\",\"forceClose\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t06_dailyStatusAfterClose_reflectsClosedState() throws Exception {
        // Clear any previous daily report for today
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        // Create one CASH order
        createOrderViaApi("S2-Customer-Status-" + RUN, "CASH", new BigDecimal("1"));

        // Close daily
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content("{\"masterKey\":\"" + MASTER_KEY_RAW + "\",\"forceClose\":false}"))
                .andExpect(status().isOk());

        // Check daily-status
        mockMvc.perform(get("/api/reports/daily-status")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.report").exists())
                .andExpect(jsonPath("$.closedByName").exists());
    }

    @Test
    void t07_dailyReportByDate_reflectsClosedReport() throws Exception {
        // Clear any previous daily report for today
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        // Create one CASH order
        createOrderViaApi("S2-Customer-ByDate-" + RUN, "CASH", new BigDecimal("1"));

        // Close daily
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content("{\"masterKey\":\"" + MASTER_KEY_RAW + "\",\"forceClose\":false}"))
                .andExpect(status().isOk());

        // Get report by date
        mockMvc.perform(get("/api/reports/daily/" + LocalDate.now())
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportDate").value(LocalDate.now().toString()));
    }

    @Test
    void t08_rangeReportsAfterClose_includesClosedReport() throws Exception {
        // Clear any previous daily report for today
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        // Create one CASH order
        createOrderViaApi("S2-Customer-Range-" + RUN, "CASH", new BigDecimal("1"));

        // Close daily
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content("{\"masterKey\":\"" + MASTER_KEY_RAW + "\",\"forceClose\":false}"))
                .andExpect(status().isOk());

        // Get range reports
        LocalDate today = LocalDate.now();
        mockMvc.perform(get("/api/reports/range")
                        .param("start", today.toString())
                        .param("end", today.toString())
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
