package rrbm_backend;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * U6 integration tests — COD lifecycle end-to-end.
 *
 * Tests the complete COD import-to-collection pipeline:
 *   Import with COD+PAID  → ACTIVE + commission
 *   Import with COD+UNPAID → PENDING_COLLECTION + COLL-DEFER + no commission
 *   Collect single order   → DELIVERED + COLL-SALE + commission (when opAmount set)
 *   Batch collect          → multiple orders in one call
 *   Duplicate detection    → re-import blocked
 *   Mixed payment modes    → correct status per row
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ImportU6Test {

    @Autowired private MockMvc                      mockMvc;
    @Autowired private UserRepository               userRepository;
    @Autowired private ProductRepository            productRepository;
    @Autowired private OrderRepository              orderRepository;
    @Autowired private TransactionRepository        transactionRepository;
    @Autowired private InventoryMovementRepository  inventoryMovementRepository;
    @Autowired private AgentRepository              agentRepository;
    @Autowired private CommissionPeriodRepository   periodRepository;
    @Autowired private CommissionEntryRepository    entryRepository;
    @Autowired private JwtUtil                      jwtUtil;
    @Autowired private CommissionService               commissionService;

    private final BCryptPasswordEncoder encoder   = new BCryptPasswordEncoder();
    private static final String         SEC_KEY  = "U6-cod-test-key";
    private static final long           RUN_ID   = System.currentTimeMillis() % 100000;

    private User    acctUser;
    private String  acctJwt;
    private Product testProduct;
    private Agent   testAgent;
    private CommissionPeriod testPeriod;

    // Tracked for @AfterAll cleanup in FK-safe order
    private final List<String> cleanupOrderIds    = new ArrayList<>();
    private final List<Long>   cleanupEntryIds    = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());

        // ── 1. ACCOUNTING user ──────────────────────────────────────────
        acctUser = new User();
        acctUser.setEmail("u6-acct-" + (suffix.length() > 8 ? suffix.substring(0, 8) : suffix) + "@test.rrbm.internal");
        acctUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        acctUser.setFullName("U6 Accounting User");
        acctUser.setRole("ACCOUNTING");
        acctUser.setAdminSecurityKey(encoder.encode(SEC_KEY));
        acctUser.setStatus("ACTIVE");
        acctUser = userRepository.save(acctUser);
        acctJwt  = jwtUtil.generateToken(acctUser);

        // ── 2. Product ──────────────────────────────────────────────────
        testProduct = new Product();
        testProduct.setName("U6 Test Product " + suffix);
        testProduct.setProductCode("U6PD" + (RUN_ID % 100));
        testProduct.setItemCode("U6IC" + RUN_ID);
        testProduct.setStockWh1(500);
        testProduct.setStockWh2(0);
        testProduct.setStockWh3(0);
        testProduct.setActive(true);
        testProduct.setIsSet(false);
        testProduct.setUnitPrice(new BigDecimal("150.00"));
        testProduct.setSellingTag("SELLING");
        testProduct.setThresholdLow(0);
        testProduct.setThresholdCritical(0);
        testProduct = productRepository.save(testProduct);

        // ── 3. Agent ────────────────────────────────────────────────────
        testAgent = new Agent();
        testAgent.setAgentCode("U6AG" + (RUN_ID % 10000));
        testAgent.setFullName("U6 Test Agent " + RUN_ID);
        testAgent.setFullName("U6 Test Agent " + suffix);
        testAgent.setContactNumber("0917000000");
        testAgent.setTerritory("Metro Manila");
        testAgent.setStatus("ACTIVE");
        testAgent.setCreatedBy(acctUser.getId());
        testAgent = agentRepository.save(testAgent);

        // ── 4. OPEN CommissionPeriod covering test date ────────────────
        testPeriod = new CommissionPeriod();
        testPeriod.setPeriodCode("U6-PERIOD-" + RUN_ID);
        testPeriod.setStartDate(LocalDate.of(2026, 1, 1));
        testPeriod.setEndDate(LocalDate.of(2026, 12, 31));
        testPeriod.setStatus("OPEN");
        testPeriod.setCreatedBy(acctUser.getId());
        testPeriod = periodRepository.save(testPeriod);
    }

    @AfterAll
    void tearDownAll() {
        if (testPeriod != null) {
            entryRepository.findByPeriodId(testPeriod.getId())
                    .forEach(e -> entryRepository.deleteById(e.getId()));
        }
        for (String oid : cleanupOrderIds) {
            transactionRepository.findByOrderIdOrderByCreatedAtDesc(oid)
                    .forEach(t -> transactionRepository.deleteById(t.getId()));
            inventoryMovementRepository.deleteAll(
                    inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(oid));
            orderRepository.deleteById(oid);
        }
        if (testPeriod != null) periodRepository.delete(testPeriod);
        if (testAgent  != null) agentRepository.delete(testAgent);
        if (testProduct != null) productRepository.delete(testProduct);
        if (acctUser   != null) userRepository.delete(acctUser);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Build a single-row CSV with the given parameters. */
    private String csvRow(String receipt, String paymentMethod, String paymentStatus,
                          String source, String agentName, String opPerUnit) {
        return "2026-01-15," + receipt + ",10:00,Test Customer,"
                + source + "," + agentName + "," + paymentMethod + ","
                + "," + testProduct.getProductCode() + ",1," + testProduct.getUnitPrice()
                + ",," + opPerUnit + "," + paymentStatus + "\r\n";
    }

    private String buildCsv(String... rows) {
        StringBuilder sb = new StringBuilder(
                "Date,Receipt#,Time,Customer,Source,Agent,Payment Method,Platform,Product Code,Qty,Unit Price,Base Price,OP per Unit,Payment Status\r\n");
        for (String r : rows) sb.append(r);
        return sb.toString();
    }

    /** Upload CSV → return sessionToken. */
    private String uploadAndGetSession(String csv) throws Exception {
        String json = mockMvc.perform(multipart("/api/import/upload/sales")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("adminSecurityKey", SEC_KEY)
                        .header("Authorization", "Bearer " + acctJwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractJsonString(json, "sessionToken");
    }

    /** Commit a session → return the JSON response string. */
    private String commitSession(String sessionToken) throws Exception {
        String body = "{\"sessionToken\":\"" + sessionToken + "\",\"adminSecurityKey\":\"" + SEC_KEY + "\",\"conflictResolutions\":[]}";
        return mockMvc.perform(post("/api/import/commit")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }

    private long countTransactions(String orderId, String type) {
        return transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(t -> type.equals(t.getTransactionType()))
                .count();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEST U6-a: COD PAID → ACTIVE order + SALE transaction
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void codPaid_import_createsActiveOrder() throws Exception {
        String ref = "U6-PAID-" + System.currentTimeMillis();
        String csv = buildCsv(csvRow(ref, "COD", "PAID", "AGENT", testAgent.getFullName(), "10.00"));

        String session = uploadAndGetSession(csv);
        assertThat(session).isNotBlank();

        String commitResp = commitSession(session);
        assertThat(commitResp).contains(ref);

        // Verify order is ACTIVE
        var opt = orderRepository.findAll().stream()
                .filter(o -> ref.equals(o.getImportRef())).findFirst();
        assertThat(opt).isPresent();
        Order order = opt.get();
        assertThat(order.getStatus()).isEqualTo("ACTIVE");
        assertThat(order.isImported()).isTrue();
        cleanupOrderIds.add(order.getId());

        // Verify SALE transaction exists
        assertThat(countTransactions(order.getId(), "SALE")).isGreaterThanOrEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEST U6-b: COD UNPAID → PENDING_COLLECTION + COLL-DEFER + no commission
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void codUnpaid_import_createsPendingCollection() throws Exception {
        String ref = "U6-UNPD-" + System.currentTimeMillis();
        String csv = buildCsv(csvRow(ref, "COD", "UNPAID", "AGENT", testAgent.getFullName(), "10.00"));

        String session = uploadAndGetSession(csv);
        assertThat(session).isNotBlank();
        commitSession(session);

        var opt = orderRepository.findAll().stream()
                .filter(o -> ref.equals(o.getImportRef())).findFirst();
        assertThat(opt).isPresent();
        Order order = opt.get();
        assertThat(order.getStatus()).isEqualTo("PENDING_COLLECTION");
        assertThat(order.getPendingCollectionAt()).isNotNull();
        cleanupOrderIds.add(order.getId());

        // Verify COLL-DEFER (negative VOID) transaction exists
        assertThat(countTransactions(order.getId(), "VOID")).isGreaterThanOrEqualTo(1);

        // Verify no commission entries created (items from import lack opAmount)
        long entries = entryRepository.findByPeriodIdAndAgentId(testPeriod.getId(), testAgent.getId())
                .stream().filter(e -> order.getId().equals(e.getOrderId())).count();
        assertThat(entries).isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEST U6-c: Collect COD UNPAID → DELIVERED + COLL-SALE
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void collectUnpaidOrder_marksDelivered() throws Exception {
        // Create a fresh COD UNPAID order to collect
        String ref = "U6-COLL-" + System.currentTimeMillis();
        String csv = buildCsv(csvRow(ref, "COD", "UNPAID", "AGENT", testAgent.getFullName(), "10.00"));
        String session = uploadAndGetSession(csv);
        commitSession(session);

        var opt = orderRepository.findAll().stream()
                .filter(o -> ref.equals(o.getImportRef())).findFirst();
        assertThat(opt).isPresent();
        Order order = opt.get();
        assertThat(order.getStatus()).isEqualTo("PENDING_COLLECTION");
        cleanupOrderIds.add(order.getId());

        // Collect via API
        String collectBody = "{\"securityKey\":\"" + SEC_KEY + "\"}";
        mockMvc.perform(patch("/api/orders/" + order.getId() + "/collect")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collectBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.collectedBy").value(acctUser.getFullName()))
                .andExpect(jsonPath("$.collectedAt").isNotEmpty());

        // Reload and verify
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("DELIVERED");
        assertThat(reloaded.getCollectedBy()).isEqualTo(acctUser.getFullName());
        assertThat(reloaded.getCollectedAt()).isNotNull();

        // Verify COLL-SALE transaction exists
        assertThat(countTransactions(order.getId(), "SALE")).isGreaterThanOrEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEST U6-d: Commission created after collection when items have opAmount
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void commissionCreatedAfterCollect_whenItemsHaveOpAmount() throws Exception {
        String ref = "U6-COMM-" + System.currentTimeMillis();
        String csv = buildCsv(csvRow(ref, "COD", "UNPAID", "AGENT", testAgent.getFullName(), "10.00"));
        String session = uploadAndGetSession(csv);
        commitSession(session);

        String orderId = orderRepository.findAll().stream()
                .filter(o -> ref.equals(o.getImportRef()))
                .findFirst().orElseThrow().getId();
        cleanupOrderIds.add(orderId);

        // Collect — import already set opAmount = opPerUnit * qty = 10.00
        String collectBody = "{\"securityKey\":\"" + SEC_KEY + "\"}";
        mockMvc.perform(patch("/api/orders/" + orderId + "/collect")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collectBody))
                .andExpect(status().isOk());

        // Verify commission entries created — query by order ID to avoid matching
        // entries assigned to a different OPEN period from a failed previous run.
        var entries = entryRepository.findByOrderId(orderId);
        assertThat(entries).describedAs("commission entries for order " + orderId).isNotEmpty();
        for (var e : entries) {
            assertThat(e.getOpAmount()).isNotNull();
            assertThat(e.getOpAmount().compareTo(BigDecimal.ZERO)).isGreaterThan(0);
            assertThat(e.getStatus()).isEqualTo("PENDING");
        }
        cleanupEntryIds.addAll(entries.stream().map(CommissionEntry::getId).toList());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEST U6-e: Batch collect multiple orders
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void batchCollect_multipleOrders() throws Exception {
        String ref1 = "U6-BAT1-" + System.currentTimeMillis();
        String ref2 = "U6-BAT2-" + System.currentTimeMillis();
        String csv = buildCsv(
                csvRow(ref1, "COD", "UNPAID", "AGENT", testAgent.getFullName(), ""),
                csvRow(ref2, "COD", "UNPAID", "AGENT", testAgent.getFullName(), ""));
        String session = uploadAndGetSession(csv);
        commitSession(session);

        var orders = orderRepository.findAll().stream()
                .filter(o -> ref1.equals(o.getImportRef()) || ref2.equals(o.getImportRef()))
                .toList();
        assertThat(orders).hasSize(2);
        orders.forEach(o -> assertThat(o.getStatus()).isEqualTo("PENDING_COLLECTION"));
        orders.forEach(o -> cleanupOrderIds.add(o.getId()));

        List<String> ids = orders.stream().map(Order::getId).toList();
        String batchBody = "{\"orderIds\":" + toJsonArray(ids) + ",\"securityKey\":\"" + SEC_KEY + "\"}";

        mockMvc.perform(post("/api/orders/batch-mark-collected")
                        .header("Authorization", "Bearer " + acctJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collected").value(2))
                .andExpect(jsonPath("$.skipped").isArray())
                .andExpect(jsonPath("$.errors").isArray());

        // Verify both are now DELIVERED
        for (Order o : orders) {
            Order reloaded = orderRepository.findById(o.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo("DELIVERED");
        }
    }

    private String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(list.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEST U6-f: Duplicate import detection
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void duplicateReceipt_appearsInDuplicates() throws Exception {
        String ref = "U6-DUP-" + System.currentTimeMillis();
        String csv = buildCsv(csvRow(ref, "CASH", "", "WALK_IN", "", ""));

        // First import → should succeed
        String session1 = uploadAndGetSession(csv);
        assertThat(session1).isNotBlank();
        commitSession(session1);

        var opt = orderRepository.findAll().stream()
                .filter(o -> ref.equals(o.getImportRef())).findFirst();
        assertThat(opt).isPresent();
        cleanupOrderIds.add(opt.get().getId());

        // Second import of same receipt → should appear in duplicates
        String uploadJson = mockMvc.perform(multipart("/api/import/upload/sales")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("adminSecurityKey", SEC_KEY)
                        .header("Authorization", "Bearer " + acctJwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(uploadJson).contains("duplicates");
        assertThat(uploadJson).contains(ref);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TEST U6-g: Mixed payment modes → correct status per row
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void mixedModes_eachGetsCorrectStatus() throws Exception {
        long ts = System.currentTimeMillis();
        String refCash = "U6-MIX-CSH-" + ts;
        String refCodPaid = "U6-MIX-PD-" + ts;
        String refCodUnpd = "U6-MIX-UP-" + ts;

        String csv = buildCsv(
                csvRow(refCash, "CASH", "", "WALK_IN", "", ""),
                csvRow(refCodPaid, "COD", "PAID", "AGENT", testAgent.getFullName(), ""),
                csvRow(refCodUnpd, "COD", "UNPAID", "AGENT", testAgent.getFullName(), ""));

        String session = uploadAndGetSession(csv);
        commitSession(session);

        var cash = orderRepository.findAll().stream()
                .filter(o -> refCash.equals(o.getImportRef())).findFirst();
        var codPaid = orderRepository.findAll().stream()
                .filter(o -> refCodPaid.equals(o.getImportRef())).findFirst();
        var codUnpd = orderRepository.findAll().stream()
                .filter(o -> refCodUnpd.equals(o.getImportRef())).findFirst();

        assertThat(cash).isPresent();
        assertThat(codPaid).isPresent();
        assertThat(codUnpd).isPresent();

        assertThat(cash.get().getStatus()).isEqualTo("ACTIVE");
        assertThat(codPaid.get().getStatus()).isEqualTo("ACTIVE");
        assertThat(codUnpd.get().getStatus()).isEqualTo("PENDING_COLLECTION");

        cleanupOrderIds.add(cash.get().getId());
        cleanupOrderIds.add(codPaid.get().getId());
        cleanupOrderIds.add(codUnpd.get().getId());
    }
}
