package rrbm_backend;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A11 integration tests — GET /api/expenses/export (CSV / Excel / PDF).
 *
 * Tests confirm:
 *   a. No JWT → 401 (Spring Security AuthenticationEntryPoint)
 *   b. Valid JWT + format=csv → 200; Content-Type contains text/csv; body has CSV header row
 *   c. Inserted non-voided expense appears in CSV export for its date
 *   d. includeVoided=false excludes voided; includeVoided=true includes voided
 *
 * Lifecycle:
 *   @BeforeAll — create test user; generate JWT
 *   @AfterAll  — delete expenses and user created by these tests
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpenseA11Test {

    @Autowired private MockMvc                   mockMvc;
    @Autowired private UserRepository            userRepository;
    @Autowired private ExpenseRepository         expenseRepository;
    @Autowired private ExpenseCategoryRepository categoryRepository;
    @Autowired private JwtUtil                   jwtUtil;

    private User             testUser;
    private String           jwt;
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());
        testUser = new User();
        testUser.setEmail("a11-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A11 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);
    }

    @AfterAll
    void tearDownAll() {
        createdIds.forEach(id -> expenseRepository.deleteById(id));
        if (testUser != null) userRepository.delete(testUser);
    }

    private Expense insertExpense(BigDecimal amount, boolean voided, String referenceNumber) {
        Long catId = categoryRepository.findByCode("FACILITY")
                .map(ExpenseCategory::getId)
                .orElseThrow(() -> new IllegalStateException("FACILITY not seeded — check V49"));

        Expense e = new Expense();
        e.setDate(LocalDate.now());
        e.setAdminId(testUser.getId());
        e.setAdminName(testUser.getFullName());
        e.setPaymentMethod("CASH");
        e.setReferenceNumber(referenceNumber);
        e.setStatus(voided ? "VOIDED" : "COMPLETED");
        e.setVoided(voided);
        if (voided) {
            e.setVoidedAt(OffsetDateTime.now());
            e.setVoidedBy(testUser.getId());
            e.setVoidReason("A11 test void");
        }

        ExpenseItem item = new ExpenseItem();
        item.setItemDescription("A11 test item");
        item.setAmount(amount);
        item.setCategoryId(catId);
        item.setExpense(e);
        e.getItems().add(item);
        e.recalculateTotal();

        Expense saved = expenseRepository.save(e);
        createdIds.add(saved.getId());
        return saved;
    }

    // ── A11-a: No JWT → 401 ─────────────────────────────────────────────────

    /**
     * GET /api/expenses/export without an Authorization header must return 401.
     * Spring Security's AuthenticationEntryPoint intercepts before parameter validation.
     */
    @Test
    void export_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/expenses/export"))
                .andExpect(status().isUnauthorized());
    }

    // ── A11-b: Valid JWT + format=csv → 200 with CSV header row ─────────────

    /**
     * With a valid JWT and format=csv the endpoint returns 200 with Content-Type
     * containing "text/csv" and a body that starts with the CSV header row.
     */
    @Test
    void export_csv_returns200WithCsvHeader() throws Exception {
        String today = LocalDate.now().toString();

        MvcResult result = mockMvc.perform(
                        get("/api/expenses/export?start=" + today + "&end=" + today + "&format=csv")
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();

        String contentType = result.getResponse().getContentType();
        assertTrue(contentType != null && contentType.contains("text/csv"),
                "Expected Content-Type to contain text/csv but got: " + contentType);

        String body = result.getResponse().getContentAsString();
        assertFalse(body.isEmpty(), "Response body must not be empty");
        assertTrue(body.contains("id,date,paymentMethod"),
                "CSV header row must be present in the response body");
    }

    // ── A11-c: Inserted expense appears in CSV ───────────────────────────────

    /**
     * After inserting a non-voided expense for today, a CSV export for today
     * must contain that expense's referenceNumber in the body.
     */
    @Test
    void export_csv_containsInsertedExpense() throws Exception {
        String refNum = "REF-A11-C-" + System.currentTimeMillis();
        insertExpense(new BigDecimal("75.50"), false, refNum);

        String today = LocalDate.now().toString();
        String body = mockMvc.perform(
                        get("/api/expenses/export?start=" + today + "&end=" + today + "&format=csv")
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(refNum),
                "CSV export should contain the inserted expense's referenceNumber: " + refNum);
    }

    // ── A11-d: includeVoided filter ──────────────────────────────────────────

    /**
     * includeVoided=false (default) must exclude voided expenses from the CSV.
     * includeVoided=true must include voided expenses in the CSV.
     * The voided expense's ID is used as the discriminator.
     */
    @Test
    void export_voidedFilter_respectsIncludeVoided() throws Exception {
        String refNum = "REF-A11-D-" + System.currentTimeMillis();
        Expense voided = insertExpense(new BigDecimal("200.00"), true, refNum);
        String today = LocalDate.now().toString();

        // includeVoided=false → voided expense NOT in response
        String bodyExcluded = mockMvc.perform(
                        get("/api/expenses/export?start=" + today + "&end=" + today
                                + "&format=csv&includeVoided=false")
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(bodyExcluded.contains(refNum),
                "Voided expense referenceNumber should NOT appear when includeVoided=false");

        // includeVoided=true → voided expense IS in response
        String bodyIncluded = mockMvc.perform(
                        get("/api/expenses/export?start=" + today + "&end=" + today
                                + "&format=csv&includeVoided=true")
                                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(bodyIncluded.contains(refNum),
                "Voided expense referenceNumber SHOULD appear when includeVoided=true");
    }
}
