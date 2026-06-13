package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S10 — Notification Emails: low-stock recipient list management.
 *
 * Routes under /api/settings/notification-emails:
 *   GET    /                — list all; requires SUPER_ADMIN or ADMINISTRATOR
 *   POST   /                — add email; requires SUPER_ADMIN or ADMINISTRATOR
 *   DELETE /{id}            — remove email; requires SUPER_ADMIN or ADMINISTRATOR
 *
 * Role gate: ACCOUNTING / STAFF → 403. No token → 401 (Spring Security filter).
 * Max 10 emails enforced; duplicate email → 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class NotificationEmailIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private NotificationEmailRepository notifEmailRepo;
    @Autowired private UserRepository userRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final ObjectMapper mapper = new ObjectMapper();

    // ADMINISTRATOR role gets through the isAdminOrSuper() gate
    private User adminUser;
    private String adminJwt;

    // ACCOUNTING role is blocked by the role gate (→ 403)
    private User accountingUser;
    private String accountingJwt;

    // Tracks the ID of the email row added in t01 (used in t04 and t07)
    private Long addedEmailId;
    // Duplicate-test email address; added by t01 and verified for duplicate in t03
    private String testEmail;

    @BeforeAll
    void seed() {
        testEmail = "s10-notify-" + RUN + "@test.rrbm.internal";

        adminUser = ITSupport.seedUser(userRepository, "ADMINISTRATOR",
                "s10-admin-" + RUN + "@test.rrbm.internal", "S10 Admin", "S10-adm-pass-" + RUN, null);
        adminJwt = ITSupport.jwtFor(jwtUtil, adminUser);

        accountingUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s10-acct-" + RUN + "@test.rrbm.internal", "S10 Accounting", "S10-acct-pass-" + RUN, null);
        accountingJwt = ITSupport.jwtFor(jwtUtil, accountingUser);
    }

    @AfterAll
    void clean() {
        // Remove any test notification emails still in DB
        notifEmailRepo.findAll().stream()
                .filter(ne -> ne.getEmail().endsWith("@test.rrbm.internal"))
                .forEach(notifEmailRepo::delete);

        // Clean activity log entries written by test users today
        List.of(adminUser, accountingUser).forEach(u ->
                activityLogRepository
                        .findByUserIdAndReportDateOrderByCreatedAtDesc(u.getId(), LocalDate.now())
                        .forEach(activityLogRepository::delete)
        );

        userRepository.delete(adminUser);
        userRepository.delete(accountingUser);
    }

    // ===== t01: POST — add valid email → 200, row persisted, activity_log written =====

    @Test
    void t01_addNotificationEmail_validEmail_returns200AndPersistsRow() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("email", testEmail);

        MvcResult result = mockMvc
                .perform(post("/api/settings/notification-emails")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = mapper.readTree(result.getResponse().getContentAsString());
        addedEmailId = response.get("id").asLong();

        // Verify row persisted in DB
        assertTrue(notifEmailRepo.existsByEmail(testEmail), "Email row should exist in DB");

        // Verify activity log entry written for today
        var logs = activityLogRepository.findByUserIdAndReportDateOrderByCreatedAtDesc(
                adminUser.getId(), LocalDate.now());
        assertTrue(logs.stream().anyMatch(l -> "ADD_NOTIFICATION_EMAIL".equals(l.getAction())),
                "ADD_NOTIFICATION_EMAIL activity log entry should be written");
    }

    // ===== t02: POST — invalid email (no @) → 400, no row written =====

    @Test
    void t02_addNotificationEmail_invalidEmail_returns400() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("email", "not-an-email");

        mockMvc.perform(post("/api/settings/notification-emails")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());

        assertFalse(notifEmailRepo.existsByEmail("not-an-email"),
                "Invalid email must not be persisted");
    }

    // ===== t03: POST — duplicate email → 400, no second row written =====

    @Test
    void t03_addNotificationEmail_duplicateEmail_returns400() throws Exception {
        // testEmail was already added in t01
        long countBefore = notifEmailRepo.count();

        ObjectNode payload = mapper.createObjectNode();
        payload.put("email", testEmail);

        mockMvc.perform(post("/api/settings/notification-emails")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());

        assertEquals(countBefore, notifEmailRepo.count(), "Row count must not increase on duplicate");
    }

    // ===== t04: GET — ADMINISTRATOR role → 200, list contains added email =====

    @Test
    void t04_listNotificationEmails_adminRole_returns200WithList() throws Exception {
        MvcResult result = mockMvc
                .perform(get("/api/settings/notification-emails")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains(testEmail),
                "Response list should contain the email added in t01");
    }

    // ===== t05: GET — ACCOUNTING role (non-admin) → 403 =====

    @Test
    void t05_listNotificationEmails_accountingRole_returns403() throws Exception {
        mockMvc.perform(get("/api/settings/notification-emails")
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isForbidden());
    }

    // ===== t06: GET — no token → 401 =====

    @Test
    void t06_listNotificationEmails_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/settings/notification-emails"))
                .andExpect(status().isUnauthorized());
    }

    // ===== t07: DELETE — valid id → 200, row removed, activity_log written =====

    @Test
    void t07_deleteNotificationEmail_validId_returns200AndRemovesRow() throws Exception {
        assertNotNull(addedEmailId, "addedEmailId must have been set by t01");

        mockMvc.perform(delete("/api/settings/notification-emails/" + addedEmailId)
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk());

        // Verify row removed from DB
        assertFalse(notifEmailRepo.existsByEmail(testEmail),
                "Email row should be removed after DELETE");

        // Verify activity log entry written
        var logs = activityLogRepository.findByUserIdAndReportDateOrderByCreatedAtDesc(
                adminUser.getId(), LocalDate.now());
        assertTrue(logs.stream().anyMatch(l -> "REMOVE_NOTIFICATION_EMAIL".equals(l.getAction())),
                "REMOVE_NOTIFICATION_EMAIL activity log entry should be written");
    }

    // ===== t08: DELETE — non-existent id → 404 =====

    @Test
    void t08_deleteNotificationEmail_nonExistentId_returns404() throws Exception {
        mockMvc.perform(delete("/api/settings/notification-emails/999999")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isNotFound());
    }

    // ===== t09: DELETE — no token → 401 =====

    @Test
    void t09_deleteNotificationEmail_noToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/settings/notification-emails/1"))
                .andExpect(status().isUnauthorized());
    }
}
