package rrbm_backend;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * U1 integration tests — import schema + permission + report filter.
 *
 *  U1-a  importFlag_persistsOnExpense
 *          is_imported + import_ref round-trip on Expense entity.
 *  U1-b  authorizeImport_nonAccountingUser_returns403
 *          A STANDARD_USER-role user is blocked from POST /api/import/authorize.
 *  U1-c  authorizeImport_wrongKey_returns403
 *          ACCOUNTING role, but wrong admin_security_key → 403.
 *  U1-d  dailyReport_importedFilter_returns200WithImportedEntries
 *          Insert one imported + one live expense, then verify that
 *          GET /api/expenses/report/daily returns a non-empty importedEntries
 *          list, and that importedOnly=true reflects the flag in the response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportU1Test {

    @Autowired private MockMvc                   mockMvc;
    @Autowired private UserRepository            userRepository;
    @Autowired private ExpenseRepository         expenseRepository;
    @Autowired private ExpenseCategoryRepository categoryRepository;
    @Autowired private JwtUtil                   jwtUtil;

    private final BCryptPasswordEncoder encoder      = new BCryptPasswordEncoder();
    private static final String         SECURITY_KEY = "U1-test-key-2026";

    private User             accountingUser;
    private User             staffUser;
    private String           accountingJwt;
    private String           staffJwt;
    private final List<Long> createdExpenseIds = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());

        accountingUser = new User();
        accountingUser.setEmail("u1-acct-" + suffix + "@test.rrbm.internal");
        accountingUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        accountingUser.setFullName("U1 Accounting User");
        accountingUser.setRole("ACCOUNTING");
        accountingUser.setAdminSecurityKey(encoder.encode(SECURITY_KEY));
        accountingUser = userRepository.save(accountingUser);
        accountingJwt = jwtUtil.generateToken(accountingUser);

        staffUser = new User();
        staffUser.setEmail("u1-staff-" + suffix + "@test.rrbm.internal");
        staffUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        staffUser.setFullName("U1 Staff User");
        staffUser.setRole("STANDARD_USER");
        staffUser.setAdminSecurityKey(encoder.encode(SECURITY_KEY));
        staffUser = userRepository.save(staffUser);
        staffJwt = jwtUtil.generateToken(staffUser);
    }

    @AfterAll
    void tearDownAll() {
        createdExpenseIds.forEach(id -> expenseRepository.deleteById(id));
        if (accountingUser != null) userRepository.delete(accountingUser);
        if (staffUser      != null) userRepository.delete(staffUser);
    }

    private Long facilityId() {
        return categoryRepository.findByCode("FACILITY")
                .map(ExpenseCategory::getId)
                .orElseThrow(() -> new IllegalStateException("FACILITY category not seeded by V49"));
    }

    private Expense buildExpense(boolean isImported, String importRef) {
        Expense e = new Expense();
        e.setDate(LocalDate.now());
        e.setAdminId(accountingUser.getId());
        e.setAdminName(accountingUser.getFullName());
        e.setPaymentMethod("CASH");
        e.setStatus("COMPLETED");
        e.setImported(isImported);
        e.setImportRef(importRef);

        ExpenseItem item = new ExpenseItem();
        item.setItemDescription("U1 test item");
        item.setAmount(new BigDecimal("100.00"));
        item.setCategoryId(facilityId());
        item.setExpense(e);
        e.getItems().add(item);
        e.recalculateTotal();

        Expense saved = expenseRepository.save(e);
        createdExpenseIds.add(saved.getId());
        return saved;
    }

    // ── U1-a: import flag + import_ref round-trip ────────────────────────────

    /**
     * is_imported and import_ref must survive a save → reload cycle.
     */
    @Test
    void importFlag_persistsOnExpense() {
        String ref = "TEMP-060626-0001";
        Expense saved = buildExpense(true, ref);

        Expense reloaded = expenseRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isImported()).isTrue();
        assertThat(reloaded.getImportRef()).isEqualTo(ref);
    }

    // ── U1-b: non-ACCOUNTING role → 403 ─────────────────────────────────────

    /**
     * A STANDARD_USER-role user must be blocked from the import-authorize endpoint.
     * Error message must mention "ACCOUNTING".
     */
    @Test
    void authorizeImport_nonAccountingUser_returns403() throws Exception {
        String body = """
                { "adminSecurityKey": "%s" }
                """.formatted(SECURITY_KEY);

        mockMvc.perform(post("/api/import/authorize")
                        .header("Authorization", "Bearer " + staffJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString("ACCOUNTING")));
    }

    // ── U1-c: correct role, wrong key → 403 ─────────────────────────────────

    /**
     * An ACCOUNTING user who supplies the wrong admin_security_key must be rejected.
     */
    @Test
    void authorizeImport_wrongKey_returns403() throws Exception {
        String body = """
                { "adminSecurityKey": "wrong-key-99999" }
                """;

        mockMvc.perform(post("/api/import/authorize")
                        .header("Authorization", "Bearer " + accountingJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Invalid security key"));
    }

    // ── U1-d: daily report importedEntries + importedOnly filter ─────────────

    /**
     * After inserting one imported and one live expense for today:
     *  - GET /api/expenses/report/daily → importedEntries array has ≥ 1 entry.
     *  - GET /api/expenses/report/daily?importedOnly=true → importedOnly=true in response body.
     */
    @Test
    void dailyReport_importedFilter_returns200WithImportedEntries() throws Exception {
        buildExpense(true,  "TEMP-060626-0099");  // imported
        buildExpense(false, null);                  // live-entered

        String today = LocalDate.now().toString();

        // Default (importedOnly=false) → importedEntries list is non-empty
        mockMvc.perform(get("/api/expenses/report/daily")
                        .header("Authorization", "Bearer " + accountingJwt)
                        .param("date", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedEntries").isArray())
                .andExpect(jsonPath("$.importedEntries.length()").value(greaterThanOrEqualTo(1)));

        // importedOnly=true → flag reflected in response body
        mockMvc.perform(get("/api/expenses/report/daily")
                        .header("Authorization", "Bearer " + accountingJwt)
                        .param("date", today)
                        .param("importedOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedOnly").value(true))
                .andExpect(jsonPath("$.importedEntries").isArray())
                .andExpect(jsonPath("$.importedEntries.length()").value(greaterThanOrEqualTo(1)));
    }
}
