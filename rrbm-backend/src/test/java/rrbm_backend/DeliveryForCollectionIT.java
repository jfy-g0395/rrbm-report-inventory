package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Fix 1 + Fix 3 — "for collection" fulfilment of a scheduled delivery, and delivery-day
 * visibility in the order list.
 *
 * <p>Fix 1: at "Mark Delivered" a scheduled order can be delivered FOR COLLECTION — the boxes
 * leave the warehouse (stock deducted) but payment is deferred. The order goes PENDING_COLLECTION
 * and the ledger records SALE(+X) + COLL-DEFER(-X) BOTH dated the delivery day, so the day's net
 * is ₱0 until the payment is collected via the existing /collect endpoint (which posts COLL-SALE).
 *
 * <p>Fix 3: a scheduled order keeps its (past) scheduling-day createdAt but must still surface in
 * the DELIVERY day's order list (/api/orders/today), via the new createdAt-OR-deliveredAt query.
 *
 * <p>Run: {@code mvn test -Dtest=DeliveryForCollectionIT}
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class DeliveryForCollectionIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private CashLedgerRepository cashLedgerRepository;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "FC-Secret!";
    private static final String SEC_KEY = "FC-admin-key-" + RUN;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private User admin;
    private String jwt;
    private Product product;

    @BeforeAll
    void seed() {
        admin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "fc-" + RUN + "@test.rrbm.internal", "FC Admin " + RUN, PASSWORD, SEC_KEY);
        jwt = ITSupport.jwtFor(jwtUtil, admin);
        product = ITSupport.seedProduct(productRepository,
                "FC" + (RUN % 10000), "FC Widget " + RUN, new BigDecimal("100.00"), 500);
    }

    @AfterAll
    void clean() {
        try { transactionRepository.deleteAll(); } catch (Exception ignored) {}
        try { inventoryMovementRepository.deleteAll(); } catch (Exception ignored) {}
        try { cashLedgerRepository.deleteAll(); } catch (Exception ignored) {}
        try { activityLogRepository.deleteAll(); } catch (Exception ignored) {}
        try {
            orderRepository.findAll().stream()
                    .filter(o -> o.getCustomerName() != null && o.getCustomerName().startsWith("FC-"))
                    .forEach(o -> orderRepository.deleteById(o.getId()));
        } catch (Exception ignored) {}
        try { if (product != null) productRepository.deleteById(product.getId()); } catch (Exception ignored) {}
        try { if (admin != null) userRepository.deleteById(admin.getId()); } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Create a SCHEDULED_DELIVERY order via the real API, scheduled for {@code date}. */
    private String createScheduledOrder(String customer, int qty, LocalDate date) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product.getId());
        item.put("productName", product.getName());
        item.put("quantity", qty);
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("warehouse", "wh1");

        Map<String, Object> req = new HashMap<>();
        req.put("customerName", customer);
        req.put("source", "WALK_IN");
        req.put("paymentMode", "CASH");
        req.put("items", List.of(item));
        req.put("scheduledDeliveryDate", date.toString());

        String json = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(MAPPER.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = MAPPER.readTree(json).get("id").asText();
        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo("SCHEDULED_DELIVERY");
        return id;
    }

    /** Confirm the final order (driver + helper required). */
    private void confirm(String id) throws Exception {
        mockMvc.perform(post("/api/orders/" + id + "/confirm-delivery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content("{\"driver\":\"FC Driver\",\"helpers\":\"FC Helper\"}"))
                .andExpect(status().isOk());
    }

    private boolean todayListContains(String id) throws Exception {
        String json = mockMvc.perform(get("/api/orders/today")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = MAPPER.readTree(json);
        for (JsonNode n : arr) if (id.equals(n.get("id").asText())) return true;
        return false;
    }

    private long txnCount(String code) {
        return transactionRepository.findAll().stream()
                .filter(t -> code.equals(t.getTransactionCode())).count();
    }

    private Transaction txn(String code) {
        return transactionRepository.findAll().stream()
                .filter(t -> code.equals(t.getTransactionCode())).findFirst().orElseThrow();
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    void t01_forCollection_defersPaymentAndNetsLedgerToZeroOnDeliveryDay() throws Exception {
        LocalDate today = LocalDate.now();
        int startStock = productRepository.findById(product.getId()).orElseThrow().getStockWh1();

        String id = createScheduledOrder("FC-ForCollect-" + RUN, 3, today);
        confirm(id);

        // Fulfil FOR COLLECTION
        mockMvc.perform(post("/api/orders/" + id + "/fulfill-delivery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content("{\"mode\":\"FOR_COLLECTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_COLLECTION"));

        Order o = orderRepository.findById(id).orElseThrow();
        assertThat(o.getStatus()).isEqualTo("PENDING_COLLECTION");
        assertThat(o.getPaymentStatus()).isEqualTo("UNPAID");
        assertThat(o.getPendingCollectionAt()).isNotNull();
        assertThat(o.getDeliveredAt()).isNotNull();

        // Boxes delivered → stock deducted.
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStockWh1())
                .isEqualTo(startStock - 3);

        // Ledger: SALE(+300) + COLL-DEFER(-300), BOTH dated today → nets to 0 on the delivery day.
        Transaction sale  = txn("SALE-" + id);
        Transaction defer = txn("COLL-DEFER-" + id);
        assertThat(sale.getAmount()).isEqualByComparingTo("300.00");
        assertThat(defer.getAmount()).isEqualByComparingTo("-300.00");
        assertThat(sale.getEffectiveDate()).isEqualTo(today);
        assertThat(defer.getEffectiveDate()).isEqualTo(today);   // the Fix-1 dating fix
        assertThat(sale.getAmount().add(defer.getAmount())).isEqualByComparingTo("0.00");

        // Not yet collected — no COLL-SALE, no commission.
        assertThat(txnCount("COLL-SALE-" + id)).isEqualTo(0);

        // Fix 3: appears in the delivery-day order list.
        assertThat(todayListContains(id)).isTrue();

        // Now collect via the existing endpoint → revenue recognised, order DELIVERED.
        mockMvc.perform(patch("/api/orders/" + id + "/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content("{\"securityKey\":\"" + SEC_KEY + "\",\"paymentMode\":\"CASH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        Order collected = orderRepository.findById(id).orElseThrow();
        assertThat(collected.getStatus()).isEqualTo("DELIVERED");
        assertThat(collected.getCollectedAt()).isNotNull();
        assertThat(txnCount("COLL-SALE-" + id)).isEqualTo(1);

        // Full ledger for the order = +300 (SALE - COLL-DEFER + COLL-SALE).
        BigDecimal net = transactionRepository.findAll().stream()
                .filter(t -> id.equals(t.getOrderId()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("300.00");
    }

    @Test
    void t02_paidPath_unchanged_recordsSaleAndDelivers() throws Exception {
        String id = createScheduledOrder("FC-Paid-" + RUN, 2, LocalDate.now());
        confirm(id);

        // Default (no body) fulfil → PAID path, unchanged behaviour.
        mockMvc.perform(post("/api/orders/" + id + "/fulfill-delivery")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo("DELIVERED");
        assertThat(txnCount("SALE-" + id)).isEqualTo(1);
        assertThat(txnCount("COLL-DEFER-" + id)).isEqualTo(0);   // paid path never defers
    }

    @Test
    void t03_fix3_deliveredTodayButCreatedEarlier_showsInTodayList() throws Exception {
        String id = createScheduledOrder("FC-Backdated-" + RUN, 1, LocalDate.now());

        // Simulate an order scheduled days ago: backdate createdAt (created_at is updatable=false).
        LocalDateTime backdated = LocalDateTime.now().minusDays(3);
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(backdated), id);

        confirm(id);
        mockMvc.perform(post("/api/orders/" + id + "/fulfill-delivery")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());

        LocalDate today = LocalDate.now();
        // Old createdAt-only query would MISS it (created 3 days ago)...
        assertThat(orderRepository.findByCreatedAtDate(today).stream().anyMatch(o -> o.getId().equals(id)))
                .isFalse();
        // ...but the new list query and the /today endpoint include it (delivered today).
        assertThat(orderRepository.findForTodayList(today).stream().anyMatch(o -> o.getId().equals(id)))
                .isTrue();
        assertThat(todayListContains(id)).isTrue();
    }
}
