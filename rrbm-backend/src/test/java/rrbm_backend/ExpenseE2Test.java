package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E2 integration tests — backdating window enforcement, paymentMethod /
 * categoryId validation on POST /api/expenses, and GET /api/expense-categories.
 *
 * Flyway runs V49–V51 before any test fires; Hibernate validate confirms every
 * new field in Expense.java maps to its column.
 *
 * Lifecycle:
 *   @BeforeAll  — create a test user; generate JWT
 *   @AfterAll   — delete any expenses created by successful POSTs; delete user
 *
 * @TestInstance(PER_CLASS) is required so @BeforeAll/@AfterAll can be
 * non-static and access @Autowired fields.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseE2Test {

    @Autowired private MockMvc                       mockMvc;
    @Autowired private UserRepository                userRepository;
    @Autowired private ExpenseRepository             expenseRepository;
    @Autowired private ExpenseCategoryRepository     categoryRepository;
    @Autowired private JwtUtil                       jwtUtil;

    private final ObjectMapper   mapper             = new ObjectMapper();
    private       String         jwt;
    private       User           testUser;
    private final List<Long>     createdExpenseIds  = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());
        testUser = new User();
        testUser.setEmail("e2-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("E2 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);
    }

    @AfterAll
    void tearDownAll() {
        createdExpenseIds.forEach(id -> expenseRepository.deleteById(id));
        if (testUser != null) userRepository.delete(testUser);
    }

    /** Returns a real primary-category id seeded by V49 (FACILITY). */
    private Long facilityId() {
        return categoryRepository.findByCode("FACILITY")
                .map(ExpenseCategory::getId)
                .orElseThrow(() -> new IllegalStateException("FACILITY category not seeded — check V49"));
    }

    // ── E2-a: Backdate within window (exactly N days ago) → 200 OK ─────────

    /**
     * A date exactly N days in the past sits at the boundary of the window
     * and must be accepted (the check is strictly "before N days ago").
     * N is seeded as 7 by V51.
     */
    @Test
    void backdate_withinWindow_returns200() throws Exception {
        LocalDate date  = LocalDate.now().minusDays(7);
        Long      catId = facilityId();

        String body = """
                {
                  "date": "%s",
                  "paymentMethod": "CASH",
                  "items": [{ "itemDescription": "E2 within-window test", "amount": 100.00, "categoryId": %d }]
                }
                """.formatted(date, catId);

        String response = mockMvc.perform(post("/api/expenses")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = mapper.readValue(response, Map.class);
        Object id = resp.get("id");
        if (id instanceof Number n) createdExpenseIds.add(n.longValue());
    }

    // ── E2-b: Backdate beyond window (N+1 days ago) → 400 "Backdating" ────

    /**
     * One day past the window must be rejected with HTTP 400.
     * The error message must contain the word "Backdating".
     */
    @Test
    void backdate_beyondWindow_returns400() throws Exception {
        LocalDate date  = LocalDate.now().minusDays(8); // 7-day window → 8 days ago is out
        Long      catId = facilityId();

        String body = """
                {
                  "date": "%s",
                  "paymentMethod": "CASH",
                  "items": [{ "itemDescription": "E2 beyond-window test", "amount": 100.00, "categoryId": %d }]
                }
                """.formatted(date, catId);

        mockMvc.perform(post("/api/expenses")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Backdating")));
    }

    // ── E2-c: Missing categoryId on one item → 400 ─────────────────────────

    /**
     * An item that omits categoryId entirely must be rejected — the field is
     * required for all new entries regardless of which category is chosen.
     */
    @Test
    void missingCategoryId_returns400() throws Exception {
        String body = """
                {
                  "date": "%s",
                  "paymentMethod": "CASH",
                  "items": [{ "itemDescription": "No category item", "amount": 50.00 }]
                }
                """.formatted(LocalDate.now());

        mockMvc.perform(post("/api/expenses")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── E2-d: GET /api/expense-categories → 8 primaries; OPERATIONS has 7 subs

    /**
     * The categories endpoint must return all 8 V49 primary categories, and
     * the OPERATIONS primary must expose exactly 7 sub-categories (per §1.2, V64).
     * No auth header is needed — this is reference data.
     */
    @Test
    @SuppressWarnings("unchecked")
    void getExpenseCategories_8PrimariesAndOperationsHas7Subs() throws Exception {
        String response = mockMvc.perform(get("/api/expense-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaries").isArray())
                .andExpect(jsonPath("$.primaries.length()").value(8))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object>       resp      = mapper.readValue(response, Map.class);
        List<Map<String, Object>> primaries = (List<Map<String, Object>>) resp.get("primaries");

        Map<String, Object> ops = primaries.stream()
                .filter(p -> "OPERATIONS".equals(p.get("code")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("OPERATIONS primary not found in response"));

        List<?> subs = (List<?>) ops.get("subcategories");
        assertEquals(7, subs.size(),
                "OPERATIONS must expose exactly 7 subcategories; found " + subs.size());
    }
}
