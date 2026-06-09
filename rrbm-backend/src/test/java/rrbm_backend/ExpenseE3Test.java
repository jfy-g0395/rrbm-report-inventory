package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E3 integration tests — expense void workflow.
 *
 * Flyway runs V49–V51 before any test; Hibernate validate confirms the full
 * schema matches the entity mappings.
 *
 * Lifecycle:
 *   @BeforeAll — create a test user with a known BCrypt security key; generate JWT
 *   @AfterAll  — delete expenses created by tests; delete user
 *
 * @TestInstance(PER_CLASS) required so @BeforeAll/@AfterAll can be non-static
 * and access @Autowired fields.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseE3Test {

    @Autowired private MockMvc                   mockMvc;
    @Autowired private UserRepository            userRepository;
    @Autowired private ExpenseRepository         expenseRepository;
    @Autowired private ExpenseCategoryRepository categoryRepository;
    @Autowired private JwtUtil                   jwtUtil;

    private final ObjectMapper          mapper       = new ObjectMapper();
    private final BCryptPasswordEncoder encoder      = new BCryptPasswordEncoder();
    private static final String         SECURITY_KEY = "E3-test-key-2026";

    private User           testUser;
    private String         jwt;
    private final List<Long> createdExpenseIds = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());
        testUser = new User();
        testUser.setEmail("e3-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("E3 Test Admin");
        testUser.setRole("ADMIN");
        testUser.setAdminSecurityKey(encoder.encode(SECURITY_KEY));
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);
    }

    @AfterAll
    void tearDownAll() {
        createdExpenseIds.forEach(id -> expenseRepository.deleteById(id));
        if (testUser != null) userRepository.delete(testUser);
    }

    /** Creates a fresh COMPLETED expense directly in the DB for use in a single test. */
    private Expense createFreshExpense() {
        Long catId = categoryRepository.findByCode("FACILITY")
                .map(ExpenseCategory::getId)
                .orElseThrow(() -> new IllegalStateException("FACILITY not seeded — check V49"));

        Expense e = new Expense();
        e.setDate(LocalDate.now());
        e.setAdminId(testUser.getId());
        e.setAdminName(testUser.getFullName());
        e.setPaymentMethod("CASH");
        e.setStatus("COMPLETED");

        ExpenseItem item = new ExpenseItem();
        item.setItemDescription("E3 test item");
        item.setAmount(new BigDecimal("50.00"));
        item.setCategoryId(catId);
        item.setExpense(e);
        e.getItems().add(item);
        e.recalculateTotal();

        Expense saved = expenseRepository.save(e);
        createdExpenseIds.add(saved.getId());
        return saved;
    }

    // ── E3-a: Void successfully → 200; voided=true, status="VOIDED" ─────────

    /**
     * Happy-path: valid JWT, correct security key, non-voided expense.
     * Response must include voided=true and status="VOIDED".
     */
    @Test
    void voidExpense_success_returns200() throws Exception {
        Expense expense = createFreshExpense();
        String body = """
                { "voidReason": "Duplicate Entry", "adminSecurityKey": "%s" }
                """.formatted(SECURITY_KEY);

        mockMvc.perform(post("/api/expenses/" + expense.getId() + "/void")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voided").value(true))
                .andExpect(jsonPath("$.status").value("VOIDED"));
    }

    // ── E3-b: Void already-voided expense → 400 "already voided" ────────────

    /**
     * Voiding an expense that is already voided must be rejected with HTTP 400.
     * The error message must contain "already voided".
     */
    @Test
    void voidExpense_alreadyVoided_returns400() throws Exception {
        Expense expense = createFreshExpense();
        String body = """
                { "voidReason": "First void", "adminSecurityKey": "%s" }
                """.formatted(SECURITY_KEY);

        // First void — must succeed
        mockMvc.perform(post("/api/expenses/" + expense.getId() + "/void")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Second void — must be rejected
        mockMvc.perform(post("/api/expenses/" + expense.getId() + "/void")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("already voided")));
    }

    // ── E3-c: Wrong security key → 403 "Invalid security key" ───────────────

    /**
     * A key that does not match the caller's BCrypt hash must be rejected with 403.
     */
    @Test
    void voidExpense_wrongSecurityKey_returns403() throws Exception {
        Expense expense = createFreshExpense();
        String body = """
                { "voidReason": "Test", "adminSecurityKey": "wrong-key-12345" }
                """;

        mockMvc.perform(post("/api/expenses/" + expense.getId() + "/void")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Invalid security key"));
    }

    // ── E3-d: Non-existent expense id → 404 ─────────────────────────────────

    /**
     * Attempting to void an id that does not exist in the DB must return 404.
     */
    @Test
    void voidExpense_notFound_returns404() throws Exception {
        String body = """
                { "voidReason": "Test", "adminSecurityKey": "%s" }
                """.formatted(SECURITY_KEY);

        mockMvc.perform(post("/api/expenses/999999999/void")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Expense not found"));
    }
}
