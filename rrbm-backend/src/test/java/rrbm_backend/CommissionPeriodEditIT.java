package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Reproduces the prod scenario: an OPEN commission period (2026-06-29 — 2026-07-10) alongside a
 * RELEASED one, then PUT to extend the OPEN period's end date to 2026-07-11. Asserts the change is
 * actually persisted (the reported bug: the edit appeared to run but the end date stayed 07-10).
 *
 * <p>Run: {@code mvn test -Dtest=CommissionPeriodEditIT}
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class CommissionPeriodEditIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private CommissionPeriodRepository periodRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private User admin;
    private String jwt;
    private Long openId;
    private Long releasedId;

    @BeforeAll
    void seed() {
        admin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "cpe-" + RUN + "@test.rrbm.internal", "CPE Admin " + RUN, "CPE-Secret!", "CPE-key-" + RUN);
        jwt = ITSupport.jwtFor(jwtUtil, admin);

        CommissionPeriod released = new CommissionPeriod();
        released.setPeriodCode("CPE-REL-" + RUN);
        released.setStartDate(LocalDate.of(2026, 6, 13));
        released.setEndDate(LocalDate.of(2026, 6, 27));
        released.setStatus("RELEASED");
        released.setCreatedBy(admin.getId());
        releasedId = periodRepository.save(released).getId();

        CommissionPeriod open = new CommissionPeriod();
        open.setPeriodCode("CPE-OPEN-" + RUN);
        open.setStartDate(LocalDate.of(2026, 6, 29));
        open.setEndDate(LocalDate.of(2026, 7, 10));
        open.setStatus("OPEN");
        open.setCreatedBy(admin.getId());
        openId = periodRepository.save(open).getId();
    }

    @AfterAll
    void clean() {
        try { if (openId != null) periodRepository.deleteById(openId); } catch (Exception ignored) {}
        try { if (releasedId != null) periodRepository.deleteById(releasedId); } catch (Exception ignored) {}
        try { if (admin != null) userRepository.deleteById(admin.getId()); } catch (Exception ignored) {}
    }

    @Test
    void t01_extendEndDate_persists() throws Exception {
        String body = "{\"startDate\":\"2026-06-29\",\"endDate\":\"2026-07-11\"}";

        String resp = mockMvc.perform(put("/api/commissions/periods/" + openId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Response should report the new end date.
        JsonNode json = MAPPER.readTree(resp);
        assertThat(json.get("endDate").asText()).isEqualTo("2026-07-11");

        // And it must actually be persisted.
        CommissionPeriod reloaded = periodRepository.findById(openId).orElseThrow();
        assertThat(reloaded.getEndDate()).isEqualTo(LocalDate.of(2026, 7, 11));
        assertThat(reloaded.getStartDate()).isEqualTo(LocalDate.of(2026, 6, 29));
    }
}
