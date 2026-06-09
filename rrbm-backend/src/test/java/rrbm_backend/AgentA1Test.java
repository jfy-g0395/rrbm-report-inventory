package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A1 integration tests — agent registry CRUD endpoints.
 *
 * All tests run against the live PostgreSQL database (Flyway-migrated to V53).
 *
 * Lifecycle:
 *   @BeforeAll  — create test user; generate JWT
 *   @AfterAll   — delete agents and user created by these tests
 *
 * Tests run in declaration order via @TestMethodOrder so later tests can
 * rely on state (agent IDs) captured by earlier ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA1Test {

    @Autowired private MockMvc         mockMvc;
    @Autowired private UserRepository  userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private JwtUtil         jwtUtil;

    private final ObjectMapper  mapper         = new ObjectMapper();
    private User                testUser;
    private String              jwt;
    private final List<Long>    createdAgentIds = new ArrayList<>();

    // Agent IDs captured across ordered tests
    private Long agent1Id;
    private Long agent2Id;

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());
        testUser = new User();
        testUser.setEmail("a1-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A1 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);
    }

    @AfterAll
    void tearDownAll() {
        createdAgentIds.forEach(id -> agentRepository.deleteById(id));
        if (testUser != null) userRepository.delete(testUser);
    }

    private Map<String, Object> validAgentBody(String suffix) {
        return Map.of(
                "fullName",       "Test Agent " + suffix,
                "contactNumber",  "09171234" + suffix,
                "territory",      "Metro Manila",
                "email",          "agent" + suffix + "@test.rrbm.internal"
        );
    }

    // ── A1-a: POST without JWT → 401 ──────────────────────────────────────

    @Test
    @Order(1)
    void postAgent_noJwt_returns401() throws Exception {
        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validAgentBody("A"))))
                .andExpect(status().isUnauthorized());
    }

    // ── A1-b: POST with valid JWT → 201; agentCode matches AGENT-YYYY-NNNN ─

    @Test
    @Order(2)
    void postAgent_validJwt_returns201WithCorrectCodePattern() throws Exception {
        String body = mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validAgentBody("001"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);
        String agentCode = (String) resp.get("agentCode");
        assertNotNull(agentCode, "agentCode must be present in response");
        assertTrue(agentCode.matches("^AGENT-\\d{4}-\\d{4}$"),
                "agentCode '" + agentCode + "' must match AGENT-YYYY-NNNN");

        agent1Id = ((Number) resp.get("id")).longValue();
        createdAgentIds.add(agent1Id);
    }

    // ── A1-c: Two sequential POSTs → codes are consecutive ────────────────

    @Test
    @Order(3)
    void postAgent_twoSequential_codesAreConsecutive() throws Exception {
        // agent1Id was set in test b; capture its code
        Agent agent1 = agentRepository.findById(agent1Id).orElseThrow();

        String body2 = mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validAgentBody("002"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp2 = mapper.readValue(body2, Map.class);
        String code2 = (String) resp2.get("agentCode");
        assertNotNull(code2);

        agent2Id = ((Number) resp2.get("id")).longValue();
        createdAgentIds.add(agent2Id);

        int seq1 = Integer.parseInt(agent1.getAgentCode().substring(agent1.getAgentCode().lastIndexOf('-') + 1));
        int seq2 = Integer.parseInt(code2.substring(code2.lastIndexOf('-') + 1));
        assertEquals(seq1 + 1, seq2,
                "Second agent sequence " + seq2 + " must be exactly first + 1 (" + (seq1 + 1) + ")");
    }

    // ── A1-d: GET /api/agents → 200; array; contains created agents ───────

    @Test
    @Order(4)
    void getAgents_returns200ArrayContainingCreatedAgents() throws Exception {
        String body = mockMvc.perform(get("/api/agents")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> list = mapper.readValue(body, List.class);
        assertNotNull(list, "Response must be a JSON array");

        List<Long> ids = list.stream()
                .map(e -> ((Number) ((Map<?, ?>) e).get("id")).longValue())
                .toList();

        assertTrue(ids.contains(agent1Id),
                "GET /api/agents must include agent1 (id=" + agent1Id + ")");
        assertTrue(ids.contains(agent2Id),
                "GET /api/agents must include agent2 (id=" + agent2Id + ")");
    }

    // ── A1-e: GET /api/agents/{id} for unknown id → 404 ──────────────────

    @Test
    @Order(5)
    void getAgent_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/agents/999999999")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound());
    }

    // ── A1-f: PUT /api/agents/{id} — update fullName → 200; name changed ──

    @Test
    @Order(6)
    void putAgent_updateFullName_returns200WithUpdatedName() throws Exception {
        String updatedName = "Updated Agent Name";
        String body = mockMvc.perform(put("/api/agents/" + agent1Id)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("fullName", updatedName))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);
        assertEquals(updatedName, resp.get("fullName"),
                "fullName in response must match the updated value");
    }

    // ── A1-g: PATCH /api/agents/{id}/status → 200; status toggled ─────────

    @Test
    @Order(7)
    void patchAgentStatus_returns200WithToggledStatus() throws Exception {
        Agent before = agentRepository.findById(agent1Id).orElseThrow();
        String expectedStatus = "ACTIVE".equals(before.getStatus()) ? "INACTIVE" : "ACTIVE";

        String body = mockMvc.perform(patch("/api/agents/" + agent1Id + "/status")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("status", expectedStatus))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);
        assertEquals(expectedStatus, resp.get("status"),
                "status in response must be " + expectedStatus);
    }
}
