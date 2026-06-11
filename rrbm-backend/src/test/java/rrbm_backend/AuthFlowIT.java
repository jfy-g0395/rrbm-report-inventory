package rrbm_backend;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S1 / W-0 — Auth & JWT baseline (login → token → protected use; verify + logout side effects).
 *
 * <p>Drives the real {@code /api/auth/*} HTTP endpoints with MockMvc and asserts through the
 * repositories. Seeds production-shape users (bcrypt password hash + hashed admin security key).
 * Login lockout is exercised over the live endpoint, complementing the unit-level
 * {@link LoginAttemptServiceTest}.
 *
 * <p><b>Shared-state caveat:</b> {@link LoginAttemptService} is a process-wide singleton bean.
 * Tests use per-run identifiers and reset their counters in {@code @AfterAll} so they neither
 * collide with each other nor leave the wrong user locked for the rest of the suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class AuthFlowIT {

    @Autowired private MockMvc               mockMvc;
    @Autowired private UserRepository        userRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil               jwtUtil;
    @Autowired private LoginAttemptService   loginAttemptService;

    private static final long   RUN      = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S1-Sup3rSecret!";
    private static final String SEC_KEY  = "S1-admin-sec-key";

    private User   superAdmin;
    private User   acctUser;
    private String acctJwt;

    /** identifiers whose lockout counters must be cleared in teardown */
    private final String lockoutEmail = "s1-lockout-" + RUN + "@test.rrbm.internal";

    @BeforeAll
    void seed() {
        superAdmin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "s1-super-" + RUN + "@test.rrbm.internal", "S1 Super Admin", PASSWORD, SEC_KEY);
        acctUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s1-acct-" + RUN + "@test.rrbm.internal", "S1 Accounting", PASSWORD, SEC_KEY);
        acctJwt = ITSupport.jwtFor(jwtUtil, acctUser);
    }

    @AfterAll
    void clean() {
        // Clear any lockout state we created so later tests in the suite aren't blocked.
        loginAttemptService.recordSuccess(lockoutEmail);
        if (superAdmin != null) loginAttemptService.recordSuccess(superAdmin.getEmail());
        if (acctUser != null)   loginAttemptService.recordSuccess(acctUser.getEmail());

        // Remove the LOGIN / LOGOUT activity_log rows our tests generated, then the users.
        deleteAuthLogsFor(superAdmin);
        deleteAuthLogsFor(acctUser);
        if (acctUser != null)   userRepository.delete(acctUser);
        if (superAdmin != null) userRepository.delete(superAdmin);
    }

    private void deleteAuthLogsFor(User u) {
        if (u == null) return;
        List<ActivityLog> today = activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now());
        today.stream()
                .filter(a -> u.getId().equals(a.getUserId()))
                .filter(a -> "LOGIN".equals(a.getAction()) || "LOGOUT".equals(a.getAction()))
                .forEach(a -> activityLogRepository.deleteById(a.getId()));
    }

    private String body(String json) { return json; }

    // ════════════════════════════════════════════════════════════════════════
    //  Login
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t01_login_ok_returnsTokenMatchingUser() throws Exception {
        String resp = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"email\":\"" + acctUser.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.id").value(acctUser.getId()))
                .andExpect(jsonPath("$.user.email").value(acctUser.getEmail()))
                .andExpect(jsonPath("$.user.role").value("ACCOUNTING"))
                // never echo the hash
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // The returned token must decode back to this exact user.
        String token = extract(resp, "token");
        assertThat(jwtUtil.extractUserId(token)).isEqualTo(acctUser.getId());
        assertThat(jwtUtil.extractEmail(token)).isEqualTo(acctUser.getEmail());

        // A successful login writes a LOGIN activity_log row for the user.
        long logins = activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now()).stream()
                .filter(a -> acctUser.getId().equals(a.getUserId()))
                .filter(a -> "LOGIN".equals(a.getAction()))
                .count();
        assertThat(logins).isGreaterThanOrEqualTo(1);

        // keep the lockout counter clean for this identifier
        loginAttemptService.recordSuccess(acctUser.getEmail());
    }

    @Test
    void t02_login_wrongPassword_unauthorizedNoToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"email\":\"" + acctUser.getEmail() + "\",\"password\":\"not-the-password\"}")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.token").doesNotExist());

        // reset so the single failure above doesn't accumulate toward lockout
        loginAttemptService.recordSuccess(acctUser.getEmail());
    }

    @Test
    void t03_login_unknownIdentifier_unauthorized() throws Exception {
        String unknown = "s1-nobody-" + RUN + "@test.rrbm.internal";
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"email\":\"" + unknown + "\",\"password\":\"" + PASSWORD + "\"}")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.token").doesNotExist());
        loginAttemptService.recordSuccess(unknown);
    }

    @Test
    void t04_login_lockout_after5Failures() throws Exception {
        // A throwaway identifier so the lockout doesn't affect the seeded users.
        String payload = "{\"email\":\"" + lockoutEmail + "\",\"password\":\"wrong\"}";

        // MAX_ATTEMPTS (5) failures → each 401, the account is not yet locked.
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(payload)))
                    .andExpect(status().isUnauthorized());
        }

        // The 6th attempt is rejected by the throttle before any credential check → 429.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payload)))
                .andExpect(status().isTooManyRequests());

        assertThat(loginAttemptService.isBlocked(lockoutEmail)).isTrue();
        // teardown clears this identifier's counter
    }

    // ════════════════════════════════════════════════════════════════════════
    //  verify-password (JWT-gated)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t05_verifyPassword_match_ok() throws Exception {
        mockMvc.perform(post("/api/auth/verify-password")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"email\":\"" + acctUser.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void t06_verifyPassword_mismatch_unauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/verify-password")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"email\":\"" + acctUser.getEmail() + "\",\"password\":\"wrong\"}")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.valid").doesNotExist());
    }

    @Test
    void t07_verifyPassword_blank_badRequest() throws Exception {
        mockMvc.perform(post("/api/auth/verify-password")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"email\":\"\",\"password\":\"\"}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t08_verifyPassword_noToken_unauthorized() throws Exception {
        // The route lives under /api/** → Spring Security rejects it with 401 before the handler.
        mockMvc.perform(post("/api/auth/verify-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"email\":\"" + acctUser.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}")))
                .andExpect(status().isUnauthorized());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  verify-security-key (personal admin key)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t09_verifySecurityKey_match_ok() throws Exception {
        mockMvc.perform(post("/api/auth/verify-security-key")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"securityKey\":\"" + SEC_KEY + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void t10_verifySecurityKey_mismatch_forbidden() throws Exception {
        mockMvc.perform(post("/api/auth/verify-security-key")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"securityKey\":\"wrong-key\"}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void t11_verifySecurityKey_noKeyAssigned_forbidden() throws Exception {
        // A real user with no admin security key set → 403 (distinct "no key" branch).
        User noKey = ITSupport.seedUser(userRepository, "STAFF",
                "s1-nokey-" + RUN + "@test.rrbm.internal", "S1 NoKey User", PASSWORD, null);
        try {
            String jwt = ITSupport.jwtFor(jwtUtil, noKey);
            mockMvc.perform(post("/api/auth/verify-security-key")
                            .header("Authorization", "Bearer " + jwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("{\"securityKey\":\"anything\"}")))
                    .andExpect(status().isForbidden());
        } finally {
            userRepository.delete(noKey);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  verify-superadmin-key (matches ANY super-admin/administrator key)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t12_verifySuperAdminKey_match_ok() throws Exception {
        // SEC_KEY is the seeded SUPER_ADMIN's personal key → must match.
        mockMvc.perform(post("/api/auth/verify-superadmin-key")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"securityKey\":\"" + SEC_KEY + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void t13_verifySuperAdminKey_mismatch_forbidden() throws Exception {
        mockMvc.perform(post("/api/auth/verify-superadmin-key")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"securityKey\":\"definitely-not-a-real-superadmin-key-" + RUN + "\"}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void t14_verifySuperAdminKey_empty_badRequest() throws Exception {
        mockMvc.perform(post("/api/auth/verify-superadmin-key")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"securityKey\":\"\"}")))
                .andExpect(status().isBadRequest());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  logout
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t15_logout_ok_writesActivityLog() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + acctJwt))
                .andExpect(status().isOk());

        long logouts = activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now()).stream()
                .filter(a -> acctUser.getId().equals(a.getUserId()))
                .filter(a -> "LOGOUT".equals(a.getAction()))
                .count();
        assertThat(logouts).isGreaterThanOrEqualTo(1);
    }

    // ── tiny JSON value extractor (mirrors ImportU6Test's approach) ──────────
    private String extract(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }
}
