package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S2 — Backdated "Add Records" commit ({@code POST /api/backdated/commit}).
 *
 * <p>Proves the backdated path reuses the live build + creation logic and routes correctly:
 * <ul>
 *   <li>PAID cash order → ACTIVE, date-prefixed ID, backdated SALE ledger, cash-on-hand up.</li>
 *   <li>UNPAID order → PENDING_COLLECTION, COLL-DEFER ledger, appears in /collections, no commission.</li>
 *   <li>COD order → PENDING, appears in /collections.</li>
 *   <li>Recording-only order → sales counted in ledger, but inventory + cash untouched.</li>
 *   <li>Non-recording order → inventory deducted + cash moved.</li>
 *   <li>Multi-item agent order → all items + per-item commission recorded.</li>
 *   <li>Backdated expense (recording-only vs deduct) → cash skipped vs reconciled; recordingOnly flag set.</li>
 *   <li>lateImported tagging when the target date's report is already closed.</li>
 *   <li>Invalid admin security key → 403; one bad row doesn't block the rest.</li>
 * </ul>
 *
 * <p>Daily-report recompute + "amended" marker is out of scope here (S3); this IT asserts only the
 * commit + routing behavior and the affectedDates result.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class BackdatedEntryIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private CommissionPeriodRepository commissionPeriodRepository;
    @Autowired private CommissionEntryRepository commissionEntryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private DailyReportRepository dailyReportRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private ExpenseRepository expenseRepository;
    @Autowired private ExpenseCategoryRepository expenseCategoryRepository;
    @Autowired private CashLedgerRepository cashLedgerRepository;
    @Autowired private CashLedgerService cashLedgerService;
    @Autowired private DailyReportService dailyReportService;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S2-Secret!";
    private static final String SEC_KEY = "S2-admin-key";

    private User admin;
    private User closer;   // closes reports first, so we can prove closedBy is preserved on amend
    private String jwt;
    private Product product;
    private Agent agent;
    private CommissionPeriod openPeriod;
    private Long categoryId;

    private final ObjectMapper om = new ObjectMapper();

    @BeforeAll
    void seed() {
        admin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "s2-admin-" + RUN + "@test.rrbm.internal", "S2 Admin", PASSWORD, SEC_KEY);
        jwt = ITSupport.jwtFor(jwtUtil, admin);

        closer = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "s2-closer-" + RUN + "@test.rrbm.internal", "S2 Closer", PASSWORD, SEC_KEY);

        product = ITSupport.seedProduct(productRepository,
                "S2P" + (RUN % 1000), "S2 Test Product", new BigDecimal("200.00"), 500);

        agent = ITSupport.seedAgent(agentRepository, "S2-AGENT-" + RUN, "S2 Test Agent", "Zone S2");

        // Open period spanning the past year so backdated dates are covered.
        openPeriod = ITSupport.seedOpenPeriod(commissionPeriodRepository,
                LocalDate.now().minusDays(400), LocalDate.now().plusDays(30));

        // Reuse a seeded system category, or create one if none exist.
        categoryId = expenseCategoryRepository.findAll().stream().findFirst()
                .map(ExpenseCategory::getId)
                .orElseGet(() -> {
                    ExpenseCategory c = new ExpenseCategory();
                    c.setCode("S2CAT" + (RUN % 1000));
                    c.setName("S2 Test Category");
                    c.setSystemDefined(false);
                    c.setActive(true);
                    return expenseCategoryRepository.save(c).getId();
                });
    }

    @AfterAll
    void clean() {
        LocalDateTime from = LocalDateTime.now().minusDays(420);
        LocalDateTime to   = LocalDateTime.now().plusDays(1);

        transactionRepository.deleteAll();
        inventoryMovementRepository.deleteAll();
        activityLogRepository.deleteAll();
        commissionEntryRepository.deleteAll();
        cashLedgerRepository.deleteAll();
        expenseRepository.findAll().stream()
                .filter(e -> e.getAdminId() != null && e.getAdminId().equals(admin.getId()))
                .forEach(expenseRepository::delete);
        orderRepository.findByCreatedAtBetween(from, to)
                .forEach(o -> orderRepository.deleteById(o.getId()));
        // Clean any daily reports we closed for backdated test dates.
        for (int d = 1; d <= 420; d++) {
            dailyReportRepository.findByReportDate(LocalDate.now().minusDays(d))
                    .ifPresent(dailyReportRepository::delete);
        }
        if (openPeriod != null) commissionPeriodRepository.deleteById(openPeriod.getId());
        if (agent != null) agentRepository.deleteById(agent.getId());
        if (product != null) productRepository.deleteById(product.getId());
        if (closer != null) userRepository.deleteById(closer.getId());
        if (admin != null) userRepository.deleteById(admin.getId());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Map<String, Object> item(int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", product.getId());
        m.put("productName", product.getName());
        m.put("quantity", qty);
        m.put("unitPrice", new BigDecimal("200.00"));
        m.put("warehouse", "wh1");
        return m;
    }

    private Map<String, Object> agentItem(int qty, String base, String op) {
        Map<String, Object> m = item(qty);
        m.put("basePrice", new BigDecimal(base));
        m.put("opPerUnit", new BigDecimal(op));
        return m;
    }

    private Map<String, Object> orderEntry(LocalDate date, boolean recordingOnly, String paymentStatus,
                                           String paymentMode, String customer, List<Map<String, Object>> items) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("date", date.toString());
        o.put("recordingOnly", recordingOnly);
        if (paymentStatus != null) o.put("paymentStatus", paymentStatus);
        o.put("customerName", customer);
        o.put("source", "WALK_IN");
        o.put("paymentMode", paymentMode);
        o.put("items", items);
        return o;
    }

    private Map<String, Object> expenseEntry(LocalDate date, boolean recordingOnly, String method, String amount) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("date", date.toString());
        e.put("recordingOnly", recordingOnly);
        e.put("paymentMethod", method);
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("itemDescription", "S2 expense item");
        it.put("amount", new BigDecimal(amount));
        it.put("categoryId", categoryId);
        e.put("items", List.of(it));
        return e;
    }

    private JsonNode commit(String securityKey, List<Map<String, Object>> orders,
                            List<Map<String, Object>> expenses) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adminSecurityKey", securityKey);
        body.put("orders", orders);
        body.put("expenses", expenses);
        String resp = mockMvc.perform(post("/api/backdated/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(resp);
    }

    private static String datePrefix(LocalDate d) {
        return String.format("%02d%02d%02d", d.getDayOfMonth(), d.getMonthValue(), d.getYear() % 100);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void t01_paidCashOrder_backdatedSaleLedger_andCashUp() throws Exception {
        LocalDate date = LocalDate.now().minusDays(20);
        BigDecimal cashBefore = cashLedgerService.getCashOnHandAsOf(date);

        JsonNode res = commit(SEC_KEY,
                List.of(orderEntry(date, false, "PAID", "CASH", "S2-Paid-" + RUN, List.of(item(2)))),
                List.of());

        assertThat(res.get("committed").asInt()).isEqualTo(1);
        assertThat(res.get("errors")).isEmpty();
        String orderId = res.get("committedOrders").get(0).get("orderId").asText();

        Order saved = orderRepository.findById(orderId).orElseThrow();
        assertThat(orderId).startsWith(datePrefix(date) + "-");          // date-prefixed ID
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getPaymentStatus()).isEqualTo("PAID");
        assertThat(saved.getCreatedAt().toLocalDate()).isEqualTo(date);  // backdated createdAt
        assertThat(transactionRepository.existsByTransactionCode("SALE-" + orderId)).isTrue();

        // Cash-on-hand for that date increased by the order total (400.00).
        BigDecimal cashAfter = cashLedgerService.getCashOnHandAsOf(date);
        assertThat(cashAfter.subtract(cashBefore)).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(res.get("affectedDates").get(0).asText()).isEqualTo(date.toString());
    }

    @Test
    void t02_unpaidOrder_routesToCollections_noCommission() throws Exception {
        LocalDate date = LocalDate.now().minusDays(19);
        // Agent order with commission fields, so "no commission" proves *deferral*, not just absence.
        Map<String, Object> entry = orderEntry(date, false, "UNPAID", "CASH", "S2-Unpaid-" + RUN,
                List.of(agentItem(2, "150.00", "50.00")));
        entry.put("agentId", agent.getId());
        entry.put("source", "AGENT");

        JsonNode res = commit(SEC_KEY, List.of(entry), List.of());

        String orderId = res.get("committedOrders").get(0).get("orderId").asText();
        Order saved = orderRepository.findById(orderId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("PENDING_COLLECTION");
        assertThat(saved.getPaymentStatus()).isEqualTo("UNPAID");
        assertThat(saved.getPendingCollectionAt()).isNotNull();
        assertThat(transactionRepository.existsByTransactionCode("COLL-DEFER-" + orderId)).isTrue();

        // Routed to collection in the result + listed by GET /api/orders/collections.
        assertThat(res.get("committedOrders").get(0).get("collection").asBoolean()).isTrue();
        assertThat(collectionsContains(orderId)).isTrue();

        // Unpaid → commission deferred (none yet) even though it has an agent + opAmount.
        assertThat(commissionEntryRepository.existsByOrderId(orderId)).isFalse();
    }

    @Test
    void t03_codOrder_pending_appearsInCollections() throws Exception {
        LocalDate date = LocalDate.now().minusDays(18);
        JsonNode res = commit(SEC_KEY,
                List.of(orderEntry(date, false, null, "COD", "S2-COD-" + RUN, List.of(item(1)))),
                List.of());

        String orderId = res.get("committedOrders").get(0).get("orderId").asText();
        Order saved = orderRepository.findById(orderId).orElseThrow();
        // Backdated COD behaves exactly like a live COD order: status PENDING (set by
        // createOrderAtDate), collectable later via PATCH /collect. NOTE: it is intentionally
        // NOT listed by GET /api/orders/collections — that endpoint returns only
        // PENDING_COLLECTION (the M-7 fix excludes PENDING to avoid double-SALE on collect).
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getPaymentMode()).isEqualTo("COD");
        assertThat(collectionsContains(orderId)).isFalse();
    }

    @Test
    void t04_recordingOnlyOrder_countsSales_butStockAndCashUntouched() throws Exception {
        LocalDate date = LocalDate.now().minusDays(17);
        int stockBefore = productRepository.findById(product.getId()).orElseThrow().getStockWh1();
        BigDecimal cashBefore = cashLedgerService.getCashOnHandAsOf(date);

        JsonNode res = commit(SEC_KEY,
                List.of(orderEntry(date, true, "PAID", "CASH", "S2-RecOnly-" + RUN, List.of(item(3)))),
                List.of());

        String orderId = res.get("committedOrders").get(0).get("orderId").asText();
        // SALE ledger still posted (sales counted for the report).
        assertThat(transactionRepository.existsByTransactionCode("SALE-" + orderId)).isTrue();
        // Inventory untouched.
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStockWh1())
                .isEqualTo(stockBefore);
        // Cash-on-hand untouched.
        assertThat(cashLedgerService.getCashOnHandAsOf(date)).isEqualByComparingTo(cashBefore);
        assertThat(res.get("committedOrders").get(0).get("recordingOnly").asBoolean()).isTrue();
    }

    @Test
    void t05_nonRecordingOrder_deductsStock() throws Exception {
        LocalDate date = LocalDate.now().minusDays(16);
        int stockBefore = productRepository.findById(product.getId()).orElseThrow().getStockWh1();

        commit(SEC_KEY,
                List.of(orderEntry(date, false, "PAID", "CASH", "S2-Deduct-" + RUN, List.of(item(4)))),
                List.of());

        assertThat(productRepository.findById(product.getId()).orElseThrow().getStockWh1())
                .isEqualTo(stockBefore - 4);
    }

    @Test
    void t06_multiItemAgentOrder_recordsItemsAndCommissionPerItem() throws Exception {
        LocalDate date = LocalDate.now().minusDays(15);
        Map<String, Object> entry = orderEntry(date, false, "PAID", "CASH", "S2-Multi-" + RUN,
                List.of(agentItem(2, "150.00", "50.00"), agentItem(3, "150.00", "40.00")));
        entry.put("agentId", agent.getId());
        entry.put("source", "AGENT");

        JsonNode res = commit(SEC_KEY, List.of(entry), List.of());
        String orderId = res.get("committedOrders").get(0).get("orderId").asText();

        Order saved = orderRepository.findById(orderId).orElseThrow();
        assertThat(saved.getAgentId()).isEqualTo(agent.getId());
        // One commission entry per line item proves both items were recorded with opAmount.
        // opPerUnit × qty = 50×2 = 100.00 and 40×3 = 120.00.
        List<CommissionEntry> entries = commissionEntryRepository.findAll().stream()
                .filter(c -> orderId.equals(c.getOrderId())).toList();
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(CommissionEntry::getOpAmount))
                .containsExactlyInAnyOrder(new BigDecimal("100.00"), new BigDecimal("120.00"));
    }

    @Test
    void t07_backdatedExpense_deductVsRecordingOnly() throws Exception {
        LocalDate date = LocalDate.now().minusDays(14);
        BigDecimal cashBefore = cashLedgerService.getCashOnHandAsOf(date);

        JsonNode res = commit(SEC_KEY, List.of(), List.of(
                expenseEntry(date, false, "CASH", "300.00"),   // deduct: cash out
                expenseEntry(date, true,  "CASH", "500.00")));  // recording-only: no cash

        assertThat(res.get("committedExpenses")).hasSize(2);

        // Net cash effect = only the deduct expense (-300.00); recording-only skipped.
        BigDecimal cashAfter = cashLedgerService.getCashOnHandAsOf(date);
        assertThat(cashBefore.subtract(cashAfter)).isEqualByComparingTo(new BigDecimal("300.00"));

        long recOnly = expenseRepository.findAll().stream()
                .filter(e -> admin.getId().equals(e.getAdminId()) && e.isRecordingOnly()).count();
        assertThat(recOnly).isGreaterThanOrEqualTo(1);
    }

    @Test
    void t08_closedDate_taggedLateImported() throws Exception {
        LocalDate date = LocalDate.now().minusDays(13);
        // Close the report for that date first (import-style close).
        dailyReportRepository.findByReportDate(date).ifPresent(dailyReportRepository::delete);
        dailyReportService.closeForImportDate(admin.getId(), admin.getFullName(), date);
        assertThat(dailyReportRepository.findByReportDate(date)).isPresent();

        JsonNode res = commit(SEC_KEY,
                List.of(orderEntry(date, false, "PAID", "CASH", "S2-Late-" + RUN, List.of(item(1)))),
                List.of());

        String orderId = res.get("committedOrders").get(0).get("orderId").asText();
        assertThat(orderRepository.findById(orderId).orElseThrow().isLateImported()).isTrue();
        assertThat(res.get("committedOrders").get(0).get("lateImported").asBoolean()).isTrue();
        // affectedDates surfaces the closed date for S3's recompute.
        assertThat(jsonArrayContains(res.get("affectedDates"), date.toString())).isTrue();
    }

    @Test
    void t09_invalidSecurityKey_returns403() throws Exception {
        LocalDate date = LocalDate.now().minusDays(12);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adminSecurityKey", "wrong-key");
        body.put("orders", List.of(orderEntry(date, false, "PAID", "CASH", "S2-Bad-" + RUN, List.of(item(1)))));
        body.put("expenses", List.of());

        mockMvc.perform(post("/api/backdated/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void t10_perRowError_doesNotBlockOtherRows() throws Exception {
        LocalDate date = LocalDate.now().minusDays(11);
        // Row 0: invalid (no items). Row 1: valid.
        Map<String, Object> bad = orderEntry(date, false, "PAID", "CASH", "S2-BadRow-" + RUN, List.of());
        Map<String, Object> good = orderEntry(date, false, "PAID", "CASH", "S2-GoodRow-" + RUN, List.of(item(1)));

        List<Map<String, Object>> orders = new ArrayList<>();
        orders.add(bad);
        orders.add(good);
        JsonNode res = commit(SEC_KEY, orders, List.of());

        assertThat(res.get("committedOrders")).hasSize(1);
        assertThat(res.get("errors")).hasSize(1);
        assertThat(res.get("errors").get(0).get("type").asText()).isEqualTo("order");
        assertThat(res.get("errors").get(0).get("index").asInt()).isEqualTo(0);
    }

    // ── S3: daily-report recompute + amended ─────────────────────────────────────

    @Test
    void t11_paidOrderOnClosedDate_recomputesReport_marksAmended() throws Exception {
        LocalDate date = LocalDate.now().minusDays(40);
        // Close the report for that date with a DIFFERENT user, so we can prove closedBy is preserved.
        dailyReportRepository.findByReportDate(date).ifPresent(dailyReportRepository::delete);
        dailyReportService.closeForImportDate(closer.getId(), closer.getFullName(), date);
        DailyReport before = dailyReportRepository.findByReportDate(date).orElseThrow();
        BigDecimal grossBefore = nz(before.getGrossSales());
        BigDecimal cashBefore = nz(before.getCashOnHand());
        assertThat(before.isAmended()).isFalse();

        JsonNode res = commit(SEC_KEY,
                List.of(orderEntry(date, false, "PAID", "CASH", "S3-Late-" + RUN, List.of(item(2)))),
                List.of());

        // Result advertises the amended date.
        assertThat(jsonArrayContains(res.get("amendedReports"), date.toString())).isTrue();

        DailyReport after = dailyReportRepository.findByReportDate(date).orElseThrow();
        assertThat(after.isAmended()).isTrue();
        assertThat(after.getAmendedBy()).isEqualTo(admin.getId());      // who amended
        assertThat(after.getAmendedAt()).isNotNull();
        assertThat(after.getClosedBy()).isEqualTo(closer.getId());      // original close preserved
        // Sales + cash rolled into the snapshot (order total = 400.00).
        assertThat(nz(after.getGrossSales()).subtract(grossBefore)).isEqualByComparingTo("400.00");
        assertThat(nz(after.getCashOnHand()).subtract(cashBefore)).isEqualByComparingTo("400.00");
    }

    @Test
    void t12_cashCascade_recomputesLaterClosedReports() throws Exception {
        LocalDate early = LocalDate.now().minusDays(38);
        LocalDate later = LocalDate.now().minusDays(36);   // later closed day, no new entry of its own
        for (LocalDate d : List.of(early, later)) {
            dailyReportRepository.findByReportDate(d).ifPresent(dailyReportRepository::delete);
            dailyReportService.closeForImportDate(closer.getId(), closer.getFullName(), d);
        }
        BigDecimal laterCashBefore = nz(dailyReportRepository.findByReportDate(later).orElseThrow().getCashOnHand());

        // A cash order on the EARLY date shifts the running cash balance for the LATER closed day too.
        JsonNode res = commit(SEC_KEY,
                List.of(orderEntry(early, false, "PAID", "CASH", "S3-Cascade-" + RUN, List.of(item(5)))),
                List.of());

        // Both the own date and the later (cascade-only) date are amended.
        assertThat(jsonArrayContains(res.get("amendedReports"), early.toString())).isTrue();
        assertThat(jsonArrayContains(res.get("amendedReports"), later.toString())).isTrue();

        DailyReport later2 = dailyReportRepository.findByReportDate(later).orElseThrow();
        assertThat(later2.isAmended()).isTrue();
        // later day's frozen cash snapshot increased by the early order's cash (5 × 200 = 1000.00),
        // even though no order/expense was recorded on the later day itself.
        assertThat(nz(later2.getCashOnHand()).subtract(laterCashBefore)).isEqualByComparingTo("1000.00");
    }

    @Test
    void t13_recordingOnlyOnClosedDate_amendsOwnDateOnly_noCascade() throws Exception {
        LocalDate early = LocalDate.now().minusDays(34);
        LocalDate later = LocalDate.now().minusDays(32);
        for (LocalDate d : List.of(early, later)) {
            dailyReportRepository.findByReportDate(d).ifPresent(dailyReportRepository::delete);
            dailyReportService.closeForImportDate(closer.getId(), closer.getFullName(), d);
        }
        DailyReport earlyBefore = dailyReportRepository.findByReportDate(early).orElseThrow();
        BigDecimal earlyGrossBefore = nz(earlyBefore.getGrossSales());
        BigDecimal earlyCashBefore = nz(earlyBefore.getCashOnHand());

        // Recording-only PAID cash order on the early date: sales counted, but NO cash moved.
        JsonNode res = commit(SEC_KEY,
                List.of(orderEntry(early, true, "PAID", "CASH", "S3-RecOnly-" + RUN, List.of(item(3)))),
                List.of());

        // Only the own date is amended; no cash cascade to the later day.
        assertThat(jsonArrayContains(res.get("amendedReports"), early.toString())).isTrue();
        assertThat(jsonArrayContains(res.get("amendedReports"), later.toString())).isFalse();
        assertThat(dailyReportRepository.findByReportDate(later).orElseThrow().isAmended()).isFalse();

        DailyReport earlyAfter = dailyReportRepository.findByReportDate(early).orElseThrow();
        assertThat(earlyAfter.isAmended()).isTrue();
        // Sales counted (3 × 200 = 600.00) but cash untouched.
        assertThat(nz(earlyAfter.getGrossSales()).subtract(earlyGrossBefore)).isEqualByComparingTo("600.00");
        assertThat(nz(earlyAfter.getCashOnHand())).isEqualByComparingTo(earlyCashBefore);
    }

    // ── small helpers ──────────────────────────────────────────────────────────

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private boolean collectionsContains(String orderId) throws Exception {
        String resp = mockMvc.perform(get("/api/orders/collections")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = om.readTree(resp);
        for (JsonNode n : arr) {
            if (orderId.equals(n.path("id").asText())) return true;
        }
        return false;
    }

    private static boolean jsonArrayContains(JsonNode arr, String value) {
        for (JsonNode n : arr) if (value.equals(n.asText())) return true;
        return false;
    }
}
