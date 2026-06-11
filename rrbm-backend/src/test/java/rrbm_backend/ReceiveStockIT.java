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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S5 / W-5 + W-12 + W-6 — Receive Stock Chain
 *
 * <p>Workflow: receive a supplier delivery → stock up + payable created.
 *
 * <p>Scenarios:
 * - Happy path: `POST /api/products/delivery` → 200; delivery_log + delivery_log_items + payables + inventory_movements created
 * - Duplicate DR number → 400; no rows written
 * - Bad DR format → 400; no rows written
 * - Empty items list → 400; no rows written
 * - Multiple warehouses → stock incremented per warehouse
 * - Rejected items recorded → delivery_log_items shows rejectedQty
 * - GET /api/reports/rejected-items includes the rejected items
 * - No auth token → 401; no rows written
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ReceiveStockIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DeliveryLogRepository deliveryLogRepository;
    @Autowired private DeliveryLogItemRepository deliveryLogItemRepository;
    @Autowired private PayableRepository payableRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S5-Secret!";
    private static final String SEC_KEY = "S5-admin-key";

    private User accountingUser;
    private String userJwt;
    private Product product1;
    private Product product2;
    private Supplier supplier1;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void seed() {
        // Seed user
        accountingUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s5-acct-" + RUN + "@test.rrbm.internal", "S5 Accounting", PASSWORD, SEC_KEY);
        userJwt = ITSupport.jwtFor(jwtUtil, accountingUser);

        // Seed products with known stock (product code max 6 chars)
        product1 = ITSupport.seedProduct(productRepository,
                "S5PA" + (RUN % 99), "S5 Product 1", new BigDecimal("200.00"), 100);
        product2 = ITSupport.seedProduct(productRepository,
                "S5PB" + (RUN % 99), "S5 Product 2", new BigDecimal("150.00"), 50);

        // Seed supplier
        supplier1 = ITSupport.seedSupplier(supplierRepository,
                "S5-SUPPLIER-" + RUN, "S5 Contact");
    }

    @AfterAll
    void clean() {
        // Delete in FK-safe order: activity_logs → inventory_movements → delivery_log_items → delivery_logs → payables
        if (accountingUser != null) {
            activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now()).stream()
                    .filter(a -> accountingUser.getId().equals(a.getUserId()))
                    .forEach(a -> activityLogRepository.deleteById(a.getId()));
        }

        // Delete delivery logs (cascades to delivery_log_items)
        OffsetDateTime yesterday = OffsetDateTime.now().minusDays(1);
        OffsetDateTime tomorrow = OffsetDateTime.now().plusDays(1);
        deliveryLogRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(dl -> {
                    // Delete inventory movements for this delivery
                    inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(dl.getReceiptNumber())
                            .forEach(im -> inventoryMovementRepository.delete(im));
                    // Delete payables for this delivery
                    payableRepository.findByDeliveryLogId(dl.getId())
                            .forEach(p -> payableRepository.delete(p));
                    // Delete delivery log (cascades to items)
                    deliveryLogRepository.delete(dl);
                });

        // Delete products
        if (product1 != null) productRepository.deleteById(product1.getId());
        if (product2 != null) productRepository.deleteById(product2.getId());

        // Delete supplier
        if (supplier1 != null) supplierRepository.deleteById(supplier1.getId());

        // Delete user
        if (accountingUser != null) userRepository.deleteById(accountingUser.getId());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t01_happyPath_deliveryReceived_stockIncremented() throws Exception {
        // Arrange: create delivery request for product1 to wh1
        long drNum = RUN + 1000;
        Map<String, Object> item1 = createDeliveryItem(
                product1.getId(), 50, 50, 0, "wh1", new BigDecimal("120.00"));

        Map<String, Object> payload = createDeliveryPayload(
                "DR-" + drNum,
                "S5-SUPPLIER-" + RUN,
                "Receiver A",
                "Verifier A",
                "Encoder A",
                "Normal delivery",
                List.of(item1));

        // Act: POST /api/products/delivery
        mockMvc.perform(post("/api/products/delivery")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // Assert: delivery_log created
        DeliveryLog log = deliveryLogRepository.findByReceiptNumber("DR-" + drNum).orElse(null);
        assertThat(log).isNotNull();
        assertThat(log.getSupplierName()).isEqualTo("S5-SUPPLIER-" + RUN);
        assertThat(log.getReceivedBy()).isEqualTo("Receiver A");
        assertThat(log.getVerifiedBy()).isEqualTo("Verifier A");
        assertThat(log.getTotalQuantity()).isEqualTo(50);

        // Assert: delivery_log_items created
        List<DeliveryLogItem> items = deliveryLogItemRepository.findByDeliveryLogId(log.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getProductId()).isEqualTo(product1.getId());
        assertThat(items.get(0).getReceivedQty()).isEqualTo(50);
        assertThat(items.get(0).getRejectedQty()).isEqualTo(0);
        assertThat(items.get(0).getWarehouse()).isEqualTo("wh1");

        // Assert: product stock incremented
        Product p1 = productRepository.findById(product1.getId()).orElse(null);
        assertThat(p1).isNotNull();
        assertThat(p1.getStockWh1()).isEqualTo(150); // 100 + 50

        // Assert: payable created
        Payable payable = payableRepository.findByDeliveryLogId(log.getId()).stream().findFirst().orElse(null);
        assertThat(payable).isNotNull();
        assertThat(payable.getStatus()).isEqualTo("PENDING");
        assertThat(payable.getReceiptNumber()).isEqualTo("DR-" + drNum);

        // Assert: inventory movement RESTOCK logged
        List<InventoryMovement> movements = inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc("DR-" + drNum);
        assertThat(movements).isNotEmpty();
        assertThat(movements.get(0).getMovementType()).isEqualTo("RESTOCK");
        assertThat(movements.get(0).getQuantity()).isEqualTo(50);
        assertThat(movements.get(0).getWarehouse()).isEqualTo("wh1");
    }

    @Test
    void t02_multipleWarehouses_stockIncrementedPerWarehouse() throws Exception {
        // Arrange: deliver to multiple warehouses
        long drNum = RUN + 2000;
        Map<String, Object> item1 = createDeliveryItem(
                product1.getId(), 20, 20, 0, "wh1", new BigDecimal("120.00"));
        Map<String, Object> item2 = createDeliveryItem(
                product2.getId(), 30, 30, 0, "wh2", new BigDecimal("90.00"));

        Map<String, Object> payload = createDeliveryPayload(
                "DR-" + drNum,
                "S5-SUPPLIER-" + RUN,
                "Receiver B",
                "Verifier B",
                "Encoder B",
                "Multi-warehouse delivery",
                List.of(item1, item2));

        // Act
        mockMvc.perform(post("/api/products/delivery")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // Assert: delivery_log created with 2 items
        DeliveryLog log = deliveryLogRepository.findByReceiptNumber("DR-" + drNum).orElse(null);
        assertThat(log).isNotNull();
        assertThat(log.getTotalItems()).isEqualTo(2);

        // Assert: product1 wh1 incremented
        Product p1 = productRepository.findById(product1.getId()).orElse(null);
        assertThat(p1.getStockWh1()).isEqualTo(170); // 100 + 20 + 50 from t01

        // Assert: product2 wh2 incremented
        Product p2 = productRepository.findById(product2.getId()).orElse(null);
        assertThat(p2.getStockWh2()).isEqualTo(80); // 50 + 30
    }

    @Test
    void t03_rejectedItems_recorded() throws Exception {
        // Arrange: delivery with some rejected items
        long drNum = RUN + 3000;
        Map<String, Object> item = createDeliveryItem(
                product1.getId(), 50, 45, 5, "wh1", new BigDecimal("120.00"));

        Map<String, Object> payload = createDeliveryPayload(
                "DR-" + drNum,
                "S5-SUPPLIER-" + RUN,
                "Receiver C",
                "Verifier C",
                "Encoder C",
                "Delivery with rejections",
                List.of(item));

        // Act
        mockMvc.perform(post("/api/products/delivery")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // Assert: delivery_log_item shows rejectedQty
        DeliveryLog log = deliveryLogRepository.findByReceiptNumber("DR-" + drNum).orElse(null);
        assertThat(log).isNotNull();
        List<DeliveryLogItem> items = deliveryLogItemRepository.findByDeliveryLogId(log.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getRejectedQty()).isEqualTo(5);
        assertThat(items.get(0).getReceivedQty()).isEqualTo(45);

        // Assert: only received qty added to stock (45, not 50)
        Product p = productRepository.findById(product1.getId()).orElse(null);
        assertThat(p.getStockWh1()).isEqualTo(215); // 170 + 45
    }

    @Test
    void t04_duplicateDrNumber_rejected() throws Exception {
        // Arrange: create first delivery
        long drNum = RUN + 4000;
        Map<String, Object> item = createDeliveryItem(
                product1.getId(), 10, 10, 0, "wh1", new BigDecimal("120.00"));

        Map<String, Object> payload = createDeliveryPayload(
                "DR-" + drNum,
                "S5-SUPPLIER-" + RUN,
                "Receiver D1",
                "Verifier D1",
                "Encoder D1",
                "First delivery",
                List.of(item));

        // Act: first delivery succeeds
        mockMvc.perform(post("/api/products/delivery")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // Verify first delivery created
        DeliveryLog firstLog = deliveryLogRepository.findByReceiptNumber("DR-" + drNum).orElse(null);
        assertThat(firstLog).isNotNull();
        long firstPayableCount = payableRepository.count();

        // Act: second delivery with same DR number
        mockMvc.perform(post("/api/products/delivery")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());

        // Assert: no new payable written
        long secondPayableCount = payableRepository.count();
        assertThat(secondPayableCount).isEqualTo(firstPayableCount);
    }

    @Test
    void t05_badDrFormat_rejected() throws Exception {
        // Arrange: invalid DR format (too long, special chars)
        Map<String, Object> item = createDeliveryItem(
                product1.getId(), 10, 10, 0, "wh1", new BigDecimal("120.00"));

        Map<String, Object> payload = createDeliveryPayload(
                "INVALID-DR-NUMBER-THAT-IS-WAY-TOO-LONG",
                "S5-SUPPLIER-" + RUN,
                "Receiver",
                "Verifier",
                "Encoder",
                "Bad format",
                List.of(item));

        // Act
        mockMvc.perform(post("/api/products/delivery")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());

        // Assert: no delivery_log created with this specific DR number
        long count = deliveryLogRepository.findByReceiptNumber("INVALID-DR-NUMBER-THAT-IS-WAY-TOO-LONG").stream().count();
        assertThat(count).isEqualTo(0);
    }

    @Test
    void t06_emptyItems_rejected() throws Exception {
        // Arrange: empty items list
        Map<String, Object> payload = createDeliveryPayload(
                "DR-" + (RUN + 6000),
                "S5-SUPPLIER-" + RUN,
                "Receiver",
                "Verifier",
                "Encoder",
                "Empty items",
                List.of());

        // Act
        mockMvc.perform(post("/api/products/delivery")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());

        // Assert: no delivery_log created
        long count = deliveryLogRepository.findByReceiptNumber("DR-" + (RUN + 6000)).stream().count();
        assertThat(count).isEqualTo(0);
    }

    @Test
    void t07_noAuthToken_rejected() throws Exception {
        // Arrange: delivery request without token
        long drNum = RUN + 7000;
        Map<String, Object> item = createDeliveryItem(
                product1.getId(), 10, 10, 0, "wh1", new BigDecimal("120.00"));

        Map<String, Object> payload = createDeliveryPayload(
                "DR-" + drNum,
                "S5-SUPPLIER-" + RUN,
                "Receiver",
                "Verifier",
                "Encoder",
                "No token",
                List.of(item));

        // Act: no Authorization header
        mockMvc.perform(post("/api/products/delivery")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());

        // Assert: no delivery_log created
        long count = deliveryLogRepository.findByReceiptNumber("DR-" + drNum).stream().count();
        assertThat(count).isEqualTo(0);
    }

    @Test
    void t08_rejectedItemsEndpoint_returns200() throws Exception {
        // We created rejected items in t03, so GET /api/reports/rejected-items should include them
        // This test verifies the endpoint is callable (not testing full response shape — UI unverified)

        mockMvc.perform(get("/api/reports/rejected-items")
                .header("Authorization", "Bearer " + userJwt)
                .param("start", LocalDate.now().toString())
                .param("end", LocalDate.now().toString()))
                .andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    private Map<String, Object> createDeliveryItem(Long productId, int quantity, int received,
                                                   int rejected, String warehouse, BigDecimal unitCost) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", productId);
        item.put("quantity", quantity);
        item.put("received", received);
        item.put("rejected", rejected);
        item.put("warehouse", warehouse);
        item.put("unitCost", unitCost);
        return item;
    }

    private Map<String, Object> createDeliveryPayload(String drNumber, String supplierName,
                                                      String receiverName, String verifierName,
                                                      String encodedByName, String notes,
                                                      List<Map<String, Object>> items) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("receiptNumber", drNumber);
        payload.put("supplierName", supplierName);
        payload.put("receiverName", receiverName);
        payload.put("verifierName", verifierName);
        payload.put("encodedByName", encodedByName);
        payload.put("notes", notes);
        payload.put("items", items);
        return payload;
    }
}
