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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A12 integration tests — agent commission statement export (SPEC §2.6).
 *
 * Setup (@BeforeAll):
 *   - test user, test agent
 *   - OPEN commission period (far-future 2035-01 to avoid cross-test pollution)
 *   - one commission entry (op = 90.00)
 *   The period stays OPEN — the export endpoint is read-only and imposes no
 *   status restriction.
 *
 * Tests:
 *   a. GET .../statement/export without JWT → 401
 *   b. GET .../statement/export?format=pdf  → 200; text/html; body contains agentCode or fullName
 *   c. GET .../statement/export?format=csv  → 200; text/csv; body starts with "orderId" header
 *   d. app.js contains the string "statement/export" (grep verify)
 *
 * Teardown: entries → period → agent → user
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA12Test {

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
        testUser.setEmail("a12-test-" + sfx + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A12 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        testAgent = new Agent();
        testAgent.setAgentCode("AGT-A12-" + sfx.substring(sfx.length() - 6));
        testAgent.setFullName("A12 Test Agent " + sfx);
        testAgent.setContactNumber("09170000012");
        testAgent.setTerritory("Test Territory A12");
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);

        CommissionPeriod period = new CommissionPeriod();
        period.setPeriodCode("A12-2035-01-" + sfx.substring(sfx.length() - 6));
        period.setStartDate(LocalDate.of(2035, 1, 1));
        period.setEndDate(LocalDate.of(2035, 1, 31));
        period.setCreatedBy(testUser.getId());
        period = periodRepository.save(period);
        testPeriodId = period.getId();

        CommissionEntry entry = new CommissionEntry();
        entry.setPeriodId(testPeriodId);
        entry.setAgentId(testAgent.getId());
        entry.setOrderDate(LocalDate.of(2035, 1, 15));
        entry.setProductName("A12 Test Product");
        entry.setQuantity(3);
        entry.setBasePrice(new BigDecimal("200.00"));
        entry.setOpRate(new BigDecimal("0.1500"));
        entry.setOpAmount(new BigDecimal("90.00"));
        entryRepository.save(entry);
    }

    @AfterAll
    void tearDownAll() {
        entryRepository.deleteAll(entryRepository.findByPeriodId(testPeriodId));
        if (testPeriodId != null) periodRepository.deleteById(testPeriodId);
        agentRepository.delete(testAgent);
        userRepository.delete(testUser);
    }

    // ── A12-a: no JWT → 401 ──────────────────────────────────────────────────

    @Test
    @Order(1)
    void exportStatement_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/commissions/periods/" + testPeriodId
                        + "/agents/" + testAgent.getId() + "/statement/export"))
                .andExpect(status().isUnauthorized());
    }

    // ── A12-b: format=pdf → 200; Content-Type text/html; body has agent info ─

    @Test
    @Order(2)
    void exportStatement_pdf_returns200WithHtmlContainingAgentInfo() throws Exception {
        String body = mockMvc.perform(get("/api/commissions/periods/" + testPeriodId
                        + "/agents/" + testAgent.getId() + "/statement/export?format=pdf")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(testAgent.getAgentCode()) || body.contains(testAgent.getFullName()),
                "PDF body must contain agentCode or fullName; got length=" + body.length());
    }

    // ── A12-c: format=csv → 200; Content-Type text/csv; body has header row ──

    @Test
    @Order(3)
    void exportStatement_csv_returns200WithCsvHeaderRow() throws Exception {
        String body = mockMvc.perform(get("/api/commissions/periods/" + testPeriodId
                        + "/agents/" + testAgent.getId() + "/statement/export?format=csv")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("orderId"),
                "CSV body must contain 'orderId' header; got: " + body.substring(0, Math.min(200, body.length())));
    }

    // ── A12-d: app.js contains "statement/export" ─────────────────────────────

    @Test
    @Order(4)
    void appJs_containsStatementExportString() throws Exception {
        java.io.File appJs = new java.io.File("../rrbm_frontend/rrbm-frontend/js/app.js");
        assertTrue(appJs.exists(), "app.js must exist at expected relative path");
        String content = new String(java.nio.file.Files.readAllBytes(appJs.toPath()));
        assertTrue(content.contains("statement/export"),
                "app.js must reference 'statement/export'");
    }
}
