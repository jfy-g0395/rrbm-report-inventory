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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A8 integration tests — GET /api/transactions/ledger and
 * GET /api/transactions/ledger/report, plus app.js surface-area grep.
 *
 * Setup (@BeforeAll): test user + one SALE + one ADJUSTMENT seeded on
 * a far-future date (2029-07-15) to avoid cross-test pollution.
 *
 * Tests (ordered):
 *   a. GET /api/transactions/ledger without JWT → 401
 *   b. GET /api/transactions/ledger with JWT → 200; array with required keys
 *   c. GET /api/transactions/ledger/report with JWT → 200; all 9 keys present;
 *      arithmetic check for grossSales, adjustmentsTotal, netSales
 *   d. app.js contains the string "loadTransactions" (grep verify)
 *
 * Teardown: delete seeded transactions → user
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LedgerA8Test {

    @Autowired private MockMvc               mockMvc;
    @Autowired private UserRepository        userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtUtil               jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();

    private User   testUser;
    private String jwt;
    private Long   txnSaleId;
    private Long   txnAdjId;

    private static final LocalDate TEST_DATE = LocalDate.of(2029, 7, 15);

    @BeforeAll
    void setUpAll() {
        String sfx = String.valueOf(System.currentTimeMillis());

        testUser = new User();
        testUser.setEmail("a8-test-" + sfx + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A8 Test User");
        testUser.setRole("ADMIN");
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        // Seed a SALE on the far-future test date
        Transaction sale = new Transaction();
        sale.setTransactionCode("SALE-A8-" + sfx);
        sale.setTransactionType("SALE");
        sale.setAmount(new BigDecimal("1500.00"));
        sale.setReferenceType("ORDER");
        sale.setReferenceId("A8-ORDER");
        sale.setNotes("A8 test sale");
        sale.setCreatedBy(testUser.getId());
        sale.setEffectiveDate(TEST_DATE);
        txnSaleId = transactionRepository.save(sale).getId();

        // Seed a negative ADJUSTMENT on the same date
        Transaction adj = new Transaction();
        adj.setTransactionCode("ADJ-A8-" + sfx);
        adj.setTransactionType("ADJUSTMENT");
        adj.setAmount(new BigDecimal("-200.00"));
        adj.setReferenceType("MANUAL");
        adj.setNotes("A8 test adjustment");
        adj.setCreatedBy(testUser.getId());
        adj.setEffectiveDate(TEST_DATE);
        txnAdjId = transactionRepository.save(adj).getId();
    }

    @AfterAll
    void tearDownAll() {
        if (txnSaleId != null) transactionRepository.deleteById(txnSaleId);
        if (txnAdjId  != null) transactionRepository.deleteById(txnAdjId);
        userRepository.delete(testUser);
    }

    // ── A8-a: no JWT → 401 ───────────────────────────────────────────────────

    @Test
    @Order(1)
    void getLedger_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/transactions/ledger")
                        .param("start", TEST_DATE.toString())
                        .param("end",   TEST_DATE.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ── A8-b: valid JWT → 200 array with required DTO keys ──────────────────

    @Test
    @Order(2)
    void getLedger_validJwt_returns200ArrayWithRequiredKeys() throws Exception {
        String resp = mockMvc.perform(get("/api/transactions/ledger")
                        .param("start", TEST_DATE.toString())
                        .param("end",   TEST_DATE.toString())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> list = mapper.readValue(resp, List.class);
        // At least the two seeded transactions must appear
        assertTrue(list.size() >= 2, "At least 2 seeded transactions must be returned");

        String[] required = { "id", "transactionCode", "transactionType", "amount", "effectiveDate" };
        for (Object item : list) {
            Map<?, ?> row = (Map<?, ?>) item;
            for (String key : required) {
                assertTrue(row.containsKey(key), "Each ledger row must contain '" + key + "'");
            }
        }
    }

    // ── A8-c: ledger report → 200 with all keys and correct arithmetic ──────

    @Test
    @Order(3)
    void getLedgerReport_validJwt_returns200WithAllKeysAndCorrectArithmetic() throws Exception {
        String resp = mockMvc.perform(get("/api/transactions/ledger/report")
                        .param("start", TEST_DATE.toString())
                        .param("end",   TEST_DATE.toString())
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> report = mapper.readValue(resp, Map.class);
        String[] required = { "startDate", "endDate", "grossSales", "voidTotal",
                               "returnTotal", "adjustmentsTotal", "netSales", "totalCount", "breakdown" };
        for (String key : required) {
            assertTrue(report.containsKey(key), "Report must contain key '" + key + "'");
        }

        // grossSales ≥ 1500 (seeded SALE)
        double gs = ((Number) report.get("grossSales")).doubleValue();
        assertTrue(gs >= 1500.0 - 0.01, "grossSales must include the seeded 1500 SALE");

        // adjustmentsTotal ≤ -200 (seeded -200 ADJUSTMENT)
        double adjTotal = ((Number) report.get("adjustmentsTotal")).doubleValue();
        assertTrue(adjTotal <= -200.0 + 0.01, "adjustmentsTotal must include the seeded -200 ADJUSTMENT");

        // netSales = grossSales + voidTotal + returnTotal + adjustmentsTotal
        double vt  = ((Number) report.get("voidTotal")).doubleValue();
        double rt  = ((Number) report.get("returnTotal")).doubleValue();
        double net = ((Number) report.get("netSales")).doubleValue();
        assertEquals(gs + vt + rt + adjTotal, net, 0.02, "netSales must equal the arithmetic sum");

        // totalCount ≥ 2 (the two seeded transactions)
        long tc = ((Number) report.get("totalCount")).longValue();
        assertTrue(tc >= 2, "totalCount must be >= 2");
    }

    // ── A8-d: app.js contains "loadTransactions" ────────────────────────────

    @Test
    @Order(4)
    void appJs_containsLoadTransactionsString() throws Exception {
        java.io.File appJs = new java.io.File("../rrbm_frontend/rrbm-frontend/js/app.js");
        assertTrue(appJs.exists(), "app.js must exist at expected relative path");
        String content = new String(java.nio.file.Files.readAllBytes(appJs.toPath()));
        assertTrue(content.contains("loadTransactions"),
                "app.js must contain 'loadTransactions'");
    }
}
