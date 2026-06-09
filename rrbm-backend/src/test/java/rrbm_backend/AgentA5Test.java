package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A5 integration tests — cash-flow widget endpoint.
 *
 * Setup (BeforeAll):
 *   - test user + JWT
 *   - test agent (needed as FK for commission entries)
 *   - 2028-01 seed data: SALE tx, RETURN tx, expense, RELEASED period + entry + BONUS adjustment
 *   - 2028-03 seed data: RELEASED/OPEN/CLOSED periods each with an entry
 *
 * Tests:
 *   a. No JWT → 401
 *   b. Valid JWT → 200; all required keys present
 *   c. 2028-01 arithmetic: net = revenue − expenses − commissions
 *   d. 2028-03 status filter: only RELEASED period contributes to commissions
 *
 * AfterAll: delete seed data in FK order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA5Test {

    @Autowired private MockMvc                         mockMvc;
    @Autowired private UserRepository                  userRepository;
    @Autowired private AgentRepository                 agentRepository;
    @Autowired private TransactionRepository           transactionRepository;
    @Autowired private ExpenseRepository               expenseRepository;
    @Autowired private CommissionPeriodRepository      periodRepository;
    @Autowired private CommissionEntryRepository       entryRepository;
    @Autowired private CommissionAdjustmentRepository  adjustmentRepository;
    @Autowired private JwtUtil                         jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();

    private User   testUser;
    private String jwt;
    private Agent  testAgent;

    // 2028-01 seed IDs (for test c)
    private Long          saleTxId;
    private Long          returnTxId;
    private Long          expenseId;
    private Long          periodC1Id;
    private Long          entryC1Id;
    private Long          adjC1Id;

    // 2028-03 seed IDs (for test d)
    private Long          periodD1Id;   // RELEASED
    private Long          entryD1Id;
    private Long          periodD2Id;   // OPEN
    private Long          entryD2Id;
    private Long          periodD3Id;   // CLOSED
    private Long          entryD3Id;

    private static final BigDecimal SALE_AMOUNT   = new BigDecimal("1000.00");
    private static final BigDecimal RETURN_AMOUNT = new BigDecimal("-200.00");
    private static final BigDecimal EXPENSE_AMT   = new BigDecimal("300.00");
    private static final BigDecimal ENTRY_OP_C1   = new BigDecimal("50.00");
    private static final BigDecimal BONUS_AMT     = new BigDecimal("10.00");
    // Expected: revenue=800, expenses=300, commissions=60, net=440
    private static final BigDecimal EXP_REVENUE   = new BigDecimal("800.00");
    private static final BigDecimal EXP_EXPENSES  = new BigDecimal("300.00");
    private static final BigDecimal EXP_COMMISSIONS = new BigDecimal("60.00");
    private static final BigDecimal EXP_NET        = new BigDecimal("440.00");

    // 2028-03: only RELEASED period (D1) contributes 100.00
    private static final BigDecimal ENTRY_OP_D1  = new BigDecimal("100.00");
    private static final BigDecimal ENTRY_OP_D2  = new BigDecimal("200.00");
    private static final BigDecimal ENTRY_OP_D3  = new BigDecimal("150.00");

    @BeforeAll
    void setUpAll() {
        String sfx = String.valueOf(System.currentTimeMillis());

        // Test user
        testUser = new User();
        testUser.setEmail("a5-test-" + sfx + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A5 Test Admin");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        // Test agent (FK for commission entries)
        testAgent = new Agent();
        testAgent.setAgentCode("AGENT-A5-" + sfx.substring(sfx.length() - 4));
        testAgent.setFullName("A5 Test Agent " + sfx);
        testAgent.setContactNumber("09170000005");
        testAgent.setTerritory("Test Territory A5");
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);

        // ── 2028-01 seed (test c) ──────────────────────────────────────────

        Transaction saleTx = new Transaction();
        saleTx.setTransactionCode("A5-SALE-" + sfx);
        saleTx.setTransactionType("SALE");
        saleTx.setAmount(SALE_AMOUNT);
        saleTx.setEffectiveDate(LocalDate.of(2028, 1, 15));
        saleTx = transactionRepository.save(saleTx);
        saleTxId = saleTx.getId();

        Transaction returnTx = new Transaction();
        returnTx.setTransactionCode("A5-RETURN-" + sfx);
        returnTx.setTransactionType("RETURN");
        returnTx.setAmount(RETURN_AMOUNT);
        returnTx.setEffectiveDate(LocalDate.of(2028, 1, 20));
        returnTx = transactionRepository.save(returnTx);
        returnTxId = returnTx.getId();

        Expense expense = new Expense();
        expense.setDate(LocalDate.of(2028, 1, 10));
        expense.setAdminName("A5 Test");
        expense.setTotalAmount(EXPENSE_AMT);
        expense = expenseRepository.save(expense);
        expenseId = expense.getId();

        CommissionPeriod periodC1 = new CommissionPeriod();
        periodC1.setPeriodCode("A5-2028-01-R-" + sfx.substring(sfx.length() - 6));
        periodC1.setStartDate(LocalDate.of(2028, 1, 1));
        periodC1.setEndDate(LocalDate.of(2028, 1, 31));
        periodC1.setStatus("RELEASED");
        periodC1.setCreatedBy(testUser.getId());
        periodC1 = periodRepository.save(periodC1);
        periodC1Id = periodC1.getId();

        CommissionEntry entryC1 = new CommissionEntry();
        entryC1.setPeriodId(periodC1Id);
        entryC1.setAgentId(testAgent.getId());
        entryC1.setOrderDate(LocalDate.of(2028, 1, 15));
        entryC1.setOpAmount(ENTRY_OP_C1);
        entryC1.setStatus("RELEASED");
        entryC1 = entryRepository.save(entryC1);
        entryC1Id = entryC1.getId();

        CommissionAdjustment adjC1 = new CommissionAdjustment();
        adjC1.setPeriodId(periodC1Id);
        adjC1.setAgentId(testAgent.getId());
        adjC1.setAdjustmentType("BONUS");
        adjC1.setAmount(BONUS_AMT);
        adjC1.setReason("A5 test bonus");
        adjC1.setCreatedBy(testUser.getId());
        adjC1 = adjustmentRepository.save(adjC1);
        adjC1Id = adjC1.getId();

        // ── 2028-03 seed (test d): RELEASED / OPEN / CLOSED periods ──────────

        CommissionPeriod periodD1 = new CommissionPeriod();
        periodD1.setPeriodCode("A5-2028-03-R-" + sfx.substring(sfx.length() - 6));
        periodD1.setStartDate(LocalDate.of(2028, 3, 1));
        periodD1.setEndDate(LocalDate.of(2028, 3, 31));
        periodD1.setStatus("RELEASED");
        periodD1.setCreatedBy(testUser.getId());
        periodD1 = periodRepository.save(periodD1);
        periodD1Id = periodD1.getId();

        CommissionEntry entryD1 = new CommissionEntry();
        entryD1.setPeriodId(periodD1Id);
        entryD1.setAgentId(testAgent.getId());
        entryD1.setOrderDate(LocalDate.of(2028, 3, 10));
        entryD1.setOpAmount(ENTRY_OP_D1);
        entryD1.setStatus("RELEASED");
        entryD1 = entryRepository.save(entryD1);
        entryD1Id = entryD1.getId();

        CommissionPeriod periodD2 = new CommissionPeriod();
        periodD2.setPeriodCode("A5-2028-03-O-" + sfx.substring(sfx.length() - 6));
        periodD2.setStartDate(LocalDate.of(2028, 3, 1));
        periodD2.setEndDate(LocalDate.of(2028, 3, 31));
        periodD2.setStatus("OPEN");
        periodD2.setCreatedBy(testUser.getId());
        periodD2 = periodRepository.save(periodD2);
        periodD2Id = periodD2.getId();

        CommissionEntry entryD2 = new CommissionEntry();
        entryD2.setPeriodId(periodD2Id);
        entryD2.setAgentId(testAgent.getId());
        entryD2.setOrderDate(LocalDate.of(2028, 3, 10));
        entryD2.setOpAmount(ENTRY_OP_D2);
        entryD2.setStatus("PENDING");
        entryD2 = entryRepository.save(entryD2);
        entryD2Id = entryD2.getId();

        CommissionPeriod periodD3 = new CommissionPeriod();
        periodD3.setPeriodCode("A5-2028-03-C-" + sfx.substring(sfx.length() - 6));
        periodD3.setStartDate(LocalDate.of(2028, 3, 1));
        periodD3.setEndDate(LocalDate.of(2028, 3, 31));
        periodD3.setStatus("CLOSED");
        periodD3.setCreatedBy(testUser.getId());
        periodD3 = periodRepository.save(periodD3);
        periodD3Id = periodD3.getId();

        CommissionEntry entryD3 = new CommissionEntry();
        entryD3.setPeriodId(periodD3Id);
        entryD3.setAgentId(testAgent.getId());
        entryD3.setOrderDate(LocalDate.of(2028, 3, 10));
        entryD3.setOpAmount(ENTRY_OP_D3);
        entryD3.setStatus("PENDING");
        entryD3 = entryRepository.save(entryD3);
        entryD3Id = entryD3.getId();
    }

    @AfterAll
    void tearDownAll() {
        // FK order: adjustments → entries → periods → expense → transactions → agent → user
        if (adjC1Id != null) adjustmentRepository.deleteById(adjC1Id);

        List<Long> entryIds = new ArrayList<>();
        if (entryC1Id != null) entryIds.add(entryC1Id);
        if (entryD1Id != null) entryIds.add(entryD1Id);
        if (entryD2Id != null) entryIds.add(entryD2Id);
        if (entryD3Id != null) entryIds.add(entryD3Id);
        entryIds.forEach(id -> entryRepository.deleteById(id));

        List<Long> periodIds = new ArrayList<>();
        if (periodC1Id != null) periodIds.add(periodC1Id);
        if (periodD1Id != null) periodIds.add(periodD1Id);
        if (periodD2Id != null) periodIds.add(periodD2Id);
        if (periodD3Id != null) periodIds.add(periodD3Id);
        periodIds.forEach(id -> periodRepository.deleteById(id));

        if (expenseId  != null) expenseRepository.deleteById(expenseId);
        if (saleTxId   != null) transactionRepository.deleteById(saleTxId);
        if (returnTxId != null) transactionRepository.deleteById(returnTxId);
        agentRepository.delete(testAgent);
        userRepository.delete(testUser);
    }

    // ── A5-a: no JWT → 401 ────────────────────────────────────────────────────

    @Test
    @Order(1)
    void cashFlow_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/dashboard/cashflow"))
                .andExpect(status().isUnauthorized());
    }

    // ── A5-b: valid JWT → 200; all required keys present ─────────────────────

    @Test
    @Order(2)
    void cashFlow_validJwt_returns200WithAllKeys() throws Exception {
        String resp = mockMvc.perform(get("/api/dashboard/cashflow")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        assertTrue(data.containsKey("year"),        "Response must contain 'year'");
        assertTrue(data.containsKey("month"),       "Response must contain 'month'");
        assertTrue(data.containsKey("revenue"),     "Response must contain 'revenue'");
        assertTrue(data.containsKey("expenses"),    "Response must contain 'expenses'");
        assertTrue(data.containsKey("commissions"), "Response must contain 'commissions'");
        assertTrue(data.containsKey("net"),         "Response must contain 'net'");
    }

    // ── A5-c: 2028-01 arithmetic — net = revenue − expenses − commissions ────
    //   SALE=1000, RETURN=−200 → revenue=800
    //   expense=300 → expenses=300
    //   RELEASED entry op=50 + BONUS adj=10 → commissions=60
    //   net = 800 − 300 − 60 = 440

    @Test
    @Order(3)
    void cashFlow_2028Jan_arithmeticIsCorrect() throws Exception {
        String resp = mockMvc.perform(get("/api/dashboard/cashflow")
                        .header("Authorization", "Bearer " + jwt)
                        .param("year", "2028")
                        .param("month", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);

        double revenue     = ((Number) data.get("revenue")).doubleValue();
        double expenses    = ((Number) data.get("expenses")).doubleValue();
        double commissions = ((Number) data.get("commissions")).doubleValue();
        double net         = ((Number) data.get("net")).doubleValue();

        assertEquals(EXP_REVENUE.doubleValue(),      revenue,     0.001, "revenue must be 800.00");
        assertEquals(EXP_EXPENSES.doubleValue(),     expenses,    0.001, "expenses must be 300.00");
        assertEquals(EXP_COMMISSIONS.doubleValue(),  commissions, 0.001, "commissions must be 60.00");
        assertEquals(EXP_NET.doubleValue(),          net,         0.001, "net must be 440.00");
        assertEquals(revenue - expenses - commissions, net, 0.001, "net must equal revenue − expenses − commissions");
    }

    // ── A5-d: only RELEASED period contributes; OPEN and CLOSED are excluded ──
    //   2028-03: RELEASED period → op=100 included
    //            OPEN period    → op=200 NOT included
    //            CLOSED period  → op=150 NOT included
    //   Expected commissions = 100.00

    @Test
    @Order(4)
    void cashFlow_2028Mar_onlyReleasedPeriodIncluded() throws Exception {
        String resp = mockMvc.perform(get("/api/dashboard/cashflow")
                        .header("Authorization", "Bearer " + jwt)
                        .param("year", "2028")
                        .param("month", "3"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        double commissions = ((Number) data.get("commissions")).doubleValue();

        assertEquals(ENTRY_OP_D1.doubleValue(), commissions, 0.001,
                "commissions must equal only the RELEASED period's opAmount (100.00); " +
                "OPEN and CLOSED periods must be excluded");
    }
}
