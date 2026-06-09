package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A16 integration tests — weekly expense report endpoint (SPEC §1.6).
 *
 * All tests run against the live PostgreSQL database (Flyway-migrated).
 * Tests A16-c and A16-d use far-future dates (2030) to avoid pollution
 * from other sessions' data.
 *
 * Lifecycle:
 *   @BeforeAll — create test user; generate JWT
 *   @AfterAll  — delete expenses and user created by these tests
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseA16Test {

    @Autowired private MockMvc                   mockMvc;
    @Autowired private UserRepository            userRepository;
    @Autowired private ExpenseRepository         expenseRepository;
    @Autowired private ExpenseCategoryRepository categoryRepository;
    @Autowired private JwtUtil                   jwtUtil;

    private final ObjectMapper mapper            = new ObjectMapper();
    private User               testUser;
    private String             jwt;
    private final List<Long>   createdExpenseIds = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());
        testUser = new User();
        testUser.setEmail("a16-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A16 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);
    }

    @AfterAll
    void tearDownAll() {
        createdExpenseIds.forEach(id -> expenseRepository.deleteById(id));
        if (testUser != null) userRepository.delete(testUser);
    }

    private Expense insertExpenseForDate(LocalDate date, BigDecimal amount) {
        Long catId = categoryRepository.findByCode("FACILITY")
                .map(ExpenseCategory::getId)
                .orElseThrow(() -> new IllegalStateException("FACILITY not seeded — check V49"));

        Expense e = new Expense();
        e.setDate(date);
        e.setAdminId(testUser.getId());
        e.setAdminName(testUser.getFullName());
        e.setPaymentMethod("CASH");
        e.setStatus("COMPLETED");

        ExpenseItem item = new ExpenseItem();
        item.setItemDescription("A16 test item");
        item.setAmount(amount);
        item.setCategoryId(catId);
        item.setExpense(e);
        e.getItems().add(item);
        e.recalculateTotal();

        Expense saved = expenseRepository.save(e);
        createdExpenseIds.add(saved.getId());
        return saved;
    }

    // ── A16-a: Weekly report without JWT → 401 ─────────────────────────────

    @Test
    void weeklyReport_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/expenses/report/weekly"))
                .andExpect(status().isUnauthorized());
    }

    // ── A16-b: Weekly report with valid JWT → 200; all keys; dayByDay has exactly 7 entries

    @Test
    void weeklyReport_validJwt_returns200WithAllKeysAnd7Days() throws Exception {
        LocalDate today = LocalDate.now();
        String body = mockMvc.perform(get("/api/expenses/report/weekly")
                        .header("Authorization", "Bearer " + jwt)
                        .param("year", String.valueOf(today.get(IsoFields.WEEK_BASED_YEAR)))
                        .param("week", String.valueOf(today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);

        for (String key : List.of("year", "week", "weekStart", "weekEnd",
                "grandTotal", "dailyAvg", "weekOverWeek",
                "highestDay", "lowestDay", "dayByDay", "byCategory", "voidedEntries")) {
            assertTrue(resp.containsKey(key),
                    "Missing required key in weekly report response: " + key);
        }

        List<?> dayByDay = (List<?>) resp.get("dayByDay");
        assertEquals(7, dayByDay.size(),
                "dayByDay must always contain exactly 7 entries (Mon–Sun)");
    }

    // ── A16-c: Non-voided expense for current week appears in weekly totals ─

    @Test
    void weeklyReport_nonVoidedExpense_appearsInTotals() throws Exception {
        BigDecimal knownAmount = new BigDecimal("88.00");
        insertExpenseForDate(LocalDate.now(), knownAmount);

        LocalDate today = LocalDate.now();
        String body = mockMvc.perform(get("/api/expenses/report/weekly")
                        .header("Authorization", "Bearer " + jwt)
                        .param("year", String.valueOf(today.get(IsoFields.WEEK_BASED_YEAR)))
                        .param("week", String.valueOf(today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);
        BigDecimal grandTotal = new BigDecimal(resp.get("grandTotal").toString());

        assertTrue(grandTotal.compareTo(knownAmount) >= 0,
                "weekly grandTotal " + grandTotal + " should be >= inserted amount " + knownAmount);
    }

    // ── A16-d: weekOverWeek is computed correctly ───────────────────────────

    @Test
    void weeklyReport_weekOverWeek_isComputedCorrectly() throws Exception {
        // Far-future year (2030) to avoid pollution. Compute ISO week 20 of 2030 dynamically.
        LocalDate jan4_2030 = LocalDate.of(2030, 1, 4);
        LocalDate week1Mon  = jan4_2030.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate thisWeekMon = week1Mon.plusWeeks(19); // week 20 (0-indexed offset)
        LocalDate prevWeekMon = thisWeekMon.minusWeeks(1); // week 19

        // Insert ₱100 in week 19 and ₱150 in week 20
        insertExpenseForDate(prevWeekMon, new BigDecimal("100.00"));
        insertExpenseForDate(thisWeekMon, new BigDecimal("150.00"));

        // Request week 20 of 2030
        String body = mockMvc.perform(get("/api/expenses/report/weekly")
                        .header("Authorization", "Bearer " + jwt)
                        .param("year", "2030")
                        .param("week", "20"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> resp = mapper.readValue(body, Map.class);

        // weekOverWeek should be (150 - 100) / 100 * 100 = 50.0 %
        assertNotNull(resp.get("weekOverWeek"),
                "weekOverWeek must be non-null when the previous week has data");
        double wow = ((Number) resp.get("weekOverWeek")).doubleValue();
        assertTrue(wow >= 49.9 && wow <= 50.1,
                "weekOverWeek expected ~50.0% but was " + wow);
    }
}
