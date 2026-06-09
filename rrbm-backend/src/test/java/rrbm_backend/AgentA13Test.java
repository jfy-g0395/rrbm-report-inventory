package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A13 integration tests — pending commission in agent list (SPEC §2.2) and
 * frontend agent order-form wiring verification (SPEC §2.3).
 *
 * Setup (@BeforeAll):
 *   - test user, test agent
 *   - OPEN commission period (far-future 2036-01 to avoid cross-test pollution)
 *   - one PENDING commission entry (opAmount = 75.00)
 *
 * Tests:
 *   a. GET /api/agents → response items contain 'pendingCommission' key
 *   b. GET /api/agents/{id} → pendingCommission ≥ 75.00
 *   c. Frontend: app.js references 'agentId' (order request wired to agent registry)
 *   d. Frontend: index.html uses 'field-agent-id' select (not free-text agent-name input)
 *
 * Teardown: entries → period → agent → user
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA13Test {

    @Autowired private MockMvc                    mockMvc;
    @Autowired private UserRepository             userRepository;
    @Autowired private AgentRepository            agentRepository;
    @Autowired private CommissionPeriodRepository periodRepository;
    @Autowired private CommissionEntryRepository  entryRepository;
    @Autowired private JwtUtil                    jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();

    private User   testUser;
    private String jwt;
    private Agent  testAgent;
    private Long   testPeriodId;

    @BeforeAll
    void setUpAll() {
        String sfx = String.valueOf(System.currentTimeMillis());

        testUser = new User();
        testUser.setEmail("a13-test-" + sfx + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A13 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        testAgent = new Agent();
        testAgent.setAgentCode("AGT-A13-" + sfx.substring(sfx.length() - 6));
        testAgent.setFullName("A13 Test Agent " + sfx);
        testAgent.setContactNumber("09170000013");
        testAgent.setTerritory("Test Territory A13");
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);

        CommissionPeriod period = new CommissionPeriod();
        period.setPeriodCode("A13-2036-01-" + sfx.substring(sfx.length() - 6));
        period.setStartDate(LocalDate.of(2036, 1, 1));
        period.setEndDate(LocalDate.of(2036, 1, 31));
        period.setCreatedBy(testUser.getId());
        period = periodRepository.save(period);
        testPeriodId = period.getId();

        CommissionEntry entry = new CommissionEntry();
        entry.setPeriodId(testPeriodId);
        entry.setAgentId(testAgent.getId());
        entry.setOrderDate(LocalDate.of(2036, 1, 15));
        entry.setProductName("A13 Test Product");
        entry.setQuantity(5);
        entry.setBasePrice(new BigDecimal("100.00"));
        entry.setOpRate(new BigDecimal("0.1500"));
        entry.setOpAmount(new BigDecimal("75.00"));
        entryRepository.save(entry);
    }

    @AfterAll
    void tearDownAll() {
        entryRepository.deleteAll(entryRepository.findByPeriodId(testPeriodId));
        if (testPeriodId != null) periodRepository.deleteById(testPeriodId);
        agentRepository.delete(testAgent);
        userRepository.delete(testUser);
    }

    // ── A13-a: GET /api/agents → items contain pendingCommission key ──────────

    @Test
    @Order(1)
    void getAgents_responseItemsContainPendingCommissionKey() throws Exception {
        String body = mockMvc.perform(get("/api/agents")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> agents = mapper.readValue(body, List.class);
        assertFalse(agents.isEmpty(), "GET /api/agents must return at least one agent");
        Map<?, ?> first = (Map<?, ?>) agents.get(0);
        assertTrue(first.containsKey("pendingCommission"),
                "Each agent row must contain 'pendingCommission' key");
    }

    // ── A13-b: GET /api/agents/{id} → pendingCommission ≥ 75.00 ─────────────

    @Test
    @Order(2)
    void getAgent_pendingCommissionReflectsPendingEntries() throws Exception {
        String body = mockMvc.perform(get("/api/agents/" + testAgent.getId())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> m = mapper.readValue(body, Map.class);
        assertTrue(m.containsKey("pendingCommission"),
                "GET /api/agents/{id} must contain 'pendingCommission'");
        BigDecimal pending = new BigDecimal(m.get("pendingCommission").toString());
        assertTrue(pending.compareTo(new BigDecimal("75.00")) >= 0,
                "pendingCommission must be ≥ 75.00 (the PENDING entry's opAmount); got " + pending);
    }

    // ── A13-c: app.js wires agentId into order request ────────────────────────

    @Test
    @Order(3)
    void appJs_containsAgentIdInOrderRequest() throws Exception {
        java.io.File appJs = new java.io.File("../rrbm_frontend/rrbm-frontend/js/app.js");
        assertTrue(appJs.exists(), "app.js must exist at expected relative path");
        String content = new String(java.nio.file.Files.readAllBytes(appJs.toPath()));
        assertTrue(content.contains("agentId"),
                "app.js must reference 'agentId' so agent orders are linked to the registry");
    }

    // ── A13-d: index.html uses agent select (not plain text input) ────────────

    @Test
    @Order(4)
    void indexHtml_agentFieldIsSelectElement() throws Exception {
        java.io.File indexHtml = new java.io.File("../rrbm_frontend/rrbm-frontend/index.html");
        assertTrue(indexHtml.exists(), "index.html must exist at expected relative path");
        String content = new String(java.nio.file.Files.readAllBytes(indexHtml.toPath()));
        assertTrue(content.contains("field-agent-id"),
                "index.html must use 'field-agent-id' select for agent selection");
    }
}
