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
 * U4 — import frontend UI.
 *
 * Tests:
 *   a. index.html contains "nav-import" (nav button added)
 *   b. app.js contains "loadImportHistory" (history function wired)
 *   c. app.js contains "Imported" badge render string in order history
 *   d. GET /api/import/history with valid ACCOUNTING JWT → 200; response is array (regression guard)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportU4Test {

    @Autowired private MockMvc        mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil        jwtUtil;

    private User   testUser;
    private String jwt;

    private String appJsContent;
    private String indexHtmlContent;

    @BeforeAll
    void setUp() throws Exception {
        // Read frontend files
        File appJs     = new File("../rrbm_frontend/rrbm-frontend/js/app.js");
        File indexHtml = new File("../rrbm_frontend/rrbm-frontend/index.html");
        assertTrue(appJs.exists(),     "app.js must exist at expected relative path");
        assertTrue(indexHtml.exists(), "index.html must exist at expected relative path");
        appJsContent     = new String(Files.readAllBytes(appJs.toPath()));
        indexHtmlContent = new String(Files.readAllBytes(indexHtml.toPath()));

        // Create an ACCOUNTING user for the API regression test
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        testUser = new User();
        testUser.setEmail("u4test-" + unique + "@test.invalid");
        testUser.setFullName("U4 Test User " + unique);
        testUser.setUsername("u4test-" + unique);
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

    // U4-a: nav-import button present in index.html
    @Test
    @Order(1)
    void indexHtml_containsNavImport() {
        assertTrue(indexHtmlContent.contains("nav-import"),
                "index.html must contain 'nav-import' (Import nav button — SPEC §3.6)");
    }

    // U4-b: loadImportHistory function wired in app.js
    @Test
    @Order(2)
    void appJs_containsLoadImportHistory() {
        assertTrue(appJsContent.contains("loadImportHistory"),
                "app.js must contain 'loadImportHistory' calling /api/import/history (SPEC §3.6)");
    }

    // U4-c: Imported badge rendered in order history when order.imported == true
    @Test
    @Order(3)
    void appJs_containsImportedBadgeInOrderHistory() {
        assertTrue(appJsContent.contains("badge-info") && appJsContent.contains("Imported"),
                "app.js must contain badge-info + Imported badge string in order history renderer (SPEC §3.6)");
    }

    // U4-d: GET /api/import/history with valid ACCOUNTING JWT → 200; response is array (regression guard)
    @Test
    @Order(4)
    void importHistory_validAccountingJwt_returns200Array() throws Exception {
        mockMvc.perform(get("/api/import/history")
                       .header("Authorization", "Bearer " + jwt))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray());
    }
}
