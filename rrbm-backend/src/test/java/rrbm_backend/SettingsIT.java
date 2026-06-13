package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S10 — Settings (part 1): system settings read + upsert.
 *
 * Workflow: any authenticated user reads settings via GET; editable keys
 * (company_name, company_address, company_contact, daily_reset_time) are updated
 * via POST. Non-editable keys (master_key_hash, thresholds) are silently skipped.
 *
 * Security gap documented: POST /api/settings has no server-side SUPER_ADMIN gate —
 * any authenticated user can update settings (see t05_disabled).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class SettingsIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private SettingsRepository settingsRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final ObjectMapper mapper = new ObjectMapper();

    private User accountingUser;
    private String accountingJwt;
    private User superAdminUser;
    private String superAdminJwt;
    private String originalCompanyAddress;

    @BeforeAll
    void seed() {
        // Save original settings value so we can restore it after the test
        originalCompanyAddress = settingsRepository.findById("company_address")
                .map(Settings::getValue).orElse(null);

        accountingUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s10-acct-" + RUN + "@test.rrbm.internal", "S10 Accounting", "S10-pass-" + RUN, null);
        accountingJwt = ITSupport.jwtFor(jwtUtil, accountingUser);

        superAdminUser = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "s10-super-" + RUN + "@test.rrbm.internal", "S10 Super Admin", "S10-super-pass-" + RUN, null);
        superAdminJwt = ITSupport.jwtFor(jwtUtil, superAdminUser);
    }

    @AfterAll
    void clean() {
        // Restore the settings value modified by t02
        if (originalCompanyAddress != null) {
            settingsRepository.findById("company_address").ifPresent(s -> {
                s.setValue(originalCompanyAddress);
                settingsRepository.save(s);
            });
        }
        // Null-out updated_by on any settings rows pointing to either test user
        // before deleting (settings.updated_by is a FK → users.id)
        settingsRepository.findAll().forEach(s -> {
            if ((accountingUser != null && accountingUser.getId().equals(s.getUpdatedBy()))
                    || (superAdminUser != null && superAdminUser.getId().equals(s.getUpdatedBy()))) {
                s.setUpdatedBy(null);
                settingsRepository.save(s);
            }
        });
        // Remove activity log entries written under the test user today
        activityLogRepository
                .findByUserIdAndReportDateOrderByCreatedAtDesc(accountingUser.getId(), LocalDate.now())
                .forEach(activityLogRepository::delete);
        userRepository.delete(accountingUser);
        if (superAdminUser != null) userRepository.delete(superAdminUser);
    }

    // ===== t01: GET /api/settings — returns map of non-sensitive keys =====

    @Test
    void t01_getSettings_noAuthRequired_returns200WithMap() throws Exception {
        MvcResult result = mockMvc
                .perform(get("/api/settings")
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Map<?, ?> responseMap = mapper.readValue(body, Map.class);

        // Company settings should be present
        assertTrue(responseMap.containsKey("company_name"), "company_name should be in response");
        // Sensitive keys must never be exposed
        assertFalse(responseMap.containsKey("master_key_hash"), "master_key_hash must not be exposed");
    }

    // ===== t02: POST /api/settings with an editable key — persists the change =====

    @Test
    void t02_postSettings_editableKey_returns200AndPersistsChange() throws Exception {
        String testAddress = "Test Address S10-" + RUN;
        ObjectNode payload = mapper.createObjectNode();
        payload.put("company_address", testAddress);

        mockMvc.perform(post("/api/settings")
                        .header("Authorization", "Bearer " + superAdminJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // Verify the DB row was updated
        String saved = settingsRepository.findById("company_address")
                .map(Settings::getValue).orElse(null);
        assertEquals(testAddress, saved, "company_address should be updated in DB");
    }

    // ===== t03: POST /api/settings with a non-editable key — silently skipped =====

    @Test
    void t03_postSettings_nonEditableKey_returns200ButValueUnchanged() throws Exception {
        String originalHash = settingsRepository.findById("master_key_hash")
                .map(Settings::getValue).orElse(null);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("master_key_hash", "HACKED_VALUE");

        mockMvc.perform(post("/api/settings")
                        .header("Authorization", "Bearer " + superAdminJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // Non-editable key must be unchanged
        String stillHash = settingsRepository.findById("master_key_hash")
                .map(Settings::getValue).orElse(null);
        assertEquals(originalHash, stillHash, "master_key_hash must NOT be modified by POST /api/settings");
    }

    // ===== t04: POST /api/settings without token — 401 =====

    @Test
    void t04_postSettings_noToken_returns401() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("company_name", "Hacked Name");

        mockMvc.perform(post("/api/settings")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    // ===== t05: ACCOUNTING role → POST /api/settings must return 403 =====
    // GAP S10-01 fixed: SettingsController now checks for SUPER_ADMIN role.

    @Test
    void t05_postSettings_nonSuperAdminRole_returns403() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("company_name", "Should Be Blocked");

        mockMvc.perform(post("/api/settings")
                        .header("Authorization", "Bearer " + accountingJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isForbidden());
    }
}
