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
 * S12 — Activity Log reads (W-13)
 *
 * <p>Seeds one order via the real endpoint so that OrderService emits a "CREATE_ORDER"
 * activity-log entry belonging to our test user. All three ActivityLogController endpoints
 * are then exercised: /today, /{date}, and the ?start/end range form.
 *
 * <p>Run command (DB up + migrated):
 * <pre>mvn test -Dtest=ActivityLogIT</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ActivityLogIT {

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
                "s12al-" + RUN + "@test.rrbm.internal", "S12 ALog " + RUN, "S12AL-Secret!", "S12AL-key");
        jwt = ITSupport.jwtFor(jwtUtil, user);

        product = ITSupport.seedProduct(productRepository,
                "S12A" + (RUN % 99), "S12 ALog Product " + RUN, new BigDecimal("300.00"), 100);
        agent = ITSupport.seedAgent(agentRepository,
                "S12ALA" + (RUN % 9999), "S12 ALog Agent", "Zone S12AL");
        period = ITSupport.seedOpenPeriod(commissionPeriodRepository,
                LocalDate.now(), LocalDate.now().plusDays(30));

        // Create one order → OrderService emits "CREATE_ORDER" entry for our user
        Map<String, Object> item = new HashMap<>();
        item.put("productId",   product.getId());
        item.put("productName", product.getName());
        item.put("quantity",    2);
        item.put("unitPrice",   new BigDecimal("300.00"));
        item.put("warehouse",   "wh1");

        Map<String, Object> req = new HashMap<>();
        req.put("customerName", "S12-Log-Customer-" + RUN);
        req.put("paymentMode",  "CASH");
        req.put("source",       "WALK_IN");
        req.put("items",        List.of(item));

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

    // ── GET /api/activity-log/today ──────────────────────────────────────────

    @Test
    void t01_today_returns200WithArray() throws Exception {
        String body = mockMvc.perform(get("/api/activity-log/today")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void t02_today_containsCreateOrderEntryForOurUser() throws Exception {
        String body = mockMvc.perform(get("/api/activity-log/today")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        boolean found = false;
        for (JsonNode entry : arr) {
            if (user.getId().equals(entry.path("userId").asLong(0))
                    && "CREATE_ORDER".equals(entry.path("action").asText(""))) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .withFailMessage("Expected a CREATE_ORDER entry for userId=%d in today's log", user.getId())
                .isTrue();
    }

    @Test
    void t03_today_eachEntryHasRequiredShape() throws Exception {
        String body = mockMvc.perform(get("/api/activity-log/today")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.size()).isGreaterThanOrEqualTo(1);

        JsonNode first = arr.get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("action")).isTrue();
        assertThat(first.has("reportDate")).isTrue();
        assertThat(first.has("createdAt")).isTrue();
        // action must be a non-blank string
        assertThat(first.get("action").asText("")).isNotBlank();
    }

    @Test
    void t04_today_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/activity-log/today"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/activity-log/{date} ─────────────────────────────────────────

    @Test
    void t05_byDate_today_returns200WithEntries() throws Exception {
        String today = LocalDate.now().toString();
        String body = mockMvc.perform(get("/api/activity-log/" + today)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void t06_byDate_matchesTodayEndpointCount() throws Exception {
        String today = LocalDate.now().toString();

        JsonNode todayArr = MAPPER.readTree(
                mockMvc.perform(get("/api/activity-log/today")
                                .header("Authorization", "Bearer " + jwt))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        JsonNode dateArr = MAPPER.readTree(
                mockMvc.perform(get("/api/activity-log/" + today)
                                .header("Authorization", "Bearer " + jwt))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        // /today and /{today} are backed by the same service call → same result set
        assertThat(dateArr.size()).isEqualTo(todayArr.size());
    }

    @Test
    void t07_byDate_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/activity-log/" + LocalDate.now()))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/activity-log?start=&end= ────────────────────────────────────

    @Test
    void t08_range_todayToToday_returns200WithEntries() throws Exception {
        String today = LocalDate.now().toString();
        String body = mockMvc.perform(
                        get("/api/activity-log?start=" + today + "&end=" + today)
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void t09_range_singleDayMatchesByDateCount() throws Exception {
        String today = LocalDate.now().toString();

        JsonNode rangeArr = MAPPER.readTree(
                mockMvc.perform(get("/api/activity-log?start=" + today + "&end=" + today)
                                .header("Authorization", "Bearer " + jwt))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        JsonNode dateArr = MAPPER.readTree(
                mockMvc.perform(get("/api/activity-log/" + today)
                                .header("Authorization", "Bearer " + jwt))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(rangeArr.size()).isEqualTo(dateArr.size());
    }

    @Test
    void t10_range_missingStart_returns400() throws Exception {
        // GAP S12-02 fixed: GlobalExceptionHandler now handles MissingServletRequestParameterException
        // with an explicit 400 handler before the catch-all 500.
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/api/activity-log?end=" + today)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t11_range_missingEnd_returns400() throws Exception {
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/api/activity-log?start=" + today)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t12_range_noToken_returns401() throws Exception {
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/api/activity-log?start=" + today + "&end=" + today))
                .andExpect(status().isUnauthorized());
    }
}
