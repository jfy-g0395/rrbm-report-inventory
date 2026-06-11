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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S1 — Master-key administration (`/api/auth/master-keys`).
 *
 * <p>Asserts the role gate (SUPER_ADMIN to mutate, SUPER_ADMIN/ADMINISTRATOR to read), that keys
 * are stored hashed and never echoed raw, and that delete deactivates rather than hard-deletes.
 *
 * <p><b>Shared-DB caveat:</b> {@link MasterKeyService#addMasterKey} caps active keys at 3. Because
 * the live DB may already hold active keys, the add test temporarily deactivates pre-existing keys
 * to make room and restores them afterwards. All rows created by this class are removed in
 * {@code @AfterAll}, leaving the DB as found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class MasterKeyAdminIT {

    @Autowired private MockMvc             mockMvc;
    @Autowired private UserRepository      userRepository;
    @Autowired private MasterKeyRepository masterKeyRepository;
    @Autowired private JwtUtil             jwtUtil;

    private static final long   RUN      = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S1-Mk-Pass!";

    private User      superAdmin;
    private String    superJwt;
    private User      acctUser;     // non-super-admin (ACCOUNTING) → mutate gate should reject
    private String    acctJwt;
    private MasterKey seededA;      // two pre-seeded active keys give list/delete real material
    private MasterKey seededB;

    /** ids of master-key rows this class created (direct or via API) — deleted in teardown */
    private final List<Long> createdKeyIds = new ArrayList<>();

    @BeforeAll
    void seed() {
        superAdmin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "s1-mk-super-" + RUN + "@test.rrbm.internal", "S1 MK Super", PASSWORD, null);
        acctUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s1-mk-acct-" + RUN + "@test.rrbm.internal", "S1 MK Acct", PASSWORD, null);
        superJwt = ITSupport.jwtFor(jwtUtil, superAdmin);
        acctJwt  = ITSupport.jwtFor(jwtUtil, acctUser);

        seededA = ITSupport.seedMasterKey(masterKeyRepository, "s1-mk-raw-A-" + RUN, "S1-MK-A-" + RUN);
        seededB = ITSupport.seedMasterKey(masterKeyRepository, "s1-mk-raw-B-" + RUN, "S1-MK-B-" + RUN);
        createdKeyIds.add(seededA.getId());
        createdKeyIds.add(seededB.getId());
    }

    @AfterAll
    void clean() {
        for (Long id : createdKeyIds) {
            masterKeyRepository.findById(id).ifPresent(masterKeyRepository::delete);
        }
        if (acctUser != null)   userRepository.delete(acctUser);
        if (superAdmin != null) userRepository.delete(superAdmin);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GET /master-keys
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t01_list_asSuperAdmin_returnsKeysWithoutHash() throws Exception {
        String resp = mockMvc.perform(get("/api/auth/master-keys")
                        .header("Authorization", "Bearer " + superJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn().getResponse().getContentAsString();

        // Our seeded labels are present...
        assertThat(resp).contains(seededA.getLabel());
        // ...but the bcrypt hashes must never leave the server.
        assertThat(resp).doesNotContain(seededA.getKeyHash());
        assertThat(resp).doesNotContain(seededB.getKeyHash());
        assertThat(resp).doesNotContain("keyHash");
    }

    @Test
    void t02_list_asNonAdmin_forbidden() throws Exception {
        mockMvc.perform(get("/api/auth/master-keys")
                        .header("Authorization", "Bearer " + acctJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void t03_list_noToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/master-keys"))
                .andExpect(status().isUnauthorized());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  POST /master-keys
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t04_add_asSuperAdmin_persistsHashedKey() throws Exception {
        long before = masterKeyRepository.count();

        // Make room: the service caps ACTIVE keys at 3. Temporarily deactivate the oldest active
        // keys we don't own until there is space, then restore them after the assertion.
        List<MasterKey> tempDeactivated = new ArrayList<>();
        List<MasterKey> active = masterKeyRepository.findByIsActiveTrue();
        int idx = 0;
        while (masterKeyRepository.countByIsActiveTrue() >= 3 && idx < active.size()) {
            MasterKey k = active.get(idx++);
            k.setActive(false);
            masterKeyRepository.save(k);
            tempDeactivated.add(k);
        }

        String rawKey  = "s1-mk-added-" + RUN;
        String label   = "S1-MK-ADDED-" + RUN;
        try {
            String resp = mockMvc.perform(post("/api/auth/master-keys")
                            .header("Authorization", "Bearer " + superJwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"key\":\"" + rawKey + "\",\"label\":\"" + label + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.label").value(label))
                    .andReturn().getResponse().getContentAsString();

            // The raw key must never be echoed back.
            assertThat(resp).doesNotContain(rawKey);

            assertThat(masterKeyRepository.count()).isEqualTo(before + 1);

            // The new row stores a bcrypt hash that matches the raw key — not the raw key itself.
            MasterKey added = masterKeyRepository.findByIsActiveTrue().stream()
                    .filter(k -> label.equals(k.getLabel()))
                    .findFirst().orElseThrow();
            createdKeyIds.add(added.getId());
            assertThat(added.getKeyHash()).isNotEqualTo(rawKey);
            assertThat(ITSupport.ENCODER.matches(rawKey, added.getKeyHash())).isTrue();
            assertThat(added.isActive()).isTrue();
        } finally {
            for (MasterKey k : tempDeactivated) {
                k.setActive(true);
                masterKeyRepository.save(k);
            }
        }
    }

    @Test
    void t05_add_asNonAdmin_forbiddenNoRowWritten() throws Exception {
        long before = masterKeyRepository.count();
        mockMvc.perform(post("/api/auth/master-keys")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"s1-mk-denied-" + RUN + "\",\"label\":\"denied\"}"))
                .andExpect(status().isForbidden());
        assertThat(masterKeyRepository.count()).isEqualTo(before);
    }

    @Test
    void t06_add_shortKey_badRequestNoRowWritten() throws Exception {
        long before = masterKeyRepository.count();
        mockMvc.perform(post("/api/auth/master-keys")
                        .header("Authorization", "Bearer " + superJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"abc\",\"label\":\"short\"}"))
                .andExpect(status().isBadRequest());
        assertThat(masterKeyRepository.count()).isEqualTo(before);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DELETE /master-keys/{id}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t07_delete_asNonAdmin_forbiddenKeyStaysActive() throws Exception {
        mockMvc.perform(delete("/api/auth/master-keys/" + seededB.getId())
                        .header("Authorization", "Bearer " + acctJwt))
                .andExpect(status().isForbidden());

        MasterKey reloaded = masterKeyRepository.findById(seededB.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
    }

    @Test
    void t08_delete_asSuperAdmin_deactivates() throws Exception {
        // seededA + seededB are both active (≥2), so removing one leaves at least one active —
        // satisfying the service's "at least one must remain" guard.
        mockMvc.perform(delete("/api/auth/master-keys/" + seededA.getId())
                        .header("Authorization", "Bearer " + superJwt))
                .andExpect(status().isOk());

        MasterKey reloaded = masterKeyRepository.findById(seededA.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse(); // deactivated, not hard-deleted
    }
}
