package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase B — unified Return / Replace flow (POST /api/orders/{id}/return-replace).
 *
 * Scenario: a delivered order of 100 × product1 @ ₱100 (= ₱10,000). Customer returns 80
 * (sellable), keeps 20, and gets 80 × product2 @ ₱90 (= ₱7,200) as a replacement; ₱800 is
 * recorded as owed (10,000 paid − 2,000 kept − 7,200 replacement). Asserts the void, stock
 * restore, replacement order, and return_events records — and that cash is NOT moved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnReplaceIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CommissionPeriodRepository commissionPeriodRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ReturnEventRepository returnEventRepository;
    @Autowired private JwtUtil jwtUtil;

    private final ObjectMapper om = new ObjectMapper();
    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String SEC_KEY = "RR-key-" + RUN;

    private String jwt;
    private Product product1;   // original
    private Product product2;   // replacement

    @BeforeAll
    void seed() {
        User u = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "rr-" + RUN + "@test.rrbm.internal", "RR User", "RR-Secret!", SEC_KEY);
        jwt = ITSupport.jwtFor(jwtUtil, u);
        product1 = ITSupport.seedProduct(productRepository, "RRA" + (RUN % 99), "RR Product A", new BigDecimal("100.00"), 500);
        product2 = ITSupport.seedProduct(productRepository, "RRB" + (RUN % 99), "RR Product B", new BigDecimal("90.00"), 500);
        ITSupport.seedOpenPeriod(commissionPeriodRepository, LocalDate.now(), LocalDate.now().plusDays(30));
    }

    @AfterAll
    void noop() { /* isolated throwaway DB — cleanup not required */ }

    private String createDeliveredOrder(int qty) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product1.getId());
        item.put("productName", product1.getName());
        item.put("quantity", qty);
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("warehouse", "wh1");
        Map<String, Object> req = new HashMap<>();
        req.put("customerName", "RR Cust " + RUN);
        req.put("source", "WALK_IN");
        req.put("paymentMode", "CASH");
        req.put("items", List.of(item));

        String resp = mockMvc.perform(post("/api/orders")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = om.readTree(resp).get("id").asText();

        // Mark delivered so the return path treats units as physically shipped.
        Order o = orderRepository.findById(orderId).orElseThrow();
        o.setStatus("DELIVERED");
        orderRepository.save(o);
        return orderId;
    }

    @Test
    void partialReturn_withReplacement_andRefundOwed() throws Exception {
        String orderId = createDeliveredOrder(100);
        Long itemId = orderRepository.findByIdWithItems(orderId).orElseThrow().getItems().get(0).getId();

        Map<String, Object> retLine = new HashMap<>();
        retLine.put("orderItemId", itemId);
        retLine.put("returnedQty", 80);
        retLine.put("sellableQty", 80);
        retLine.put("rejectedQty", 0);
        retLine.put("restockWarehouse", "wh1");

        Map<String, Object> replLine = new HashMap<>();
        replLine.put("productId", product2.getId());
        replLine.put("productName", product2.getName());
        replLine.put("quantity", 80);
        replLine.put("unitPrice", new BigDecimal("90.00"));
        replLine.put("warehouse", "wh1");

        Map<String, Object> body = new HashMap<>();
        body.put("securityKey", SEC_KEY);
        body.put("reason", "Wrong size — smaller replacement");
        body.put("returnItems", List.of(retLine));
        body.put("replacementItems", List.of(replLine));
        body.put("refundOwed", new BigDecimal("800.00"));

        mockMvc.perform(post("/api/orders/" + orderId + "/return-replace")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk());

        // ── Original order: 80 voided, ₱8,000 voided, still active (20 remain) ──
        Order original = orderRepository.findByIdWithItems(orderId).orElseThrow();
        assertThat(original.getItems().get(0).getVoidedQuantity()).isEqualTo(80);
        assertThat(original.getVoidedAmount()).isEqualByComparingTo("8000.00");
        assertThat(original.getStatus()).isEqualTo("DELIVERED"); // not fully returned

        // VOID ledger entry for the returned revenue
        boolean hasVoid = transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .anyMatch(t -> "VOID".equals(t.getTransactionType())
                        && t.getAmount().compareTo(new BigDecimal("-8000.00000")) == 0);
        assertThat(hasVoid).as("VOID of ₱8,000 posted for the return").isTrue();

        // product1 stock: 500 − 100 (order) + 80 (return) = 480
        assertThat(productRepository.findById(product1.getId()).orElseThrow().getStockWh1()).isEqualTo(480);

        // ── Replacement order: linked, product2 ×80, SALE posted, stock deducted ──
        List<Order> replacements = orderRepository.findAll().stream()
                .filter(o -> orderId.equals(o.getOriginalOrderId())).toList();
        assertThat(replacements).hasSize(1);
        Order repl = orderRepository.findByIdWithItems(replacements.get(0).getId()).orElseThrow();
        assertThat(repl.getItems()).hasSize(1);
        assertThat(repl.getItems().get(0).getQuantity()).isEqualTo(80);
        assertThat(repl.getTotal()).isEqualByComparingTo("7200.00");
        boolean replHasSale = transactionRepository.findByOrderIdOrderByCreatedAtDesc(repl.getId()).stream()
                .anyMatch(t -> "SALE".equals(t.getTransactionType()));
        assertThat(replHasSale).as("replacement SALE posted").isTrue();
        // product2 stock: 500 − 80 = 420
        assertThat(productRepository.findById(product2.getId()).orElseThrow().getStockWh1()).isEqualTo(420);

        // ── return_events: OWED refund of ₱800, one item row, linked to replacement ──
        List<ReturnEvent> events = returnEventRepository.findWithItemsByOrderId(orderId);
        assertThat(events).hasSize(1);
        ReturnEvent ev = events.get(0);
        assertThat(ev.getEventType()).isEqualTo("RETURN");
        assertThat(ev.getRefundStatus()).isEqualTo("OWED");
        assertThat(ev.getRefundOwed()).isEqualByComparingTo("800.00");
        assertThat(ev.getReplacementOrderId()).isEqualTo(repl.getId());
        assertThat(ev.getItems()).hasSize(1);
        assertThat(ev.getItems().get(0).getReturnedQty()).isEqualTo(80);
        assertThat(ev.getItems().get(0).getSellableQty()).isEqualTo(80);

        // ── To-Refund list shows this event (₱800 owed) ──────────────────────────
        String listJson = mockMvc.perform(get("/api/orders/collections/refunds")
                .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listJson).contains("\"orderId\":\"" + orderId + "\"");

        // ── Pay the refund (Refund button) ───────────────────────────────────────
        mockMvc.perform(post("/api/orders/collections/refunds/" + ev.getId() + "/pay")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("securityKey", SEC_KEY))))
                .andExpect(status().isOk());

        // ── Event now REFUNDED for ₱800, and no longer owed ──────────────────────
        ReturnEvent afterRefund = returnEventRepository.findById(ev.getId()).orElseThrow();
        assertThat(afterRefund.getRefundStatus()).isEqualTo("REFUNDED");
        assertThat(afterRefund.getRefundedAmount()).isEqualByComparingTo("800.00");
        assertThat(afterRefund.getRefundedAt()).isNotNull();
        assertThat(returnEventRepository.findByRefundStatusOrderByCreatedAtDesc("OWED")).isEmpty();

        // ── Paying again is rejected (already refunded) ──────────────────────────
        mockMvc.perform(post("/api/orders/collections/refunds/" + ev.getId() + "/pay")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("securityKey", SEC_KEY))))
                .andExpect(status().isBadRequest());
    }
}
