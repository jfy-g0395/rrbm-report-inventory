package rrbm_backend;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A14 integration tests — SPEC §1.3 (quick-entry presets + full expense form)
 * and SPEC §2.3 ("Register new agent" shortcut modal from order form).
 *
 * All tests are static file-content checks (no HTTP needed) — same pattern as
 * A7-d, A10-d, A12-d. No DB state created; no teardown required.
 *
 * Tests:
 *   a. app.js contains 'applyExpensePreset' (quick-entry function wired)
 *   b. index.html contains 'exp-primary-cat' (category select added to expense form)
 *   c. index.html contains 'modal-register-agent' (register agent modal exists)
 *   d. app.js contains 'openRegisterAgentModal' (register agent function exists)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A14Test {

    private String appJsContent;
    private String indexHtmlContent;

    @BeforeAll
    void readFrontendFiles() throws Exception {
        File appJs   = new File("../rrbm_frontend/rrbm-frontend/js/app.js");
        File indexHtml = new File("../rrbm_frontend/rrbm-frontend/index.html");
        assertTrue(appJs.exists(), "app.js must exist at expected relative path");
        assertTrue(indexHtml.exists(), "index.html must exist at expected relative path");
        appJsContent    = new String(Files.readAllBytes(appJs.toPath()));
        indexHtmlContent = new String(Files.readAllBytes(indexHtml.toPath()));
    }

    // ── A14-a: quick-entry preset function wired in app.js ────────────────────

    @Test
    @Order(1)
    void appJs_containsApplyExpensePreset() {
        assertTrue(appJsContent.contains("applyExpensePreset"),
                "app.js must contain 'applyExpensePreset' (SPEC §1.3 quick-entry buttons)");
    }

    // ── A14-b: category select present in expense entry form ─────────────────

    @Test
    @Order(2)
    void indexHtml_expenseFormContainsPrimaryCategorySelect() {
        assertTrue(indexHtmlContent.contains("exp-primary-cat"),
                "index.html must contain 'exp-primary-cat' select (expense category field)");
    }

    // ── A14-c: register-new-agent modal exists in index.html ─────────────────

    @Test
    @Order(3)
    void indexHtml_containsRegisterAgentModal() {
        assertTrue(indexHtmlContent.contains("modal-register-agent"),
                "index.html must contain 'modal-register-agent' (SPEC §2.3 quick modal)");
    }

    // ── A14-d: open-register-agent function exists in app.js ─────────────────

    @Test
    @Order(4)
    void appJs_containsOpenRegisterAgentModal() {
        assertTrue(appJsContent.contains("openRegisterAgentModal"),
                "app.js must contain 'openRegisterAgentModal' function (SPEC §2.3)");
    }
}
