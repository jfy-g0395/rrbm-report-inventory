package rrbm_backend;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * U5 — import detail modal.
 *
 * Tests:
 *   a. index.html contains "modal-import-detail" (modal added in U5)
 *   b. app.js contains "openImportDetailModal" (row-click handler wired in U5)
 *   c. GET /api/import/history/batch without JWT → 401
 *   d. GET /api/import/history/batch?date=<date> with valid JWT → 200;
 *      response has "orders" and "expenses" keys; both are arrays
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportU5Test {

    @Autowired private MockMvc        mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil        jwtUtil;

    private User   testUser;
    private String jwt;

    private String appJsContent;
    private String indexHtmlContent;

    @BeforeAll
    void setUp() throws Exception {
        File appJs     = new File("../rrbm_frontend/rrbm-frontend/js/app.js");
        File indexHtml = new File("../rrbm_frontend/rrbm-frontend/index.html");
        assertTrue(appJs.exists(),     "app.js must exist at expected relative path");
        assertTrue(indexHtml.exists(), "index.html must exist at expected relative path");
        appJsContent     = new String(Files.readAllBytes(appJs.toPath()));
        indexHtmlContent = new String(Files.readAllBytes(indexHtml.toPath()));

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        testUser = new User();
        testUser.setEmail("u5test-" + unique + "@test.invalid");
        testUser.setFullName("U5 Test User " + unique);
        testUser.setUsername("u5test-" + unique);
        testUser.setPasswordHash(encoder.encode("password123"));
        testUser.setRole("ACCOUNTING");
        testUser.setAdminSecurityKey(encoder.encode("seckey123"));
        testUser.setStatus("ACTIVE");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);
    }

    @AfterAll
    void tearDown() {
        if (testUser != null) userRepository.delete(testUser);
    }

    // U5-a: modal-import-detail present in index.html
    @Test
    @Order(1)
    void indexHtml_containsModalImportDetail() {
        assertTrue(indexHtmlContent.contains("modal-import-detail"),
                "index.html must contain 'modal-import-detail' (import batch detail modal — U5)");
    }

    // U5-b: openImportDetailModal handler wired in app.js
    @Test
    @Order(2)
    void appJs_containsOpenImportDetailModal() {
        assertTrue(appJsContent.contains("openImportDetailModal"),
                "app.js must contain 'openImportDetailModal' (import history row-click handler — U5)");
    }

    // U5-c: GET /api/import/history/batch without JWT → 401
    @Test
    @Order(3)
    void getBatchDetail_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/import/history/batch").param("date", "2026-01-01"))
               .andExpect(status().isUnauthorized());
    }

    // U5-d: GET /api/import/history/batch with valid JWT → 200; has orders + expenses arrays
    @Test
    @Order(4)
    void getBatchDetail_validJwt_returns200WithOrdersAndExpenses() throws Exception {
        mockMvc.perform(get("/api/import/history/batch")
                       .param("date", "2026-01-01")
                       .header("Authorization", "Bearer " + jwt))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.orders").isArray())
               .andExpect(jsonPath("$.expenses").isArray());
    }
}
