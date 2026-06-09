package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A6 integration tests — agent_commissions master record and agent performance widget.
 *
 * Setup (@BeforeAll):
 *   - test user (with BCrypt security key), test agent
 *   - OPEN period (2029-01, far future to avoid cross-test pollution)
 *   - two commission entries (op=12.00, op=8.00 → totalOp=20.00)
 *   - BONUS adjustment=10.00, DEDUCTION adjustment=5.00
 *   → expectedNetCommission = 20.00 + 10.00 − 5.00 = 25.00
 *
 * Tests (ordered):
 *   a. release → agent_commissions row exists with correct totalOp and netCommission
 *   b. GET /api/agents/{id}/performance without JWT → 401
 *   c. GET /api/agents/{id}/performance with JWT → 200; all keys present
 *   d. lifetimeNetCommission = SUM(commissionSummary[i].netCommission)
 *
 * Teardown (@AfterAll):
 *   agent_commissions → adjustments → entries → period → agent → user
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA6Test {

    @Autowired private MockMvc                         mockMvc;
    @Autowired private UserRepository                  userRepository;
    @Autowired private AgentRepository                 agentRepository;
    @Autowired private AgentCommissionRepository       agentCommissionRepository;
    @Autowired private CommissionPeriodRepository      periodRepository;
    @Autowired private CommissionEntryRepository       entryRepository;
    @Autowired private CommissionAdjustmentRepository  adjustmentRepository;
    @Autowired private JwtUtil                         jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();

    private User   testUser;
    private String jwt;
    private Agent  testAgent;
    private Long   testPeriodId;

    private static final String     TEST_SECURITY_KEY   = "a6TestSecurityKey";
    private static final BigDecimal ENTRY_OP_1          = new BigDecimal("12.00");
    private static final BigDecimal ENTRY_OP_2          = new BigDecimal("8.00");
    private static final BigDecimal TOTAL_OP            = new BigDecimal("20.00");
    private static final BigDecimal BONUS_AMOUNT        = new BigDecimal("10.00");
    private static final BigDecimal DEDUCTION_AMOUNT    = new BigDecimal("5.00");
    private static final BigDecimal EXPECTED_NET        = new BigDecimal("25.00");  // 20 + 10 − 5

    @BeforeAll
    void setUpAll() {
        String sfx = String.valueOf(System.currentTimeMillis());

        testUser = new User();
        testUser.setEmail("a6-test-" + sfx + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A6 Test Admin");
        testUser.setRole("ADMIN");
        testUser.setAdminSecurityKey(new BCryptPasswordEncoder().encode(TEST_SECURITY_KEY));
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        testAgent = new Agent();
        testAgent.setAgentCode("AGENT-A6-" + sfx.substring(sfx.length() - 4));
        testAgent.setFullName("A6 Test Agent " + sfx);
        testAgent.setContactNumber("09170000006");
        testAgent.setTerritory("Test Territory A6");
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);

        // OPEN period in far future to avoid overlap with any production/other-test data.
        CommissionPeriod period = new CommissionPeriod();
        period.setPeriodCode("A6-2029-01-" + sfx.substring(sfx.length() - 6));
        period.setStartDate(LocalDate.of(2029, 1, 1));
        period.setEndDate(LocalDate.of(2029, 1, 31));
        period.setCreatedBy(testUser.getId());
        period = periodRepository.save(period);
        testPeriodId = period.getId();

        // Two commission entries.
        CommissionEntry e1 = new CommissionEntry();
        e1.setPeriodId(testPeriodId);
        e1.setAgentId(testAgent.getId());
        e1.setOrderDate(LocalDate.of(2029, 1, 10));
        e1.setProductName("A6 Widget Alpha");
        e1.setQuantity(1);
        e1.setOpAmount(ENTRY_OP_1);
        entryRepository.save(e1);

        CommissionEntry e2 = new CommissionEntry();
        e2.setPeriodId(testPeriodId);
        e2.setAgentId(testAgent.getId());
        e2.setOrderDate(LocalDate.of(2029, 1, 15));
        e2.setProductName("A6 Widget Beta");
        e2.setQuantity(2);
        e2.setOpAmount(ENTRY_OP_2);
        entryRepository.save(e2);

        // Bonus and deduction adjustments.
        CommissionAdjustment bonus = new CommissionAdjustment();
        bonus.setPeriodId(testPeriodId);
        bonus.setAgentId(testAgent.getId());
        bonus.setAdjustmentType("BONUS");
        bonus.setAmount(BONUS_AMOUNT);
        bonus.setReason("A6 performance bonus");
        bonus.setCreatedBy(testUser.getId());
        adjustmentRepository.save(bonus);

        CommissionAdjustment deduction = new CommissionAdjustment();
        deduction.setPeriodId(testPeriodId);
        deduction.setAgentId(testAgent.getId());
        deduction.setAdjustmentType("DEDUCTION");
        deduction.setAmount(DEDUCTION_AMOUNT);
        deduction.setReason("A6 test deduction");
        deduction.setCreatedBy(testUser.getId());
        adjustmentRepository.save(deduction);
    }

    @AfterAll
    void tearDownAll() {
        // FK order: agent_commissions → adjustments → entries → period → agent → user
        agentCommissionRepository.deleteAll(agentCommissionRepository.findByPeriodId(testPeriodId));
        adjustmentRepository.deleteAll(adjustmentRepository.findByPeriodId(testPeriodId));
        entryRepository.deleteAll(entryRepository.findByPeriodId(testPeriodId));
        if (testPeriodId != null) periodRepository.deleteById(testPeriodId);
        agentRepository.delete(testAgent);
        userRepository.delete(testUser);
    }

    // ── A6-a: release → agent_commissions row written with correct figures ────
    // Also exercises close + release so tests c and d see a released period.

    @Test
    @Order(1)
    void releasePeriod_createsAgentCommissionsRow() throws Exception {
        // Close
        mockMvc.perform(post("/api/commissions/periods/" + testPeriodId + "/close")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());

        // Release
        Map<String, String> releaseBody = Map.of("adminSecurityKey", TEST_SECURITY_KEY);
        mockMvc.perform(post("/api/commissions/periods/" + testPeriodId + "/release")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(releaseBody)))
                .andExpect(status().isOk());

        // Verify agent_commissions row was written.
        Optional<AgentCommission> acOpt =
                agentCommissionRepository.findByAgentIdAndPeriodId(testAgent.getId(), testPeriodId);
        assertTrue(acOpt.isPresent(), "agent_commissions row must exist after period release");

        AgentCommission ac = acOpt.get();
        assertEquals(0, TOTAL_OP.compareTo(ac.getTotalOp()),
                "totalOp must equal sum of RELEASED entries: " + TOTAL_OP);
        assertEquals(0, BONUS_AMOUNT.compareTo(ac.getTotalBonus()),
                "totalBonus must equal BONUS adjustment: " + BONUS_AMOUNT);
        assertEquals(0, DEDUCTION_AMOUNT.compareTo(ac.getTotalDeduction()),
                "totalDeduction must equal DEDUCTION adjustment: " + DEDUCTION_AMOUNT);
        assertEquals(0, EXPECTED_NET.compareTo(ac.getNetCommission()),
                "netCommission must equal totalOp + totalBonus − totalDeduction = " + EXPECTED_NET);
    }

    // ── A6-b: GET /api/agents/{id}/performance without JWT → 401 ─────────────

    @Test
    @Order(2)
    void performance_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/agents/" + testAgent.getId() + "/performance"))
                .andExpect(status().isUnauthorized());
    }

    // ── A6-c: valid JWT → 200; all required keys present ─────────────────────

    @Test
    @Order(3)
    void performance_validJwt_returns200WithAllKeys() throws Exception {
        String resp = mockMvc.perform(get("/api/agents/" + testAgent.getId() + "/performance")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        assertTrue(data.containsKey("agentId"),               "Response must have agentId");
        assertTrue(data.containsKey("agentCode"),             "Response must have agentCode");
        assertTrue(data.containsKey("fullName"),              "Response must have fullName");
        assertTrue(data.containsKey("totalOrders"),           "Response must have totalOrders");
        assertTrue(data.containsKey("commissionSummary"),     "Response must have commissionSummary");
        assertTrue(data.containsKey("lifetimeNetCommission"), "Response must have lifetimeNetCommission");

        assertEquals(testAgent.getId().intValue(), ((Number) data.get("agentId")).intValue(),
                "agentId must match test agent");
        assertEquals(testAgent.getAgentCode(), data.get("agentCode"),
                "agentCode must match test agent");

        List<?> summary = (List<?>) data.get("commissionSummary");
        assertFalse(summary.isEmpty(), "commissionSummary must contain at least one row");

        Map<?, ?> row = (Map<?, ?>) summary.get(0);
        assertTrue(row.containsKey("periodCode"),     "Summary row must have periodCode");
        assertTrue(row.containsKey("startDate"),      "Summary row must have startDate");
        assertTrue(row.containsKey("endDate"),        "Summary row must have endDate");
        assertTrue(row.containsKey("totalOp"),        "Summary row must have totalOp");
        assertTrue(row.containsKey("totalBonus"),     "Summary row must have totalBonus");
        assertTrue(row.containsKey("totalDeduction"), "Summary row must have totalDeduction");
        assertTrue(row.containsKey("netCommission"),  "Summary row must have netCommission");
        assertTrue(row.containsKey("releasedAt"),     "Summary row must have releasedAt");
    }

    // ── A6-d: lifetimeNetCommission = SUM of commissionSummary.netCommission ──

    @Test
    @Order(4)
    void performance_lifetimeNetCommissionEqualsSum() throws Exception {
        String resp = mockMvc.perform(get("/api/agents/" + testAgent.getId() + "/performance")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        List<?> summary = (List<?>) data.get("commissionSummary");

        double sumFromRows = summary.stream()
                .mapToDouble(r -> ((Number) ((Map<?, ?>) r).get("netCommission")).doubleValue())
                .sum();

        double lifetimeNet = ((Number) data.get("lifetimeNetCommission")).doubleValue();

        assertEquals(sumFromRows, lifetimeNet, 0.001,
                "lifetimeNetCommission must equal SUM(commissionSummary[i].netCommission)");
        assertEquals(EXPECTED_NET.doubleValue(), lifetimeNet, 0.001,
                "lifetimeNetCommission must equal the expected net: " + EXPECTED_NET);
    }
}
