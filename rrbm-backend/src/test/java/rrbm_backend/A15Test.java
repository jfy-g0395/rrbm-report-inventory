package rrbm_backend;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A15 integration tests — SPEC §2.2 + §2.3 polish pass.
 *
 *   a. app.js contains 'pendingCommission' in the agent list row builder
 *      (SPEC §2.2 list view must show pending commission column)
 *   b. app.js contains 'openEditAgentModal'
 *      (SPEC §2.2 actions must include edit)
 *   c. index.html contains 'field-agent-input'
 *      (SPEC §2.3 searchable dropdown — text input autocomplete, not plain select)
 *   d. index.html contains 'modal-edit-agent'
 *      (edit agent modal added)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A15Test {

    private String appJsContent;
    private String indexHtmlContent;

    @BeforeAll
    void readFrontendFiles() throws Exception {
        File appJs     = new File("../rrbm_frontend/rrbm-frontend/js/app.js");
        File indexHtml = new File("../rrbm_frontend/rrbm-frontend/index.html");
        assertTrue(appJs.exists(),     "app.js must exist at expected relative path");
        assertTrue(indexHtml.exists(), "index.html must exist at expected relative path");
        appJsContent     = new String(Files.readAllBytes(appJs.toPath()));
        indexHtmlContent = new String(Files.readAllBytes(indexHtml.toPath()));
    }

    // ── A15-a: pendingCommission column rendered in agent list table ──────────

    @Test
    @Order(1)
    void appJs_containsPendingCommissionInAgentTable() {
        assertTrue(appJsContent.contains("pendingCommission"),
                "app.js must reference 'pendingCommission' when rendering agent list rows (SPEC §2.2)");
    }

    // ── A15-b: edit agent modal function exists in app.js ────────────────────

    @Test
    @Order(2)
    void appJs_containsOpenEditAgentModal() {
        assertTrue(appJsContent.contains("openEditAgentModal"),
                "app.js must contain 'openEditAgentModal' (SPEC §2.2 edit action)");
    }

    // ── A15-c: agent field in order form is text-input autocomplete ───────────

    @Test
    @Order(3)
    void indexHtml_agentFieldIsAutocompleteInput() {
        assertTrue(indexHtmlContent.contains("field-agent-input"),
                "index.html must contain 'field-agent-input' text input (SPEC §2.3 searchable dropdown)");
    }

    // ── A15-d: edit agent modal exists in index.html ─────────────────────────

    @Test
    @Order(4)
    void indexHtml_containsEditAgentModal() {
        assertTrue(indexHtmlContent.contains("modal-edit-agent"),
                "index.html must contain 'modal-edit-agent' (edit agent modal, SPEC §2.2)");
    }
}
