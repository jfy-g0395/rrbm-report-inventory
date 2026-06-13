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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S2 gap — Force-close path
 *
 * Tests the force-close branch of POST /api/reports/close-daily. Force-close requires
 * dual-auth: the caller's personal adminSecurityKey AND any SUPER_ADMIN's security key.
 * When both are valid, uncollected PENDING/ACTIVE orders are moved to PENDING_COLLECTION
 * and their SALE transactions are reversed via a COLL-DEFER-{id} ledger entry so they
 * do not inflate today's financial snapshot.
 *
 * Run command (DB up + migrated):
 * <pre>mvn test -Dtest=ForceCloseIT</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ForceCloseIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private DailyReportRepository dailyReportRepository;
    @Autowired private MasterKeyRepository masterKeyRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long   RUN            = System.currentTimeMillis() % 100000;
    private static final String SEC_KEY        = "FC-key-" + RUN;
    private static final String MASTER_KEY_RAW = "FC-master-" + RUN;

    private User superAdmin;
    private User accountingUser;
    private String jwt;
    private MasterKey masterKey;
    private Product product;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void seed() {
        superAdmin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "fc-super-" + RUN + "@test.rrbm.internal",
                "FC Super Admin", "FC-pw1-" + RUN, SEC_KEY);

        accountingUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "fc-acct-" + RUN + "@test.rrbm.internal",
                "FC Accounting", "FC-pw2-" + RUN, SEC_KEY);

        jwt = ITSupport.jwtFor(jwtUtil, accountingUser);

        masterKey = ITSupport.seedMasterKey(masterKeyRepository, MASTER_KEY_RAW, "FC Test Key " + RUN);

        product = ITSupport.seedProduct(productRepository,
                "SFC" + (RUN % 99), "FC Test Product", new BigDecimal("250.00"), 200);
    }

    @AfterAll
    void clean() {
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);
        transactionRepository.deleteAll();
        inventoryMovementRepository.deleteAll();
        activityLogRepository.deleteAll();
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        LocalDateTime tomorrow  = LocalDateTime.now().plusDays(1);
        orderRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(o -> orderRepository.deleteById(o.getId()));
        if (product        != null) productRepository.deleteById(product.getId());
        if (masterKey      != null) masterKeyRepository.deleteById(masterKey.getId());
        if (accountingUser != null) userRepository.deleteById(accountingUser.getId());
        if (superAdmin     != null) userRepository.deleteById(superAdmin.getId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String createCodOrder(String customerName) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("productId",   product.getId());
        item.put("productName", product.getName());
        item.put("quantity",    2);
        item.put("unitPrice",   new BigDecimal("250.00"));
        item.put("warehouse",   "wh1");

        Map<String, Object> req = new HashMap<>();
        req.put("customerName", customerName);
        req.put("source",       "WALK_IN");
        req.put("paymentMode",  "COD");
        req.put("items",        List.of(item));

        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asText();
    }

    private Map<String, Object> forceCloseBody(String adminKey, String superAdminKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("masterKey",  MASTER_KEY_RAW);
        body.put("forceClose", true);
        if (adminKey      != null) body.put("adminSecurityKey",      adminKey);
        if (superAdminKey != null) body.put("superAdminSecurityKey", superAdminKey);
        return body;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Happy path: valid master key + forceClose=true + correct adminSecurityKey +
     * correct superAdminSecurityKey.
     *
     * Expected outcome:
     *   - 200 with message "Daily sales closed successfully"
     *   - Response includes unfulfilledOrders ≥ 1 and unfulfilledAmount > 0
     *   - daily_reports row written with unfulfilledOrders ≥ 1, unfulfilledAmount > 0
     *   - The COD order status is PENDING_COLLECTION with pendingCollectionAt set
     *   - COLL-DEFER-{id} transaction created in the ledger (reverses the original SALE)
     */
    @Test
    void t01_forceClose_validDualKey_defersPendingOrders() throws Exception {
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        String orderId = createCodOrder("FC-Force-" + RUN);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo("PENDING");

        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(mapper.writeValueAsString(forceCloseBody(SEC_KEY, SEC_KEY))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Daily sales closed successfully"))
                .andExpect(jsonPath("$.unfulfilledOrders").exists())
                .andExpect(jsonPath("$.unfulfilledAmount").exists());

        // Snapshot written with unfulfilled stats
        DailyReport report = dailyReportRepository.findByReportDate(LocalDate.now()).orElseThrow();
        assertThat(report.getUnfulfilledOrders()).isGreaterThanOrEqualTo(1);
        assertThat(report.getUnfulfilledAmount()).isGreaterThan(BigDecimal.ZERO);

        // Order deferred: status PENDING_COLLECTION, timestamp set
        Order deferred = orderRepository.findById(orderId).orElseThrow();
        assertThat(deferred.getStatus()).isEqualTo("PENDING_COLLECTION");
        assertThat(deferred.getPendingCollectionAt()).isNotNull();

        // SALE reversed by COLL-DEFER in the ledger
        assertThat(transactionRepository.existsByTransactionCode("COLL-DEFER-" + orderId)).isTrue();
    }

    /**
     * Wrong adminSecurityKey → 400; no daily report written; order status unchanged.
     *
     * The service validates the caller's personal key before touching any orders,
     * so a wrong key is a hard rejection with no side effects.
     */
    @Test
    void t02_forceClose_wrongAdminKey_returns400AndNoSideEffects() throws Exception {
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        String orderId = createCodOrder("FC-WrongKey-" + RUN);

        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(mapper.writeValueAsString(forceCloseBody("wrong-key-" + RUN, SEC_KEY))))
                .andExpect(status().isBadRequest());

        // No daily report written
        assertThat(dailyReportRepository.findByReportDate(LocalDate.now())).isEmpty();

        // Order not moved — still PENDING
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo("PENDING");

        // No COLL-DEFER created
        assertThat(transactionRepository.existsByTransactionCode("COLL-DEFER-" + orderId)).isFalse();
    }

    /**
     * Missing superAdminSecurityKey → 400; no daily report written; order status unchanged.
     *
     * Dual-auth requires both keys. If the SUPER_ADMIN key is absent or blank,
     * the service rejects the request after validating the admin key.
     */
    @Test
    void t03_forceClose_missingSuperAdminKey_returns400AndNoSideEffects() throws Exception {
        dailyReportRepository.findByReportDate(LocalDate.now()).ifPresent(dailyReportRepository::delete);

        String orderId = createCodOrder("FC-NoSuper-" + RUN);

        // superAdminSecurityKey intentionally omitted → defaults to empty string in controller
        mockMvc.perform(post("/api/reports/close-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(mapper.writeValueAsString(forceCloseBody(SEC_KEY, null))))
                .andExpect(status().isBadRequest());

        // No daily report written
        assertThat(dailyReportRepository.findByReportDate(LocalDate.now())).isEmpty();

        // Order not moved — still PENDING
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo("PENDING");

        // No COLL-DEFER created
        assertThat(transactionRepository.existsByTransactionCode("COLL-DEFER-" + orderId)).isFalse();
    }
}
