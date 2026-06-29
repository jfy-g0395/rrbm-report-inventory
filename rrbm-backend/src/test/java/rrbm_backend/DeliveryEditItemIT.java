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
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Delivery edit — per-line delta safeguard (DR 208390 incident).
 *
 * <p>Editing one line of a delivery must only move the lines that actually changed. Lines left
 * untouched — especially ones that have been sold down below their delivered quantity since the
 * delivery was received — must NOT be reversed/re-applied (the old path floored such a line's
 * stock at zero and silently inflated it).
 *
 * <p>Also verifies the change log is a concise per-line diff, not a full before/after dump.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class DeliveryEditItemIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DeliveryLogRepository deliveryLogRepository;
    @Autowired private PayableRepository payableRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "EDI-Secret!";
    private static final String SEC_KEY = "EDI-admin-key";

    private User user;
    private String jwt;
    private Product a, b, c, d;
    private Supplier supplier;
    private final List<Long> productIds = new ArrayList<>();
    private final ObjectMapper om = new ObjectMapper();

    @BeforeAll
    void seed() {
        user = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "edi-" + RUN + "@test.rrbm.internal", "EDI Accounting", PASSWORD, SEC_KEY);
        jwt = ITSupport.jwtFor(jwtUtil, user);
        supplier = ITSupport.seedSupplier(supplierRepository, "EDI-SUP-" + RUN, "EDI Contact");
        a = ITSupport.seedProduct(productRepository, "EDA" + (RUN % 99), "EDI Prod A", new BigDecimal("10.00"), 0);
        b = ITSupport.seedProduct(productRepository, "EDB" + (RUN % 99), "EDI Prod B", new BigDecimal("20.00"), 0);
        c = ITSupport.seedProduct(productRepository, "EDC" + (RUN % 99), "EDI Prod C", new BigDecimal("30.00"), 0);
        d = ITSupport.seedProduct(productRepository, "EDD" + (RUN % 99), "EDI Prod D", new BigDecimal("40.00"), 0);
        productIds.add(a.getId()); productIds.add(b.getId()); productIds.add(c.getId()); productIds.add(d.getId());
    }

    @AfterAll
    void clean() {
        if (user != null) {
            activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now()).stream()
                    .filter(x -> user.getId().equals(x.getUserId()))
                    .forEach(x -> activityLogRepository.deleteById(x.getId()));
        }
        OffsetDateTime from = OffsetDateTime.now().minusDays(1), to = OffsetDateTime.now().plusDays(1);
        deliveryLogRepository.findByCreatedAtBetween(from, to).forEach(dl -> {
            inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(dl.getReceiptNumber())
                    .forEach(inventoryMovementRepository::delete);
            payableRepository.findByDeliveryLogId(dl.getId()).forEach(payableRepository::delete);
            deliveryLogRepository.delete(dl);
        });
        productIds.forEach(id -> { try { productRepository.deleteById(id); } catch (Exception ignored) {} });
        if (supplier != null) supplierRepository.deleteById(supplier.getId());
        if (user != null) userRepository.deleteById(user.getId());
    }

    /** The incident: removing one line must not touch a sibling line that was sold down below its delivered qty. */
    @Test
    void t01_removeLine_doesNotCorruptSoldDownSibling() throws Exception {
        String dr = "EDI-A-" + RUN;
        createDelivery(dr, List.of(
                createItem(a.getId(), 100, "wh1", new BigDecimal("10.00")),
                createItem(b.getId(), 50,  "wh1", new BigDecimal("20.00"))));
        // After receiving: A=100, B=50.
        assertThat(productRepository.findById(a.getId()).get().getStockWh1()).isEqualTo(100);
        assertThat(productRepository.findById(b.getId()).get().getStockWh1()).isEqualTo(50);

        // Simulate 2 days of sales: A sold down to 10 (below its delivered 100).
        Product pa = productRepository.findById(a.getId()).get();
        pa.setStockWh1(10);
        productRepository.save(pa);

        // Edit: remove line B, keep A unchanged.
        DeliveryLog log = deliveryLogRepository.findByReceiptNumber(dr).orElseThrow();
        editItems(log.getId(), "B was the wrong input", List.of(
                editItem(a.getId(), 100, "wh1", new BigDecimal("10.00"))));

        // A is unchanged (NOT reset to 100 by a reverse/re-apply); B is removed (50 → 0).
        assertThat(productRepository.findById(a.getId()).get().getStockWh1()).isEqualTo(10);
        assertThat(productRepository.findById(b.getId()).get().getStockWh1()).isEqualTo(0);

        // No new movement was logged for the untouched A; B got a single CORRECTION_OUT of -50.
        long aMoves = inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(dr).stream()
                .filter(m -> a.getId().equals(m.getProductId()) && "CORRECTION_OUT".equals(m.getMovementType())).count();
        long bRemoval = inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(dr).stream()
                .filter(m -> b.getId().equals(m.getProductId()) && m.getQuantity() == -50).count();
        assertThat(aMoves).isZero();
        assertThat(bRemoval).isEqualTo(1);

        // Change log is a concise diff: "Removed EDI Prod B ..." and NOT the old full dump.
        String cl = deliveryLogRepository.findById(log.getId()).get().getChangeLog();
        assertThat(cl).contains("Removed").contains("EDI Prod B");
        assertThat(cl).doesNotContain("→ [");
    }

    /** A quantity change applies only the delta to that line; the untouched sibling stays put. */
    @Test
    void t02_qtyChange_appliesDeltaOnly() throws Exception {
        String dr = "EDI-C-" + RUN;
        createDelivery(dr, List.of(
                createItem(c.getId(), 100, "wh1", new BigDecimal("30.00")),
                createItem(d.getId(), 40,  "wh1", new BigDecimal("40.00"))));
        // C sold down to 30; D untouched at 40.
        Product pc = productRepository.findById(c.getId()).get();
        pc.setStockWh1(30);
        productRepository.save(pc);

        DeliveryLog log = deliveryLogRepository.findByReceiptNumber(dr).orElseThrow();
        // Bump C 100 → 120 (delta +20); keep D unchanged.
        editItems(log.getId(), "C quantity correction", List.of(
                editItem(c.getId(), 120, "wh1", new BigDecimal("30.00")),
                editItem(d.getId(), 40,  "wh1", new BigDecimal("40.00"))));

        // C: 30 + 20 = 50 (delta only); D: unchanged at 40.
        assertThat(productRepository.findById(c.getId()).get().getStockWh1()).isEqualTo(50);
        assertThat(productRepository.findById(d.getId()).get().getStockWh1()).isEqualTo(40);

        String cl = deliveryLogRepository.findById(log.getId()).get().getChangeLog();
        assertThat(cl).contains("100 → 120");
        assertThat(cl).doesNotContain("EDI Prod D");   // untouched line not mentioned
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void createDelivery(String dr, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("receiptNumber", dr);
        payload.put("supplierName", "EDI-SUP-" + RUN);
        payload.put("receiverName", "Receiver");
        payload.put("verifierName", "Verifier");
        payload.put("encodedByName", "Encoder");
        payload.put("notes", "edit-delta test");
        payload.put("items", items);
        mockMvc.perform(post("/api/products/delivery")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    private void editItems(Long id, String reason, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("securityKey", SEC_KEY);
        body.put("reason", reason);
        body.put("items", items);
        mockMvc.perform(patch("/api/delivery-reports/" + id)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    /** Create-endpoint item shape (uses "received"). */
    private Map<String, Object> createItem(Long productId, int qty, String wh, BigDecimal cost) {
        Map<String, Object> m = new HashMap<>();
        m.put("productId", productId);
        m.put("quantity", qty);
        m.put("received", qty);
        m.put("rejected", 0);
        m.put("warehouse", wh);
        m.put("unitCost", cost);
        return m;
    }

    /** Edit-endpoint item shape (uses "receivedQty"). */
    private Map<String, Object> editItem(Long productId, int qty, String wh, BigDecimal cost) {
        Map<String, Object> m = new HashMap<>();
        m.put("productId", productId);
        m.put("quantity", qty);
        m.put("receivedQty", qty);
        m.put("rejectedQty", 0);
        m.put("warehouse", wh);
        m.put("unitCost", cost);
        return m;
    }
}
