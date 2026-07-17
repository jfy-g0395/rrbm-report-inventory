package rrbm_backend;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * U2 integration tests — CSV upload pipeline.
 *
 *  U2-a  template_noJwt_returns401
 *          GET /api/import/template without JWT → 401 (Spring Security).
 *  U2-b  template_withJwt_returns200WithCsv
 *          GET /api/import/template/sales with valid JWT → 200; Content-Type text/csv.
 *  U2-c  upload_validCsv_returnsPreviewWithValidRows
 *          POST /api/import/upload/sales with valid flat CSV → 200;
 *          preview valid array has ≥ 1 entry.
 *  U2-d  upload_malformedDate_appearsInNeedsFix
 *          CSV row with "2026-99-99" date → appears in needsFix.
 *  U2-e  commit_validSession_committedOrderHasImportedFlag
 *          POST /api/import/commit with valid session → 200; committed ≥ 1;
 *          the committed order is in the DB with is_imported=true.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportU2Test {

    @Autowired private MockMvc                      mockMvc;
    @Autowired private UserRepository               userRepository;
    @Autowired private ProductRepository            productRepository;
    @Autowired private OrderRepository              orderRepository;
    @Autowired private ExpenseRepository            expenseRepository;
    @Autowired private TransactionRepository        transactionRepository;
    @Autowired private InventoryMovementRepository  inventoryMovementRepository;
    @Autowired private JwtUtil                      jwtUtil;

    private final BCryptPasswordEncoder encoder     = new BCryptPasswordEncoder();
    private static final String         SECURITY_KEY = "U2-test-key-2026";

    private User    accountingUser;
    private String  accountingJwt;
    private Product testProduct;

    // IDs tracked for @AfterAll cleanup
    private final List<String> cleanupOrderIds   = new ArrayList<>();
    private final List<Long>   cleanupExpenseIds = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());

        accountingUser = new User();
        accountingUser.setEmail("u2-acct-" + suffix + "@test.rrbm.internal");
        accountingUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        accountingUser.setFullName("U2 Accounting User");
        accountingUser.setRole("ACCOUNTING");
        accountingUser.setAdminSecurityKey(encoder.encode(SECURITY_KEY));
        accountingUser = userRepository.save(accountingUser);
        accountingJwt  = jwtUtil.generateToken(accountingUser);

        // Product with known itemCode and enough stock for a 1-unit order
        testProduct = new Product();
        testProduct.setName("U2 Test Product " + suffix);
        testProduct.setProductCode("U2PD" + suffix.substring(suffix.length() - 2));
        testProduct.setStockWh1(100);
        testProduct.setStockWh2(0);
        testProduct.setStockWh3(0);
        testProduct.setActive(true);
        testProduct.setIsSet(false);
        testProduct.setUnitPrice(new BigDecimal("100.00"));
        testProduct.setSellingTag("SLOW");   // low-stock email threshold 1000; caught silently
        testProduct.setThresholdLow(0);
        testProduct.setThresholdCritical(0);
        testProduct = productRepository.save(testProduct);
    }

    @AfterAll
    void tearDownAll() {
        for (String orderId : cleanupOrderIds) {
            transactionRepository.deleteAll(
                    transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId));
            inventoryMovementRepository.deleteAll(
                    inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(orderId));
            orderRepository.deleteById(orderId);
        }
        cleanupExpenseIds.forEach(id -> expenseRepository.deleteById(id));
        if (testProduct   != null) productRepository.delete(testProduct);
        if (accountingUser != null) userRepository.delete(accountingUser);
    }

    // ── U2-a ─────────────────────────────────────────────────────────────────

    @Test
    void template_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/import/template/sales"))
                .andExpect(status().isUnauthorized());
    }

    // ── U2-b ─────────────────────────────────────────────────────────────────

    @Test
    void template_withJwt_returns200WithCsv() throws Exception {
        mockMvc.perform(get("/api/import/template/sales")
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/csv")));
    }

    // ── U2-c ─────────────────────────────────────────────────────────────────

    @Test
    void upload_validCsv_returnsPreviewWithValidRows() throws Exception {
        String csv = buildValidCsv("U2-PREVIEW-001", testProduct.getProductCode());

        mockMvc.perform(multipart("/api/import/upload/sales")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("adminSecurityKey", SECURITY_KEY)
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.valid").isArray())
                .andExpect(jsonPath("$.valid.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.needsFix").isArray())
                .andExpect(jsonPath("$.duplicates").isArray())
                .andExpect(jsonPath("$.summary.validCount").value(greaterThanOrEqualTo(1)));
    }

    // ── U2-d ─────────────────────────────────────────────────────────────────

    @Test
    void upload_malformedDate_appearsInNeedsFix() throws Exception {
        String csv = "Date,Receipt#,Time,Customer,Source,Agent,Payment Method,Platform,Product Code,Qty,Unit Price,OP per Unit,Payment Status\r\n" +
                     "2026-99-99,U2-BADDATE-001,10:00,Test Customer,WALK_IN,,CASH,,,,,,,\r\n";

        mockMvc.perform(multipart("/api/import/upload/sales")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("adminSecurityKey", SECURITY_KEY)
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsFix").isArray())
                .andExpect(jsonPath("$.needsFix.length()").value(greaterThanOrEqualTo(1)));
    }

    // ── U2-e ─────────────────────────────────────────────────────────────────

    @Test
    void commit_validSession_committedOrderHasImportedFlag() throws Exception {
        String receiptNum = "U2-COMMIT-001";
        String csv = buildValidCsv(receiptNum, testProduct.getProductCode());

        // Step 1: Upload → get session token
        String uploadJson = mockMvc.perform(multipart("/api/import/upload/sales")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("adminSecurityKey", SECURITY_KEY)
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String sessionToken = extractJsonString(uploadJson, "sessionToken");
        assertThat(sessionToken).isNotBlank();

        // Step 2: Commit
        String commitBody = "{" +
                "\"sessionToken\":\"" + sessionToken + "\"," +
                "\"adminSecurityKey\":\"" + SECURITY_KEY + "\"," +
                "\"conflictResolutions\":[]" +
                "}";

        mockMvc.perform(post("/api/import/commit")
                        .header("Authorization", "Bearer " + accountingJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commitBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(greaterThanOrEqualTo(1)));

        // Step 3: Verify order in DB with is_imported=true
        Optional<Order> committedOrder = orderRepository.findAll().stream()
                .filter(o -> receiptNum.equals(o.getImportRef()))
                .findFirst();
        assertThat(committedOrder).isPresent();
        assertThat(committedOrder.get().isImported()).isTrue();

        // Track for cleanup
        committedOrder.ifPresent(o -> cleanupOrderIds.add(o.getId()));
        expenseRepository.findAll().stream()
                .filter(e -> e.isImported()
                        && accountingUser.getId().equals(e.getAdminId())
                        && LocalDate.of(2026, 1, 15).equals(e.getDate()))
                .forEach(e -> cleanupExpenseIds.add(e.getId()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a flat CSV with one sales row, using the given receiptNum and itemCode.
     */
    private String buildValidCsv(String receiptNum, String itemCode) {
        return "Date,Receipt#,Time,Customer,Source,Agent,Payment Method,Platform,Product Code,Qty,Unit Price,OP per Unit,Payment Status\r\n" +
               "2026-01-15," + receiptNum + ",10:00,Test Customer,WALK_IN,,CASH,," + itemCode + ",1,100.00,,\r\n";
    }

    /** Extract a JSON string field value from a raw JSON response string. */
    private String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }
}
