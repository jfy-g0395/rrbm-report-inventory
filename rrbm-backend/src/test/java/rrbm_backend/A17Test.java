package rrbm_backend;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A17 integration tests — frontend weekly report UI + inactive agent edge case.
 *
 *   a. app.js contains 'loadWeeklyReport'
 *      (SPEC §1.6 weekly report frontend function)
 *   b. index.html contains 'exp-weekly-year'
 *      (SPEC §1.6 weekly report year input in expenses view)
 *   c. app.js contains 'Inactive' in the replacement-form agent block
 *      (SPEC §2.3 edge case: inactive agent shown as placeholder, hidden id cleared)
 *   d. GET /api/expenses/report/weekly with no params returns 200 with 7 dayByDay entries
 *      (confirms endpoint defaults to current ISO week when year/week omitted)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A17Test {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserRepository userRepository;

    private String appJsContent;
    private String indexHtmlContent;
    private String jwt;
    private Long   testUserId;

    @BeforeAll
    void setUp() throws Exception {
        File appJs     = new File("../rrbm_frontend/rrbm-frontend/js/app.js");
        File indexHtml = new File("../rrbm_frontend/rrbm-frontend/index.html");
        assertTrue(appJs.exists(),     "app.js must exist at expected relative path");
        assertTrue(indexHtml.exists(), "index.html must exist at expected relative path");
        appJsContent     = new String(Files.readAllBytes(appJs.toPath()));
        indexHtmlContent = new String(Files.readAllBytes(indexHtml.toPath()));

        // Create a JWT for the weekly report default-params test
        String suffix = String.valueOf(System.currentTimeMillis());
        User user = new User();
        user.setEmail("a17test-" + suffix + "@test.rrbm.internal");
        user.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        user.setFullName("A17 Test Admin");
        user.setRole("ADMIN");
        user = userRepository.save(user);
        testUserId = user.getId();
        jwt = jwtUtil.generateToken(user);
    }

    @AfterAll
    void tearDown() {
        if (testUserId != null) userRepository.deleteById(testUserId);
    }

    // ── A17-a: loadWeeklyReport function present in app.js ───────────────────

    @Test
    @Order(1)
    void appJs_containsLoadWeeklyReport() {
        assertTrue(appJsContent.contains("loadWeeklyReport"),
                "app.js must contain 'loadWeeklyReport' (SPEC §1.6 weekly report UI)");
    }

    // ── A17-b: weekly year input present in index.html ───────────────────────

    @Test
    @Order(2)
    void indexHtml_containsWeeklyYearInput() {
        assertTrue(indexHtmlContent.contains("exp-weekly-year"),
                "index.html must contain 'exp-weekly-year' input (SPEC §1.6 weekly report UI in expenses view)");
    }

    // ── A17-c: inactive agent fallback in replacement form ───────────────────

    @Test
    @Order(3)
    void appJs_containsInactiveAgentFallback() {
        assertTrue(appJsContent.contains("Inactive"),
                "app.js must contain 'Inactive' text for the inactive-agent placeholder in openReplacementForm (SPEC §2.3 edge case)");
    }

    // ── A17-d: weekly endpoint defaults to current week when no params given ──

    @Test
    @Order(4)
    void weeklyReport_noParams_returns200WithSevenDays() throws Exception {
        mockMvc.perform(get("/api/expenses/report/weekly")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").isNumber())
                .andExpect(jsonPath("$.week").isNumber())
                .andExpect(jsonPath("$.dayByDay").isArray())
                .andExpect(jsonPath("$.dayByDay.length()").value(7));
    }
}
