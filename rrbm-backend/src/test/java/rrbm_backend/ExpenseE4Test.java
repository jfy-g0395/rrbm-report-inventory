package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E4 integration tests — expense dashboard summary endpoint.
 *
 * All tests run against the live PostgreSQL database (Flyway-migrated).
 * Tests check >= comparisons rather than exact equality because the shared DB
 * may already contain expenses for the current day.
 *
 * Lifecycle:
 *   @BeforeAll — create test user; generate JWT
 *   @AfterAll  — delete expenses and user created by these tests
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseE4Test {

    @Autowired private MockMvc                   mockMvc;
    @Autowired private UserRepository            userRepository;
    @Autowired private ExpenseRepository         expenseRepository;
    @Autowired private ExpenseCategoryRepository categoryRepository;
    @Autowired private JwtUtil                   jwtUtil;

    private final ObjectMapper  mapper           = new ObjectMapper();
    private User                testUser;
    private String              jwt;
    private final List<Long>    createdExpenseIds = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());
        testUser = new User();
        testUser.setEmail("e4-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("E4 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);
    }

    @AfterAll
    void tearDownAll() {
        createdExpenseIds.forEach(id -> expenseRepository.deleteById(id));
        if (testUser != null) userRepository.delete(testUser);
    }

    /** Persists a COMPLETED expense for today with the given amount. */
    private Expense insertNonVoidedExpense(BigDecimal amount) {
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
        item.setItemDescription("E4 non-voided test item");
        item.setAmount(amount);
        item.setCategoryId(catId);
        item.setExpense(e);
        e.getItems().add(item);
        e.recalculateTotal();

        Expense saved = expenseRepository.save(e);
        createdExpenseIds.add(saved.getId());
        return saved;
    }

    /** Persists a VOIDED expense for today with the given amount. */
    private Expense insertVoidedExpense(BigDecimal amount) {
        Expense e = insertNonVoidedExpense(amount);
        e.setVoided(true);
        e.setStatus("VOIDED");
        e.setVoidedAt(OffsetDateTime.now());
        e.setVoidedBy(testUser.getId());
        e.setVoidReason("E4 test void");
        return expenseRepository.save(e);
    }

    // ── E4-a: No JWT → 401 ──────────────────────────────────────────────────

    /**
     * GET /api/expenses/summary without an Authorization header must return 401.
     */
    @Test
    void summary_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/expenses/summary"))
                .andExpect(status().isUnauthorized());
    }

    // ── E4-b: Valid JWT → 200; all required keys present ────────────────────

    /**
     * With a valid JWT the endpoint returns 200 and a JSON object containing
     * all seven required keys. vsYesterdayPct may legitimately be null (when
     * yesterday has no spend), so key presence is checked via Map.containsKey
     * rather than MockMvc's .exists() (which rejects null values).
     */
    @Test
    void summary_validJwt_returns200WithAllKeys() throws Exception {
        String body = mockMvc.perform(get("/api/expenses/summary")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);

        for (String key : List.of("todayTotal", "todayCount", "yesterdayTotal",
                "vsYesterdayPct", "mtdTotal", "mtdByCategory", "pendingVoidCount")) {
            assertTrue(resp.containsKey(key), "Missing required key in summary response: " + key);
        }
        assertTrue(resp.get("mtdByCategory") instanceof List<?>,
                "mtdByCategory must be a JSON array");
    }

    // ── E4-c: Non-voided expense is counted ─────────────────────────────────

    /**
     * After inserting a known non-voided expense for today, todayTotal must be
     * >= that expense's amount and todayCount must be >= 1.
     */
    @Test
    void summary_nonVoidedToday_countedInTotals() throws Exception {
        BigDecimal knownAmount = new BigDecimal("123.45");
        insertNonVoidedExpense(knownAmount);

        String body = mockMvc.perform(get("/api/expenses/summary")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);
        BigDecimal todayTotal = new BigDecimal(resp.get("todayTotal").toString());
        int todayCount = ((Number) resp.get("todayCount")).intValue();

        assertTrue(todayTotal.compareTo(knownAmount) >= 0,
                "todayTotal " + todayTotal + " should be >= inserted amount " + knownAmount);
        assertTrue(todayCount >= 1,
                "todayCount should be >= 1 after inserting a non-voided expense");
    }

    // ── E4-d: Voided expense is excluded ────────────────────────────────────

    /**
     * After capturing a baseline summary, inserting a voided expense must not
     * change todayTotal or todayCount — voided expenses are excluded from all totals.
     */
    @Test
    void summary_voidedToday_notCountedInTotals() throws Exception {
        // Capture baseline before inserting the voided expense
        String baseBody = mockMvc.perform(get("/api/expenses/summary")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> base = mapper.readValue(baseBody, Map.class);
        BigDecimal baseTotal = new BigDecimal(base.get("todayTotal").toString());
        int baseCount = ((Number) base.get("todayCount")).intValue();

        insertVoidedExpense(new BigDecimal("999.99"));

        String afterBody = mockMvc.perform(get("/api/expenses/summary")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> after = mapper.readValue(afterBody, Map.class);
        BigDecimal afterTotal = new BigDecimal(after.get("todayTotal").toString());
        int afterCount = ((Number) after.get("todayCount")).intValue();

        assertEquals(0, afterTotal.compareTo(baseTotal),
                "todayTotal must not increase after inserting a voided expense");
        assertEquals(baseCount, afterCount,
                "todayCount must not increase after inserting a voided expense");
    }
}
