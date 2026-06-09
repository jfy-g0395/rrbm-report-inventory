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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A7 integration tests — lifetimeNetCommission in agent list/detail, and
 * app.js grep for the "lifetimeNetCommission" key (frontend surface area).
 *
 * Setup (@BeforeAll):
 *   - test user (with BCrypt security key), test agent
 *   - OPEN period (2030-03, far future to avoid cross-test pollution)
 *   - one commission entry (op=30.00)
 *
 * Tests (ordered):
 *   a. GET /api/agents → items contain "lifetimeNetCommission" key
 *   b. GET /api/agents/{id} → response contains "lifetimeNetCommission" key
 *   c. After period release, GET /api/agents → agent's lifetimeNetCommission ≥ netCommission
 *   d. app.js contains the string "lifetimeNetCommission" (grep verify)
 *
 * Teardown (@AfterAll):
 *   agent_commissions → entries → period → agent → user
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA7Test {

    @Autowired private MockMvc                        mockMvc;
    @Autowired private UserRepository                 userRepository;
    @Autowired private AgentRepository                agentRepository;
    @Autowired private AgentCommissionRepository      agentCommissionRepository;
    @Autowired private CommissionPeriodRepository     periodRepository;
    @Autowired private CommissionEntryRepository      entryRepository;
    @Autowired private CommissionAdjustmentRepository adjustmentRepository;
    @Autowired private JwtUtil                        jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();

    private User   testUser;
    private String jwt;
    private Agent  testAgent;
    private Long   testPeriodId;

    private static final String     TEST_SECURITY_KEY = "a7TestSecurityKey";
    private static final BigDecimal ENTRY_OP          = new BigDecimal("30.00");

    @BeforeAll
    void setUpAll() {
        String sfx = String.valueOf(System.currentTimeMillis());

        testUser = new User();
        testUser.setEmail("a7-test-" + sfx + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A7 Test Admin");
        testUser.setRole("ADMIN");
        testUser.setAdminSecurityKey(new BCryptPasswordEncoder().encode(TEST_SECURITY_KEY));
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        testAgent = new Agent();
        testAgent.setAgentCode("AGENT-A7-" + sfx.substring(sfx.length() - 4));
        testAgent.setFullName("A7 Test Agent " + sfx);
        testAgent.setContactNumber("09170000007");
        testAgent.setTerritory("Test Territory A7");
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);

        // OPEN period in far future to avoid overlap with any other test data.
        CommissionPeriod period = new CommissionPeriod();
        period.setPeriodCode("A7-2030-03-" + sfx.substring(sfx.length() - 6));
        period.setStartDate(LocalDate.of(2030, 3, 1));
        period.setEndDate(LocalDate.of(2030, 3, 31));
        period.setCreatedBy(testUser.getId());
        period = periodRepository.save(period);
        testPeriodId = period.getId();

        CommissionEntry entry = new CommissionEntry();
        entry.setPeriodId(testPeriodId);
        entry.setAgentId(testAgent.getId());
        entry.setOrderDate(LocalDate.of(2030, 3, 15));
        entry.setProductName("A7 Widget");
        entry.setQuantity(1);
        entry.setOpAmount(ENTRY_OP);
        entryRepository.save(entry);
    }

    @AfterAll
    void tearDownAll() {
        // FK order per A4/A6 pattern: agent_commissions → adjustments → entries → period → agent → user
        agentCommissionRepository.deleteAll(agentCommissionRepository.findByPeriodId(testPeriodId));
        adjustmentRepository.deleteAll(adjustmentRepository.findByPeriodId(testPeriodId));
        entryRepository.deleteAll(entryRepository.findByPeriodId(testPeriodId));
        if (testPeriodId != null) periodRepository.deleteById(testPeriodId);
        agentRepository.delete(testAgent);
        userRepository.delete(testUser);
    }

    // ── A7-a: GET /api/agents → items contain "lifetimeNetCommission" key ────

    @Test
    @Order(1)
    void getAgents_responseItemsContainLifetimeNetCommissionKey() throws Exception {
        String resp = mockMvc.perform(get("/api/agents")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> list = mapper.readValue(resp, List.class);
        assertFalse(list.isEmpty(), "Response must not be empty");

        // Every item must carry the key (including the test agent).
        for (Object item : list) {
            Map<?, ?> row = (Map<?, ?>) item;
            assertTrue(row.containsKey("lifetimeNetCommission"),
                    "Each agent row must contain 'lifetimeNetCommission'");
        }

        // The test agent's value must be present (ZERO before any release).
        boolean found = list.stream()
                .anyMatch(i -> testAgent.getId().intValue() == ((Number) ((Map<?, ?>) i).get("id")).intValue());
        assertTrue(found, "Test agent must appear in the list");
    }

    // ── A7-b: GET /api/agents/{id} → response contains "lifetimeNetCommission" key ─

    @Test
    @Order(2)
    void getAgent_responseContainsLifetimeNetCommissionKey() throws Exception {
        String resp = mockMvc.perform(get("/api/agents/" + testAgent.getId())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        assertTrue(data.containsKey("lifetimeNetCommission"),
                "GET /api/agents/{id} response must contain 'lifetimeNetCommission'");
    }

    // ── A7-c: After period release, agent's lifetimeNetCommission ≥ netCommission ─

    @Test
    @Order(3)
    void getAgents_afterRelease_agentLifetimeNetCommissionGteNetCommission() throws Exception {
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

        // Read netCommission directly from the agent_commissions row written by releasePeriod.
        AgentCommission ac = agentCommissionRepository
                .findByAgentIdAndPeriodId(testAgent.getId(), testPeriodId)
                .orElse(null);
        assertNotNull(ac, "agent_commissions row must exist after release");
        double periodNetCommission = ac.getNetCommission().doubleValue();

        // GET /api/agents → agent's lifetimeNetCommission must reflect the released period.
        String listResp = mockMvc.perform(get("/api/agents")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> agents = mapper.readValue(listResp, List.class);
        Map<?, ?> agentRow = agents.stream()
                .filter(a -> testAgent.getId().intValue() == ((Number) ((Map<?, ?>) a).get("id")).intValue())
                .map(a -> (Map<?, ?>) a)
                .findFirst()
                .orElse(null);

        assertNotNull(agentRow, "Test agent must appear in the list after release");
        double lifetimeNet = ((Number) agentRow.get("lifetimeNetCommission")).doubleValue();
        assertTrue(lifetimeNet >= periodNetCommission - 0.001,
                "lifetimeNetCommission (" + lifetimeNet + ") must be >= periodNetCommission (" + periodNetCommission + ")");
        assertTrue(lifetimeNet >= ENTRY_OP.doubleValue() - 0.001,
                "lifetimeNetCommission must be >= entry opAmount " + ENTRY_OP);
    }

    // ── A7-d: app.js contains "lifetimeNetCommission" ─────────────────────────

    @Test
    @Order(4)
    void appJs_containsLifetimeNetCommissionString() throws Exception {
        java.io.File appJs = new java.io.File(
                "../rrbm_frontend/rrbm-frontend/js/app.js");
        assertTrue(appJs.exists(), "app.js must exist at expected relative path");
        String content = new String(java.nio.file.Files.readAllBytes(appJs.toPath()));
        assertTrue(content.contains("lifetimeNetCommission"),
                "app.js must reference 'lifetimeNetCommission'");
    }
}
