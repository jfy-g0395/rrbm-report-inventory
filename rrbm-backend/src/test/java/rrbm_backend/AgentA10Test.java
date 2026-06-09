package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A10 integration tests — agent list view filters (SPEC §2.2).
 *
 * Covers: territory exact-match, commission min/max range, registration date range,
 * and a grep verify that app.js wires up registeredFrom/registeredTo.
 *
 * Setup (@BeforeAll):
 *   - test user + test agent with a unique territory, registered today.
 *   - No commission periods released → lifetimeNetCommission = 0.
 *
 * Teardown (@AfterAll):
 *   agent → user   (no commission data, so no FK cascade needed)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA10Test {

    @Autowired private MockMvc         mockMvc;
    @Autowired private UserRepository  userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private JwtUtil         jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();

    private User  testUser;
    private String jwt;
    private Agent testAgent;

    // Unique territory unlikely to collide with other test agents.
    private String testTerritory;

    @BeforeAll
    void setUpAll() {
        String sfx = String.valueOf(System.currentTimeMillis());
        testTerritory = "NorthA10-" + sfx;

        testUser = new User();
        testUser.setEmail("a10-test-" + sfx + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A10 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        testAgent = new Agent();
        testAgent.setAgentCode("AGENT-A10-" + sfx.substring(sfx.length() - 4));
        testAgent.setFullName("A10 Test Agent " + sfx);
        testAgent.setContactNumber("09170000010");
        testAgent.setTerritory(testTerritory);
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);
    }

    @AfterAll
    void tearDownAll() {
        agentRepository.delete(testAgent);
        userRepository.delete(testUser);
    }

    // ── A10-a: territory filter returns only agents with that territory ────────

    @Test
    @Order(1)
    void territoryFilter_returns200AndOnlyMatchingAgents() throws Exception {
        String resp = mockMvc.perform(get("/api/agents")
                        .param("territory", testTerritory)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> list = mapper.readValue(resp, List.class);
        assertFalse(list.isEmpty(), "Result must contain at least the test agent");

        for (Object item : list) {
            Map<?, ?> row = (Map<?, ?>) item;
            assertEquals(testTerritory, row.get("territory"),
                    "Every returned agent must have territory=" + testTerritory);
        }

        boolean found = list.stream()
                .anyMatch(i -> testAgent.getId().intValue()
                        == ((Number) ((Map<?, ?>) i).get("id")).intValue());
        assertTrue(found, "Test agent must appear in the territory-filtered result");
    }

    // ── A10-b: commission range filter returns agents with commission in [x, y] ─

    @Test
    @Order(2)
    void commissionRangeFilter_returns200AndAgentsInRange() throws Exception {
        // Test agent has lifetimeNetCommission=0 (no released periods).
        // Filter [0, 0] must include it; all returned agents must be in range.
        String resp = mockMvc.perform(get("/api/agents")
                        .param("minCommission", "0")
                        .param("maxCommission", "0")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> list = mapper.readValue(resp, List.class);

        for (Object item : list) {
            Map<?, ?> row = (Map<?, ?>) item;
            Object lncObj = row.get("lifetimeNetCommission");
            double lnc = lncObj != null ? ((Number) lncObj).doubleValue() : 0.0;
            assertTrue(lnc >= -0.001 && lnc <= 0.001,
                    "All returned agents must have lifetimeNetCommission=0; got: " + lnc);
        }

        boolean testAgentPresent = list.stream()
                .anyMatch(i -> testAgent.getId().intValue()
                        == ((Number) ((Map<?, ?>) i).get("id")).intValue());
        assertTrue(testAgentPresent,
                "Test agent (commission=0) must appear in the [0,0] commission range");
    }

    // ── A10-c: registration date range filter returns agents registered in range ─

    @Test
    @Order(3)
    void registrationDateFilter_returns200AndAgentsInRange() throws Exception {
        String today = LocalDate.now().toString();

        String resp = mockMvc.perform(get("/api/agents")
                        .param("registeredFrom", today)
                        .param("registeredTo",   today)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> list = mapper.readValue(resp, List.class);

        for (Object item : list) {
            Map<?, ?> row = (Map<?, ?>) item;
            String regDate = (String) row.get("registrationDate");
            assertNotNull(regDate, "registrationDate must be present in each row");
            assertEquals(today, regDate,
                    "All returned agents must have registrationDate=" + today);
        }

        boolean found = list.stream()
                .anyMatch(i -> testAgent.getId().intValue()
                        == ((Number) ((Map<?, ?>) i).get("id")).intValue());
        assertTrue(found, "Test agent (registered today) must appear in today's date range");
    }

    // ── A10-d: app.js references registeredFrom or registeredTo ──────────────

    @Test
    @Order(4)
    void appJs_containsRegisteredFromOrRegisteredTo() throws Exception {
        java.io.File appJs = new java.io.File(
                "../rrbm_frontend/rrbm-frontend/js/app.js");
        assertTrue(appJs.exists(), "app.js must exist at expected relative path");
        String content = new String(java.nio.file.Files.readAllBytes(appJs.toPath()));
        assertTrue(content.contains("registeredFrom") || content.contains("registeredTo"),
                "app.js must reference 'registeredFrom' or 'registeredTo'");
    }
}
