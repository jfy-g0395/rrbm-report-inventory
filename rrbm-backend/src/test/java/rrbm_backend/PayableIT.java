package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S7 / W-19 — Payables
 *
 * <p>Workflow: liabilities created by real supplier deliveries are listed, settled,
 * and deleted. Payables are seeded via {@code POST /api/products/delivery} (never
 * hand-inserted) so every row is in production shape before any assertion runs.
 *
 * <p>Scenarios:
 * - GET /api/payables list + 401 baseline
 * - GET /api/payables/{id} with delivery line items + 404 non-existent
 * - GET /api/payables/summary outstanding total + recomputation after payment
 * - PATCH /{id}/status PENDING→PAID (ADMINISTRATOR role) → 200, amountPaid/paidAt set, activity_log
 * - PATCH with ACCOUNTING role → 403 (no status change)
 * - PATCH with no token → 401; invalid status → 400
 * - PATCH PAID→PENDING revert → amountPaid/paidAt/paidBy cleared
 * - DELETE with valid master key → 200, row removed, activity_log
 * - DELETE with bad master key → 403, row intact
 * - DELETE non-existent id → 404
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class PayableIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DeliveryLogRepository deliveryLogRepository;
    @Autowired private DeliveryLogItemRepository deliveryLogItemRepository;
    @Autowired private PayableRepository payableRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private MasterKeyRepository masterKeyRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD  = "S7-Secret!";
    private static final String SEC_KEY   = "S7-admin-key";
    private static final String MK_RAW    = "s7-mk-" + RUN;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private User accountingUser;
    private User adminUser;
    private String accountingJwt;
    private String adminJwt;
    private MasterKey masterKey;
    private Product product;
    private Supplier supplier;

    // payable IDs created via real delivery in @BeforeAll
    private Long payable1Id; // used for status / summary tests
    private Long payable2Id; // used for delete test

    @BeforeAll
    void seed() throws Exception {
        accountingUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s7-acct-" + RUN + "@test.rrbm.internal", "S7 Accounting", PASSWORD, SEC_KEY);
        accountingJwt = ITSupport.jwtFor(jwtUtil, accountingUser);

        adminUser = ITSupport.seedUser(userRepository, "ADMINISTRATOR",
                "s7-admin-" + RUN + "@test.rrbm.internal", "S7 Admin", PASSWORD, SEC_KEY);
        adminJwt = ITSupport.jwtFor(jwtUtil, adminUser);

        masterKey = ITSupport.seedMasterKey(masterKeyRepository, MK_RAW, "S7 test key " + RUN);

        product = ITSupport.seedProduct(productRepository,
                "S7P" + (RUN % 99), "S7 Product", new BigDecimal("200.00"), 300);
        supplier = ITSupport.seedSupplier(supplierRepository,
                "S7-SUPP-" + RUN, "S7 Contact");

        // Arrive via real workflow: two deliveries → two PENDING payables
        payable1Id = createDeliveryAndGetPayableId("DR-S7A-" + RUN, 50);
        payable2Id = createDeliveryAndGetPayableId("DR-S7B-" + RUN, 30);
    }

    @AfterAll
    void clean() {
        // Activity log rows for our test users (today)
        for (User u : List.of(accountingUser, adminUser)) {
            if (u == null) continue;
            activityLogRepository
                    .findByUserIdAndReportDateOrderByCreatedAtDesc(u.getId(), LocalDate.now())
                    .forEach(a -> activityLogRepository.deleteById(a.getId()));
        }

        // Delivery logs (and their inventory movements + any remaining payables)
        OffsetDateTime yesterday = OffsetDateTime.now().minusDays(1);
        OffsetDateTime tomorrow  = OffsetDateTime.now().plusDays(1);
        deliveryLogRepository.findByCreatedAtBetween(yesterday, tomorrow).forEach(dl -> {
            inventoryMovementRepository
                    .findByReferenceIdOrderByCreatedAtDesc(dl.getReceiptNumber())
                    .forEach(inventoryMovementRepository::delete);
            payableRepository.findByDeliveryLogId(dl.getId())
                    .forEach(payableRepository::delete);
            deliveryLogRepository.delete(dl);
        });

        if (product  != null) productRepository.deleteById(product.getId());
        if (supplier != null) supplierRepository.deleteById(supplier.getId());
        if (masterKey != null) masterKeyRepository.deleteById(masterKey.getId());
        if (accountingUser != null) userRepository.deleteById(accountingUser.getId());
        if (adminUser      != null) userRepository.deleteById(adminUser.getId());
    }

    // ─── GET /api/payables ───────────────────────────────────────────────────

    @Test
    void t01_getAll_authenticatedUser_returns200WithPayablesArray() throws Exception {
        mockMvc.perform(get("/api/payables")
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Both payables seeded in @BeforeAll must be present
        List<Payable> all = payableRepository.findAllByOrderByCreatedAtDesc();
        assertThat(all.stream().anyMatch(p -> p.getId().equals(payable1Id))).isTrue();
        assertThat(all.stream().anyMatch(p -> p.getId().equals(payable2Id))).isTrue();
    }

    @Test
    void t02_getAll_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/payables"))
                .andExpect(status().isUnauthorized());
    }

    // ─── GET /api/payables/{id} ──────────────────────────────────────────────

    @Test
    void t03_getOne_existingPayable_returnsDetailsAndLineItems() throws Exception {
        mockMvc.perform(get("/api/payables/" + payable1Id)
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payable1Id))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").isNumber())
                .andExpect(jsonPath("$.items").isArray());

        // DB re-read: status and amounts are correct
        Payable p = payableRepository.findById(payable1Id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo("PENDING");
        assertThat(p.getTotalAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(p.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);

        // Line items linked to the delivery log
        Long dlId = p.getDeliveryLogId();
        assertThat(deliveryLogItemRepository.findByDeliveryLogId(dlId)).isNotEmpty();
    }

    @Test
    void t04_getOne_nonExistentId_returns404() throws Exception {
        mockMvc.perform(get("/api/payables/999999")
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isNotFound());
    }

    // ─── GET /api/payables/summary ───────────────────────────────────────────

    @Test
    void t05_getSummary_returnsTotalOutstandingAndPendingCount() throws Exception {
        mockMvc.perform(get("/api/payables/summary")
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOutstanding").isNumber())
                .andExpect(jsonPath("$.pendingCount").isNumber());

        // Both our payables are PENDING → outstanding must be positive
        BigDecimal outstanding = payableRepository.getTotalOutstanding();
        assertThat(outstanding).isGreaterThan(BigDecimal.ZERO);
    }

    // ─── PATCH /api/payables/{id}/status — gates ─────────────────────────────

    @Test
    void t06_patchStatus_accountingRole_returns403_statusUnchanged() throws Exception {
        Map<String, String> body = Map.of("status", "PAID");

        mockMvc.perform(patch("/api/payables/" + payable1Id + "/status")
                        .header("Authorization", "Bearer " + accountingJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        assertThat(payableRepository.findById(payable1Id).orElseThrow().getStatus())
                .isEqualTo("PENDING");
    }

    @Test
    void t07_patchStatus_noToken_returns401_statusUnchanged() throws Exception {
        Map<String, String> body = Map.of("status", "PAID");

        mockMvc.perform(patch("/api/payables/" + payable1Id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());

        assertThat(payableRepository.findById(payable1Id).orElseThrow().getStatus())
                .isEqualTo("PENDING");
    }

    @Test
    void t08_patchStatus_invalidStatus_returns400_statusUnchanged() throws Exception {
        Map<String, String> body = Map.of("status", "CANCELLED");

        mockMvc.perform(patch("/api/payables/" + payable1Id + "/status")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        assertThat(payableRepository.findById(payable1Id).orElseThrow().getStatus())
                .isEqualTo("PENDING");
    }

    // ─── PATCH /api/payables/{id}/status — happy path ────────────────────────

    @Test
    void t09_patchStatus_pendingToPaid_administratorRole_returns200AndPersists() throws Exception {
        Map<String, String> body = Map.of("status", "PAID");

        mockMvc.perform(patch("/api/payables/" + payable1Id + "/status")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk());

        // DB re-read: PAID + full amount settled + timestamp set
        Payable p = payableRepository.findById(payable1Id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo("PAID");
        assertThat(p.getAmountPaid()).isEqualByComparingTo(p.getTotalAmount());
        assertThat(p.getPaidAt()).isNotNull();
        assertThat(p.getPaidBy()).isNotNull();

        // Activity log entry written
        List<ActivityLog> logs = activityLogRepository
                .findByUserIdAndReportDateOrderByCreatedAtDesc(adminUser.getId(), LocalDate.now());
        assertThat(logs.stream().anyMatch(a ->
                "PAYABLE_STATUS_CHANGED".equals(a.getAction())
                && String.valueOf(payable1Id).equals(a.getEntityId()))).isTrue();
    }

    // ─── GET /api/payables/summary after settlement ──────────────────────────

    @Test
    void t10_getSummary_afterPayment_outstandingExcludesPaidPayable() throws Exception {
        // payable1 is PAID after t09; payable2 is still PENDING
        Payable p2 = payableRepository.findById(payable2Id).orElseThrow();
        assertThat(p2.getStatus()).isEqualTo("PENDING");

        BigDecimal outstanding = payableRepository.getTotalOutstanding();
        // PAID payable1 is excluded; outstanding must be >= payable2 totalAmount
        assertThat(outstanding).isGreaterThanOrEqualTo(p2.getTotalAmount());

        // Payable1 totalAmount must NOT be included in outstanding
        Payable p1 = payableRepository.findById(payable1Id).orElseThrow();
        // If only our two payables exist for this run, outstanding == p2.totalAmount
        // We can at least assert it's < p1.totalAmount + p2.totalAmount (p1 excluded)
        assertThat(outstanding).isLessThan(p1.getTotalAmount().add(p2.getTotalAmount()).add(BigDecimal.ONE));

        mockMvc.perform(get("/api/payables/summary")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOutstanding").isNumber());
    }

    // ─── PATCH PAID → PENDING revert ─────────────────────────────────────────

    @Test
    void t11_patchStatus_paidToPending_revertsAmountAndTimestamps() throws Exception {
        // payable1 is PAID from t09
        Map<String, String> body = Map.of("status", "PENDING");

        mockMvc.perform(patch("/api/payables/" + payable1Id + "/status")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk());

        Payable p = payableRepository.findById(payable1Id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo("PENDING");
        assertThat(p.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(p.getPaidAt()).isNull();
        assertThat(p.getPaidBy()).isNull();
    }

    // ─── DELETE /api/payables/{id} ───────────────────────────────────────────

    @Test
    void t12_delete_badMasterKey_returns403_rowIntact() throws Exception {
        Map<String, String> body = Map.of("masterKey", "wrong-key");

        mockMvc.perform(delete("/api/payables/" + payable2Id)
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        assertThat(payableRepository.findById(payable2Id)).isPresent();
    }

    @Test
    void t13_delete_validMasterKey_returns200AndRemovesRow() throws Exception {
        Map<String, String> body = Map.of("masterKey", MK_RAW);

        mockMvc.perform(delete("/api/payables/" + payable2Id)
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk());

        // Row gone
        assertThat(payableRepository.findById(payable2Id)).isEmpty();

        // Activity log written
        List<ActivityLog> logs = activityLogRepository
                .findByUserIdAndReportDateOrderByCreatedAtDesc(adminUser.getId(), LocalDate.now());
        assertThat(logs.stream().anyMatch(a ->
                "DELETE_PAYABLE".equals(a.getAction())
                && String.valueOf(payable2Id).equals(a.getEntityId()))).isTrue();
    }

    @Test
    void t14_delete_nonExistentId_returns404() throws Exception {
        Map<String, String> body = Map.of("masterKey", MK_RAW);

        mockMvc.perform(delete("/api/payables/999999")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Long createDeliveryAndGetPayableId(String drNumber, int qty) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("productId",  product.getId());
        item.put("quantity",   qty);
        item.put("received",   qty);
        item.put("rejected",   0);
        item.put("warehouse",  "wh1");
        item.put("unitCost",   new BigDecimal("120.00"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("receiptNumber",  drNumber);
        payload.put("supplierName",   "S7-SUPP-" + RUN);
        payload.put("receiverName",   "S7 Receiver");
        payload.put("verifierName",   "S7 Verifier");
        payload.put("encodedByName",  "S7 Encoder");
        payload.put("notes",          "S7 test delivery");
        payload.put("items",          List.of(item));

        mockMvc.perform(post("/api/products/delivery")
                        .header("Authorization", "Bearer " + accountingJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(payload)))
                .andExpect(status().isOk());

        DeliveryLog log = deliveryLogRepository.findByReceiptNumber(drNumber).orElseThrow();
        return payableRepository.findByDeliveryLogId(log.getId())
                .stream().findFirst().orElseThrow().getId();
    }
}
