package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session 1 / item 1.1 regression — authorization gate on user create & update.
 *
 * Approach A: account create/edit is limited to SUPER_ADMIN + ADMINISTRATOR, and an
 * ADMINISTRATOR may neither mint a SUPER_ADMIN nor edit an existing SUPER_ADMIN.
 *
 *   a. STANDARD_USER → POST /api/users (role=SUPER_ADMIN)        → 403
 *   b. STANDARD_USER → PUT  /api/users/{id} (password reset)     → 403
 *   c. ADMINISTRATOR → POST /api/users (role=SUPER_ADMIN)        → 403 (no escalation)
 *   d. ADMINISTRATOR → PUT  /api/users/{superAdminId}            → 403 (can't touch a super admin)
 *   e. ADMINISTRATOR → POST /api/users (role=STAFF)              → 201 (workflow preserved)
 *   f. SUPER_ADMIN   → POST /api/users (role=SUPER_ADMIN)        → 201 (allowed)
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserCreateUpdateGateTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;

    private static final String PW_HASH =
            "$2a$10$placeholderHashForTestOnly000000000000000000000000000";

    private final List<Long> createdIds = new ArrayList<>();
    private String suffix;
    private String jwtStandard;
    private String jwtAdministrator;
    private String jwtSuper;
    private Long   standardUserId;
    private Long   targetSuperId;

    @BeforeAll
    void setUp() {
        suffix = String.valueOf(System.currentTimeMillis());
        standardUserId   = newUser("STANDARD_USER", "standard");
        jwtStandard      = jwtUtil.generateToken(userRepository.findById(standardUserId).orElseThrow());
        jwtAdministrator = tokenFor("ADMINISTRATOR", "administrator");
        jwtSuper         = tokenFor("SUPER_ADMIN",   "super");
        targetSuperId    = newUser("SUPER_ADMIN", "targetsuper");
    }

    @AfterAll
    void tearDown() {
        for (Long id : createdIds) {
            try { userRepository.deleteById(id); } catch (Exception ignored) {}
        }
    }

    private Long newUser(String role, String tag) {
        User u = new User();
        u.setEmail(tag + "-" + suffix + "@test.rrbm.internal");
        u.setPasswordHash(PW_HASH);
        u.setFullName("Gate Test " + tag);
        u.setRole(role);
        u.setStatus("ACTIVE");
        u = userRepository.save(u);
        createdIds.add(u.getId());
        return u.getId();
    }

    private String tokenFor(String role, String tag) {
        return jwtUtil.generateToken(userRepository.findById(newUser(role, tag)).orElseThrow());
    }

    /** Minimal valid create body with a unique email/username for the given role. */
    private String createBody(String role, String tag) {
        String email = tag + "-" + suffix + "@test.rrbm.internal";
        return "{"
             + "\"fullName\":\"Created " + tag + "\","
             + "\"email\":\"" + email + "\","
             + "\"password\":\"secret123\","
             + "\"role\":\"" + role + "\"}";
    }

    // ── a: STANDARD_USER cannot create a SUPER_ADMIN ─────────────────────────
    @Test @Order(1)
    void standardUser_createSuperAdmin_forbidden() throws Exception {
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + jwtStandard)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("SUPER_ADMIN", "evil-create")))
                .andExpect(status().isForbidden());
    }

    // ── b: STANDARD_USER cannot reset another user's password via PUT ────────
    @Test @Order(2)
    void standardUser_updateUser_forbidden() throws Exception {
        mockMvc.perform(put("/api/users/" + targetSuperId)
                        .header("Authorization", "Bearer " + jwtStandard)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"hijacked123\"}"))
                .andExpect(status().isForbidden());
    }

    // ── c: ADMINISTRATOR cannot mint a SUPER_ADMIN (no escalation) ───────────
    @Test @Order(3)
    void administrator_createSuperAdmin_forbidden() throws Exception {
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + jwtAdministrator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("SUPER_ADMIN", "admin-escalate")))
                .andExpect(status().isForbidden());
    }

    // ── d: ADMINISTRATOR cannot edit an existing SUPER_ADMIN ─────────────────
    @Test @Order(4)
    void administrator_editSuperAdmin_forbidden() throws Exception {
        mockMvc.perform(put("/api/users/" + targetSuperId)
                        .header("Authorization", "Bearer " + jwtAdministrator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"hijacked123\"}"))
                .andExpect(status().isForbidden());
    }

    // ── e: ADMINISTRATOR may still create a normal STAFF account ─────────────
    @Test @Order(5)
    void administrator_createStaff_allowed() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + jwtAdministrator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("STAFF", "admin-staff")))
                .andExpect(status().isCreated())
                .andReturn();
        trackCreated(res);
    }

    // ── f: SUPER_ADMIN may create a SUPER_ADMIN ──────────────────────────────
    @Test @Order(6)
    void superAdmin_createSuperAdmin_allowed() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + jwtSuper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("SUPER_ADMIN", "super-create")))
                .andExpect(status().isCreated())
                .andReturn();
        trackCreated(res);
    }

    // ── g (M-2.1): STANDARD_USER cannot enumerate the full staff list ────────
    @Test @Order(7)
    void standardUser_listUsers_forbidden() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + jwtStandard))
                .andExpect(status().isForbidden());
    }

    // ── h (M-2.1): a manager may list users ──────────────────────────────────
    @Test @Order(8)
    void administrator_listUsers_allowed() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + jwtAdministrator))
                .andExpect(status().isOk());
    }

    // ── i (M-2.1): a user may read their OWN record ──────────────────────────
    @Test @Order(9)
    void standardUser_getOwnRecord_allowed() throws Exception {
        mockMvc.perform(get("/api/users/" + standardUserId)
                        .header("Authorization", "Bearer " + jwtStandard))
                .andExpect(status().isOk());
    }

    // ── j (M-2.1): a user may NOT read someone else's record ─────────────────
    @Test @Order(10)
    void standardUser_getOtherRecord_forbidden() throws Exception {
        mockMvc.perform(get("/api/users/" + targetSuperId)
                        .header("Authorization", "Bearer " + jwtStandard))
                .andExpect(status().isForbidden());
    }

    /** Record the id of a successfully-created user so @AfterAll can clean it up. */
    private void trackCreated(MvcResult res) throws Exception {
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        if (body.hasNonNull("id")) createdIds.add(body.get("id").asLong());
    }
}
