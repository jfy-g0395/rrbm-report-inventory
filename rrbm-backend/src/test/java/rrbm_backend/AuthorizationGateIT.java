package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S12 — Authorization gate verification (§4 risk #6)
 *
 * <p>Two concerns tested here:
 *
 * <ol>
 *   <li><b>Role-based gates (server-side, enforced):</b> manager-only routes (UserController)
 *       return 403 for non-manager callers; Super-Admin-only routes (role/status/permissions/
 *       security-key changes) return 403 for ADMINISTRATOR and ACCOUNTING callers.
 *   <li><b>allowedPages (GAP S12-01 — fixed):</b> {@link PageAccessInterceptor} maps each
 *       {@code /api/**} route to a page key and returns 403 when the calling user's
 *       {@code allowedPages} JSON array does not include that key. Super Admin bypasses the
 *       check entirely; a {@code null} allowedPages value is treated as unrestricted (legacy).
 *       Tests t12 and t13 assert the 403 behaviour for a user with {@code allowedPages = "[]"}.
 * </ol>
 *
 * <p>Run command (DB up + migrated):
 * <pre>mvn test -Dtest=AuthorizationGateIT</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class AuthorizationGateIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private User superAdmin;
    private User adminUser;
    private User accountingUser;
    private User restrictedUser;     // allowedPages = "[]" — no page access in the UI model

    private String superAdminJwt;
    private String adminJwt;
    private String accountingJwt;
    private String restrictedJwt;

    @BeforeAll
    void seed() {
        superAdmin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "s12sa-" + RUN + "@test.rrbm.internal", "S12 Super Admin " + RUN, "S12SA-Secret!", "S12SA-key");
        superAdminJwt = ITSupport.jwtFor(jwtUtil, superAdmin);

        adminUser = ITSupport.seedUser(userRepository, "ADMINISTRATOR",
                "s12ad-" + RUN + "@test.rrbm.internal", "S12 Administrator " + RUN, "S12AD-Secret!", "S12AD-key");
        adminJwt = ITSupport.jwtFor(jwtUtil, adminUser);

        accountingUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s12ac-" + RUN + "@test.rrbm.internal", "S12 Accounting " + RUN, "S12AC-Secret!", null);
        accountingJwt = ITSupport.jwtFor(jwtUtil, accountingUser);

        // Restricted user: valid JWT, ACCOUNTING role, but allowedPages explicitly empty.
        // The backend stores this value but never checks it — see GAP S12-01 below.
        restrictedUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s12rx-" + RUN + "@test.rrbm.internal", "S12 Restricted " + RUN, "S12RX-Secret!", null);
        restrictedUser.setAllowedPages("[]");
        userRepository.save(restrictedUser);
        restrictedJwt = ITSupport.jwtFor(jwtUtil, restrictedUser);
    }

    @AfterAll
    void clean() {
        activityLogRepository.deleteAll();
        if (restrictedUser  != null) userRepository.deleteById(restrictedUser.getId());
        if (accountingUser  != null) userRepository.deleteById(accountingUser.getId());
        if (adminUser       != null) userRepository.deleteById(adminUser.getId());
        if (superAdmin      != null) userRepository.deleteById(superAdmin.getId());
    }

    // ── User list — manager (SUPER_ADMIN or ADMINISTRATOR) only ─────────────

    @Test
    void t01_listUsers_accountingRole_returns403() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void t02_listUsers_administratorRole_returns200() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk());
    }

    @Test
    void t03_listUsers_superAdminRole_returns200() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + superAdminJwt))
                .andExpect(status().isOk());
    }

    // ── Role change — SUPER_ADMIN only ───────────────────────────────────────

    @Test
    void t04_updateRole_accountingRole_returns403() throws Exception {
        mockMvc.perform(patch("/api/users/" + accountingUser.getId() + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accountingJwt)
                        .content(MAPPER.writeValueAsString(Map.of("role", "STAFF"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void t05_updateRole_administratorRole_returns403() throws Exception {
        // ADMINISTRATOR is a manager but not a SUPER_ADMIN; role changes are Super-Admin-only
        mockMvc.perform(patch("/api/users/" + accountingUser.getId() + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content(MAPPER.writeValueAsString(Map.of("role", "STAFF"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void t06_updateRole_superAdminRole_returns200() throws Exception {
        // SUPER_ADMIN can change roles; use accountingUser as the target (not restrictedUser —
        // updateRole resets allowedPages to the role default, which would break t12/t13).
        mockMvc.perform(patch("/api/users/" + accountingUser.getId() + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + superAdminJwt)
                        .content(MAPPER.writeValueAsString(Map.of("role", "STANDARD_USER"))))
                .andExpect(status().isOk());

        // Restore to ACCOUNTING
        mockMvc.perform(patch("/api/users/" + accountingUser.getId() + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + superAdminJwt)
                        .content(MAPPER.writeValueAsString(Map.of("role", "ACCOUNTING"))))
                .andExpect(status().isOk());
    }

    // ── Permissions change — SUPER_ADMIN only ────────────────────────────────

    @Test
    void t07_updatePermissions_accountingRole_returns403() throws Exception {
        mockMvc.perform(patch("/api/users/" + accountingUser.getId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accountingJwt)
                        .content(MAPPER.writeValueAsString(Map.of("allowedPages", "[\"orders\"]"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void t08_updatePermissions_administratorRole_returns403() throws Exception {
        mockMvc.perform(patch("/api/users/" + accountingUser.getId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminJwt)
                        .content(MAPPER.writeValueAsString(Map.of("allowedPages", "[\"orders\"]"))))
                .andExpect(status().isForbidden());
    }

    // ── Security-key assignment — SUPER_ADMIN only ───────────────────────────

    @Test
    void t09_setSecurityKey_accountingRole_returns403() throws Exception {
        mockMvc.perform(patch("/api/users/" + accountingUser.getId() + "/security-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + accountingJwt)
                        .content(MAPPER.writeValueAsString(Map.of("securityKey", "newkey-123"))))
                .andExpect(status().isForbidden());
    }

    // ── Notification-email list — ADMINISTRATOR / SUPER_ADMIN only ───────────

    @Test
    void t10_getNotificationEmails_accountingRole_returns403() throws Exception {
        mockMvc.perform(get("/api/settings/notification-emails")
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void t11_getNotificationEmails_administratorRole_returns200() throws Exception {
        mockMvc.perform(get("/api/settings/notification-emails")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk());
    }

    // ── GAP S12-01 — allowedPages enforced by PageAccessInterceptor ──────────

    /**
     * GAP S12-01 fixed: {@link PageAccessInterceptor} now enforces {@code allowedPages}
     * server-side. A user with {@code allowedPages = "[]"} receives 403 on any
     * page-mapped endpoint. Super Admin bypasses the check; {@code null} allowedPages
     * is treated as unrestricted.
     *
     * <p><b>Note on test isolation:</b> {@code restrictedUser} must NOT be the target of
     * any {@code updateRole} call in this class — that endpoint resets {@code allowedPages}
     * to the role default, which would silently break these assertions.
     */
    @Test
    void t12_restrictedUser_emptyAllowedPages_blockedFromOrdersEndpoint() throws Exception {
        // restrictedUser has allowedPages = "[]" → interceptor blocks → 403
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + restrictedJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void t13_restrictedUser_emptyAllowedPages_blockedFromReportsEndpoint() throws Exception {
        // Same interceptor blocks /api/reports/** when allowedPages = "[]".
        mockMvc.perform(get("/api/reports/accounting-summary")
                        .header("Authorization", "Bearer " + restrictedJwt))
                .andExpect(status().isForbidden());
    }
}
