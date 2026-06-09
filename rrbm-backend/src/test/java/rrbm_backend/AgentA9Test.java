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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A9 integration tests — commission payment record (SPEC §2.5 step 4).
 *
 * Setup (@BeforeAll):
 *   - test user (with BCrypt security key), test agent
 *   - OPEN period (2031-06, far future to avoid cross-test pollution)
 *   - one commission entry (op=50.00)
 *   (Period is NOT released in @BeforeAll — tests (b) and (c) control the lifecycle.)
 *
 * Tests (ordered):
 *   a. POST pay without JWT → 401
 *   b. POST pay on OPEN period (not yet RELEASED) → 400
 *   c. Close + release, then POST pay with valid body → 200; status=PAID
 *   d. POST pay again (already PAID) → 400
 *
 * Teardown (@AfterAll):
 *   agent_commissions → entries → period → agent → user
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA9Test {

    @Autowired private MockMvc                    mockMvc;
    @Autowired private UserRepository             userRepository;
    @Autowired private AgentRepository            agentRepository;
    @Autowired private AgentCommissionRepository  agentCommissionRepository;
    @Autowired private CommissionPeriodRepository periodRepository;
    @Autowired private CommissionEntryRepository  entryRepository;
    @Autowired private JwtUtil                    jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();

    private User   testUser;
    private String jwt;
    private Agent  testAgent;
    private Long   testPeriodId;

    private static final String     TEST_SECURITY_KEY = "a9TestSecurityKey";
    private static final BigDecimal ENTRY_OP          = new BigDecimal("50.00");

    @BeforeAll
    void setUpAll() {
        String sfx = String.valueOf(System.currentTimeMillis());

        testUser = new User();
        testUser.setEmail("a9-test-" + sfx + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A9 Test Admin");
        testUser.setRole("ADMIN");
        testUser.setAdminSecurityKey(new BCryptPasswordEncoder().encode(TEST_SECURITY_KEY));
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        testAgent = new Agent();
        testAgent.setAgentCode("AGENT-A9-" + sfx.substring(sfx.length() - 4));
        testAgent.setFullName("A9 Test Agent " + sfx);
        testAgent.setContactNumber("09170000009");
        testAgent.setTerritory("Test Territory A9");
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);

        CommissionPeriod period = new CommissionPeriod();
        period.setPeriodCode("A9-2031-06-" + sfx.substring(sfx.length() - 6));
        period.setStartDate(LocalDate.of(2031, 6, 1));
        period.setEndDate(LocalDate.of(2031, 6, 30));
        period.setCreatedBy(testUser.getId());
        period = periodRepository.save(period);
        testPeriodId = period.getId();

        CommissionEntry entry = new CommissionEntry();
        entry.setPeriodId(testPeriodId);
        entry.setAgentId(testAgent.getId());
        entry.setOrderDate(LocalDate.of(2031, 6, 15));
        entry.setProductName("A9 Widget");
        entry.setQuantity(2);
        entry.setOpAmount(ENTRY_OP);
        entryRepository.save(entry);
    }

    @AfterAll
    void tearDownAll() {
        // FK order: agent_commissions → entries → period → agent → user
        agentCommissionRepository.deleteAll(agentCommissionRepository.findByPeriodId(testPeriodId));
        entryRepository.deleteAll(entryRepository.findByPeriodId(testPeriodId));
        if (testPeriodId != null) periodRepository.deleteById(testPeriodId);
        agentRepository.delete(testAgent);
        userRepository.delete(testUser);
    }

    // ── A9-a: POST pay without JWT → 401 ─────────────────────────────────────

    @Test
    @Order(1)
    void recordPayment_noJwt_returns401() throws Exception {
        Map<String, String> body = Map.of(
                "paymentMethod",    "Cash",
                "paymentDate",      "2031-06-30",
                "adminSecurityKey", TEST_SECURITY_KEY
        );
        mockMvc.perform(post("/api/commissions/periods/" + testPeriodId
                        + "/agents/" + testAgent.getId() + "/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    // ── A9-b: POST pay on OPEN (not RELEASED) period → 400 ───────────────────

    @Test
    @Order(2)
    void recordPayment_openPeriod_returns400() throws Exception {
        Map<String, String> body = Map.of(
                "paymentMethod",    "Cash",
                "paymentDate",      "2031-06-30",
                "adminSecurityKey", TEST_SECURITY_KEY
        );
        String resp = mockMvc.perform(post("/api/commissions/periods/" + testPeriodId
                        + "/agents/" + testAgent.getId() + "/pay")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        String msg = (String) data.get("message");
        assertTrue(msg != null && msg.contains("RELEASED"),
                "Error message must mention RELEASED requirement; got: " + msg);
    }

    // ── A9-c: Close + release, then pay → 200; status = PAID ─────────────────

    @Test
    @Order(3)
    void recordPayment_validBody_returns200WithPaidStatus() throws Exception {
        // Close the period.
        mockMvc.perform(post("/api/commissions/periods/" + testPeriodId + "/close")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());

        // Release the period (creates agent_commissions row).
        Map<String, String> releaseBody = Map.of("adminSecurityKey", TEST_SECURITY_KEY);
        mockMvc.perform(post("/api/commissions/periods/" + testPeriodId + "/release")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(releaseBody)))
                .andExpect(status().isOk());

        // Record payment.
        Map<String, String> payBody = Map.of(
                "paymentMethod",    "GCash",
                "paymentReference", "GC-2031-123456",
                "paymentDate",      "2031-06-30",
                "adminSecurityKey", TEST_SECURITY_KEY
        );
        String resp = mockMvc.perform(post("/api/commissions/periods/" + testPeriodId
                        + "/agents/" + testAgent.getId() + "/pay")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payBody)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        assertEquals("PAID",           data.get("status"),           "status must be PAID");
        assertEquals("GCash",          data.get("paymentMethod"),    "paymentMethod must match");
        assertEquals("GC-2031-123456", data.get("paymentReference"), "paymentReference must match");
        assertEquals("2031-06-30",     data.get("paymentDate"),      "paymentDate must match");
        assertNotNull(data.get("paidAt"), "paidAt must be set");

        // Verify the agent_commissions row was updated in the DB.
        AgentCommission ac = agentCommissionRepository
                .findByAgentIdAndPeriodId(testAgent.getId(), testPeriodId)
                .orElse(null);
        assertNotNull(ac, "agent_commissions row must exist");
        assertEquals("PAID",  ac.getStatus(),         "DB status must be PAID");
        assertEquals("GCash", ac.getPaymentMethod(),  "DB paymentMethod must be GCash");
        assertNotNull(ac.getPaidAt(),                  "DB paidAt must be set");
    }

    // ── A9-d: POST pay again (already PAID) → 400 ────────────────────────────

    @Test
    @Order(4)
    void recordPayment_alreadyPaid_returns400() throws Exception {
        Map<String, String> payBody = Map.of(
                "paymentMethod",    "Cash",
                "paymentDate",      "2031-06-30",
                "adminSecurityKey", TEST_SECURITY_KEY
        );
        String resp = mockMvc.perform(post("/api/commissions/periods/" + testPeriodId
                        + "/agents/" + testAgent.getId() + "/pay")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payBody)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        String msg = (String) data.get("message");
        assertTrue(msg != null && msg.toLowerCase().contains("paid"),
                "Error message must mention already paid; got: " + msg);
    }
}
