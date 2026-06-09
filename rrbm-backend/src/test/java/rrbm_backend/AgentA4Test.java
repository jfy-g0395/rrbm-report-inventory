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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A4 integration tests — commission release workflow, per-agent statements,
 * and bonus/deduction adjustments.
 *
 * Setup:
 *   @BeforeAll — create test user (with BCrypt security key), test agent;
 *                insert an OPEN commission period and one entry directly via repos.
 *
 * Test order (all share testPeriodId):
 *   1. no-JWT → 401
 *   2. valid body → 201
 *   3. close + release period, then POST adjustment → 400
 *   4. GET statement → 200; structure + netCommission correct
 *   5. GET statement → entries are RELEASED after period release
 *
 *   @AfterAll — delete adjustments → entries → period → agent → user
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA4Test {

    @Autowired private MockMvc                         mockMvc;
    @Autowired private UserRepository                  userRepository;
    @Autowired private AgentRepository                 agentRepository;
    @Autowired private AgentCommissionRepository       agentCommissionRepository;
    @Autowired private CommissionPeriodRepository      periodRepository;
    @Autowired private CommissionEntryRepository       entryRepository;
    @Autowired private CommissionAdjustmentRepository  adjustmentRepository;
    @Autowired private JwtUtil                         jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();
    private       User         testUser;
    private       String       jwt;
    private       Agent        testAgent;
    private       Long         testPeriodId;
    private final List<Long>   createdPeriodIds = new ArrayList<>();

    private static final String     TEST_SECURITY_KEY = "a4TestSecurityKey";
    // Entry inserted directly: basePrice=40, opRate=0.15, qty=2 → opAmount=12.00
    private static final BigDecimal ENTRY_OP          = new BigDecimal("12.00");
    // BONUS added in test b
    private static final BigDecimal BONUS_AMOUNT      = new BigDecimal("50.00");

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());

        testUser = new User();
        testUser.setEmail("a4-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A4 Test Admin");
        testUser.setRole("ADMIN");
        testUser.setAdminSecurityKey(new BCryptPasswordEncoder().encode(TEST_SECURITY_KEY));
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        testAgent = new Agent();
        testAgent.setAgentCode("AGENT-A4-" + suffix.substring(suffix.length() - 4));
        testAgent.setFullName("A4 Test Agent " + suffix);
        testAgent.setContactNumber("09170000004");
        testAgent.setTerritory("Test Territory A4");
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);

        // OPEN period via repo directly — unique code avoids overlap check
        CommissionPeriod period = new CommissionPeriod();
        period.setPeriodCode("A4-" + suffix.substring(suffix.length() - 8));
        period.setStartDate(LocalDate.of(2027, 6, 1));
        period.setEndDate(LocalDate.of(2027, 6, 30));
        period.setCreatedBy(testUser.getId());
        period = periodRepository.save(period);
        testPeriodId = period.getId();
        createdPeriodIds.add(testPeriodId);

        // One commission entry directly — gives us a totalOp to verify in statement tests
        CommissionEntry entry = new CommissionEntry();
        entry.setPeriodId(testPeriodId);
        entry.setAgentId(testAgent.getId());
        entry.setOrderDate(LocalDate.of(2027, 6, 5));
        entry.setProductName("A4 Test Widget");
        entry.setQuantity(2);
        entry.setBasePrice(new BigDecimal("40.00"));
        entry.setOpRate(new BigDecimal("0.1500"));
        entry.setOpAmount(ENTRY_OP);
        entryRepository.save(entry);
    }

    @AfterAll
    void tearDownAll() {
        // FK order: agent_commissions → adjustments → entries → periods → agent → user
        createdPeriodIds.forEach(pid ->
                agentCommissionRepository.deleteAll(agentCommissionRepository.findByPeriodId(pid)));
        adjustmentRepository.deleteAll(adjustmentRepository.findByPeriodId(testPeriodId));
        entryRepository.deleteAll(entryRepository.findByPeriodId(testPeriodId));
        createdPeriodIds.forEach(periodRepository::deleteById);
        agentRepository.delete(testAgent);
        userRepository.delete(testUser);
    }

    // ── A4-a: POST adjustments without JWT → 401 ─────────────────────────────

    @Test
    @Order(1)
    void addAdjustment_noJwt_returns401() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId",        testAgent.getId());
        body.put("adjustmentType", "BONUS");
        body.put("amount",         "10.00");
        body.put("reason",         "Test");

        mockMvc.perform(post("/api/commissions/periods/" + testPeriodId + "/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    // ── A4-b: valid body → 201; adjustmentType, amount, reason in response ───

    @Test
    @Order(2)
    void addAdjustment_validBody_returns201WithCorrectFields() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId",        testAgent.getId());
        body.put("adjustmentType", "BONUS");
        body.put("amount",         BONUS_AMOUNT.toPlainString());
        body.put("reason",         "A4 test bonus");

        String resp = mockMvc.perform(post("/api/commissions/periods/" + testPeriodId + "/adjustments")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        assertEquals("BONUS",         data.get("adjustmentType"), "adjustmentType must be BONUS");
        assertEquals("A4 test bonus", data.get("reason"),         "reason must match");
        double amt = ((Number) data.get("amount")).doubleValue();
        assertEquals(BONUS_AMOUNT.doubleValue(), amt, 0.001, "amount must match");
    }

    // ── A4-c: POST adjustment on RELEASED period → 400 ───────────────────────
    //   Also exercises close + release so tests d and e see a RELEASED period.

    @Test
    @Order(3)
    void addAdjustment_onReleasedPeriod_returns400() throws Exception {
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

        // Attempt to add adjustment to the now-RELEASED period → 400
        Map<String, Object> adjBody = new HashMap<>();
        adjBody.put("agentId",        testAgent.getId());
        adjBody.put("adjustmentType", "BONUS");
        adjBody.put("amount",         "25.00");
        adjBody.put("reason",         "Should be rejected");

        mockMvc.perform(post("/api/commissions/periods/" + testPeriodId + "/adjustments")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(adjBody)))
                .andExpect(status().isBadRequest());
    }

    // ── A4-d: GET statement → 200; required keys present; netCommission correct

    @Test
    @Order(4)
    void getStatement_returns200WithCorrectStructureAndNetCommission() throws Exception {
        String resp = mockMvc.perform(
                        get("/api/commissions/periods/" + testPeriodId
                            + "/agents/" + testAgent.getId() + "/statement")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        assertTrue(data.containsKey("period"),      "Response must contain 'period'");
        assertTrue(data.containsKey("agent"),       "Response must contain 'agent'");
        assertTrue(data.containsKey("entries"),     "Response must contain 'entries'");
        assertTrue(data.containsKey("adjustments"), "Response must contain 'adjustments'");
        assertTrue(data.containsKey("summary"),     "Response must contain 'summary'");

        // netCommission = totalOp(12.00) + totalAdjustments(50.00) = 62.00
        Map<?, ?> summary = (Map<?, ?>) data.get("summary");
        double netCommission = ((Number) summary.get("netCommission")).doubleValue();
        assertEquals(62.0, netCommission, 0.01,
                "netCommission must equal totalOp + totalAdjustments = 12.00 + 50.00 = 62.00");
    }

    // ── A4-e: GET statement after release → entry status = RELEASED ──────────

    @Test
    @Order(5)
    void getStatement_afterRelease_entryStatusIsReleased() throws Exception {
        String resp = mockMvc.perform(
                        get("/api/commissions/periods/" + testPeriodId
                            + "/agents/" + testAgent.getId() + "/statement")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        List<?> entries = (List<?>) data.get("entries");
        assertFalse(entries.isEmpty(), "Statement must contain at least one entry");

        Map<?, ?> firstEntry = (Map<?, ?>) entries.get(0);
        assertEquals("RELEASED", firstEntry.get("status"),
                "Entry status must be RELEASED after period has been released");
    }
}
