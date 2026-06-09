package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E5 integration tests — daily and monthly expense report endpoints.
 *
 * All tests run against the live PostgreSQL database (Flyway-migrated).
 * Uses >= comparisons where the shared DB may already contain data for the
 * current day/month.
 *
 * Lifecycle:
 *   @BeforeAll — create test user; generate JWT
 *   @AfterAll  — delete expenses and user created by these tests
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseE5Test {

    @Autowired private MockMvc                   mockMvc;
    @Autowired private UserRepository            userRepository;
    @Autowired private ExpenseRepository         expenseRepository;
    @Autowired private ExpenseCategoryRepository categoryRepository;
    @Autowired private JwtUtil                   jwtUtil;

    private final ObjectMapper  mapper            = new ObjectMapper();
    private User                testUser;
    private String              jwt;
    private final List<Long>    createdExpenseIds = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());
        testUser = new User();
        testUser.setEmail("e5-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("E5 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);
    }

    @AfterAll
    void tearDownAll() {
        createdExpenseIds.forEach(id -> expenseRepository.deleteById(id));
        if (testUser != null) userRepository.delete(testUser);
    }

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
        item.setItemDescription("E5 test item");
        item.setAmount(amount);
        item.setCategoryId(catId);
        item.setExpense(e);
        e.getItems().add(item);
        e.recalculateTotal();

        Expense saved = expenseRepository.save(e);
        createdExpenseIds.add(saved.getId());
        return saved;
    }

    // ── E5-a: Daily report without JWT → 401 ───────────────────────────────

    @Test
    void dailyReport_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/expenses/report/daily"))
                .andExpect(status().isUnauthorized());
    }

    // ── E5-b: Daily report with valid JWT → 200; all required keys present ─

    @Test
    void dailyReport_validJwt_returns200WithAllKeys() throws Exception {
        String body = mockMvc.perform(get("/api/expenses/report/daily")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);

        for (String key : List.of("date", "total", "count", "avgPerEntry",
                "byCategory", "byPaymentMethod", "voidedEntries")) {
            assertTrue(resp.containsKey(key),
                    "Missing required key in daily report response: " + key);
        }
        assertTrue(resp.get("byCategory") instanceof List<?>,
                "byCategory must be a JSON array");
        assertTrue(resp.get("byPaymentMethod") instanceof List<?>,
                "byPaymentMethod must be a JSON array");
        assertTrue(resp.get("voidedEntries") instanceof List<?>,
                "voidedEntries must be a JSON array");
    }

    // ── E5-c: Non-voided expense appears in daily totals ───────────────────

    @Test
    void dailyReport_nonVoidedExpense_appearsInTotals() throws Exception {
        BigDecimal knownAmount = new BigDecimal("77.50");
        insertNonVoidedExpense(knownAmount);

        String body = mockMvc.perform(get("/api/expenses/report/daily")
                        .header("Authorization", "Bearer " + jwt)
                        .param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);
        BigDecimal total = new BigDecimal(resp.get("total").toString());
        int count = ((Number) resp.get("count")).intValue();

        assertTrue(total.compareTo(knownAmount) >= 0,
                "daily total " + total + " should be >= inserted amount " + knownAmount);
        assertTrue(count >= 1,
                "daily count should be >= 1 after inserting a non-voided expense");
    }

    // ── E5-d: Monthly report without JWT → 401 ─────────────────────────────

    @Test
    void monthlyReport_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/expenses/report/monthly"))
                .andExpect(status().isUnauthorized());
    }

    // ── E5-e: Monthly report with valid JWT → 200; all keys + correct dailyBreakdown size

    @Test
    void monthlyReport_validJwt_returns200WithAllKeysAndCorrectBreakdownSize() throws Exception {
        LocalDate today = LocalDate.now();
        String body = mockMvc.perform(get("/api/expenses/report/monthly")
                        .header("Authorization", "Bearer " + jwt)
                        .param("year",  String.valueOf(today.getYear()))
                        .param("month", String.valueOf(today.getMonthValue())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);

        for (String key : List.of("year", "month", "grandTotal", "dailyAvg",
                "highestDay", "lowestDay", "byCategory", "dailyBreakdown", "voidedEntries")) {
            assertTrue(resp.containsKey(key),
                    "Missing required key in monthly report response: " + key);
        }

        List<?> breakdown = (List<?>) resp.get("dailyBreakdown");
        int expectedDays = YearMonth.of(today.getYear(), today.getMonthValue()).lengthOfMonth();
        assertEquals(expectedDays, breakdown.size(),
                "dailyBreakdown must have exactly " + expectedDays
                        + " entries (one per calendar day in the month)");
    }
}
