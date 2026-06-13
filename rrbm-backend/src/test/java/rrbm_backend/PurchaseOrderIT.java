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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * S6.2 / W-7 — Purchase Order CRUD, Status Transitions & Receive Flow
 *
 * <p>Workflow: create PO with auto-generated PO number, manage items, update status,
 * receive goods against line items with inventory update.
 *
 * <p>Scenarios:
 * - Create PO: auto-generated PO-YYYYMMDD-NNNNN, counter incremented
 * - Items: with explicit unit price or supplier mapping pricing
 * - Supplier snapshot: supplierItemCode + productId saved on PoItem
 * - Explicit unitPrice overrides mapping cost
 * - Status transitions: INCOMPLETE → PARTIALLY_RECEIVED → COMPLETE via receive
 * - Receive endpoint: stock update + RESTOCK inventory movement logged
 * - Validation: zero qty rejected, invalid status rejected
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class PurchaseOrderIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private PoYearCounterRepository poYearCounterRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private SupplierProductMappingRepository mappingRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private PayableRepository payableRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S6PO-Secret!";

    private User accountingUser;
    private String userJwt;
    private Product product1;
    private Product product2;
    private Supplier supplier1;
    private ObjectMapper objectMapper = new ObjectMapper();

    // Set by t09, consumed by t11/t12/t13 — tracks the PO used for receive tests
    private Long receivePOId;
    private Long receiveItemId;

    @BeforeAll
    void seed() {
        accountingUser = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s6po-acct-" + RUN + "@test.rrbm.internal", "S6PO Accounting", PASSWORD, null);
        userJwt = ITSupport.jwtFor(jwtUtil, accountingUser);

        product1 = ITSupport.seedProduct(productRepository,
                "S6OA" + (RUN % 99), "S6PO Product 1", new BigDecimal("500.00"), 100);
        product2 = ITSupport.seedProduct(productRepository,
                "S6OB" + (RUN % 99), "S6PO Product 2", new BigDecimal("300.00"), 50);

        supplier1 = ITSupport.seedSupplier(supplierRepository,
                "S6PO-SUPPLIER-" + RUN, "S6PO Contact");
    }

    @AfterAll
    void clean() {
        // Activity logs
        if (accountingUser != null) {
            activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now()).stream()
                    .filter(a -> accountingUser.getId().equals(a.getUserId()))
                    .forEach(a -> activityLogRepository.deleteById(a.getId()));
        }

        // Payables created by PO receive (delivery_log_id is null — safe to identify by receipt prefix)
        payableRepository.findByReceiptNumber("DR-TEST-" + RUN)
                .forEach(p -> payableRepository.deleteById(p.getId()));
        payableRepository.findByReceiptNumber("DR-TEST-" + RUN + "-2")
                .forEach(p -> payableRepository.deleteById(p.getId()));

        // Inventory movements logged against test products (must precede product delete)
        if (product1 != null) {
            inventoryMovementRepository.findByProductIdOrderByCreatedAtDesc(product1.getId())
                    .forEach(m -> inventoryMovementRepository.deleteById(m.getId()));
        }
        if (product2 != null) {
            inventoryMovementRepository.findByProductIdOrderByCreatedAtDesc(product2.getId())
                    .forEach(m -> inventoryMovementRepository.deleteById(m.getId()));
        }

        // Purchase orders (cascade deletes PoItems)
        java.time.LocalDateTime yesterday = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime tomorrow  = java.time.LocalDateTime.now().plusDays(1);
        purchaseOrderRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(po -> purchaseOrderRepository.delete(po));

        // Mappings and supplier
        if (supplier1 != null) {
            mappingRepository.findBySupplierId(supplier1.getId())
                    .forEach(m -> mappingRepository.delete(m));
            supplierRepository.deleteById(supplier1.getId());
        }

        // Products
        if (product1 != null) productRepository.deleteById(product1.getId());
        if (product2 != null) productRepository.deleteById(product2.getId());

        // User
        if (accountingUser != null) userRepository.deleteById(accountingUser.getId());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PO Creation Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t01_createPurchaseOrder_success() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item1 = new HashMap<>();
        item1.put("itemDescription", "Item A");
        item1.put("itemCode", "A001");
        item1.put("quantityOrdered", 10);
        item1.put("unitPrice", new BigDecimal("250.00"));
        items.add(item1);

        Map<String, Object> item2 = new HashMap<>();
        item2.put("itemDescription", "Item B");
        item2.put("itemCode", "B001");
        item2.put("quantityOrdered", 5);
        item2.put("unitPrice", new BigDecimal("150.00"));
        items.add(item2);

        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "VENDOR-" + RUN);
        payload.put("vendorContact", "vendor@example.com");
        payload.put("vendorAddress", "123 Vendor St");
        payload.put("shipToName", "Warehouse");
        payload.put("items", items);

        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        List<PurchaseOrder> orders = purchaseOrderRepository.findByVendorName("VENDOR-" + RUN);
        assertThat(orders).hasSize(1);
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(orders.get(0).getId()).orElse(null);
        assertThat(po).isNotNull();
        assertThat(po.getPoNumber()).matches("^PO-\\d{6}-\\d+$");
        assertThat(po.getStatus()).isEqualTo("INCOMPLETE");
        assertThat(po.getItems()).hasSize(2);
        assertThat(po.getTotalAmount()).isEqualTo(new BigDecimal("3250.00"));
    }

    @Test
    void t02_createPurchaseOrder_withSupplierMapping() throws Exception {
        // Create supplier mapping
        Map<String, Object> mappingPayload = new HashMap<>();
        mappingPayload.put("productId", product1.getId());
        mappingPayload.put("supplierItemCode", "VENDOR-P1");
        mappingPayload.put("unitCost", new BigDecimal("300.00"));

        mockMvc.perform(post("/api/suppliers/" + supplier1.getId() + "/mappings")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mappingPayload)))
                .andExpect(status().isOk());

        // Create PO with supplier linkage — no explicit unitPrice on item
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemDescription", "Mapped Item");
        item.put("productId", product1.getId());
        item.put("quantityOrdered", 5);
        items.add(item);

        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "MAPPED-VENDOR-" + RUN);
        payload.put("supplierId", supplier1.getId());
        payload.put("items", items);

        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        List<PurchaseOrder> orders = purchaseOrderRepository.findByVendorName("MAPPED-VENDOR-" + RUN);
        assertThat(orders).hasSize(1);
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(orders.get(0).getId()).orElse(null);
        assertThat(po).isNotNull();
        assertThat(po.getSupplierId()).isEqualTo(supplier1.getId());
        assertThat(po.getItems()).hasSize(1);
        // Unit price should be filled from mapping cost
        assertThat(po.getItems().get(0).getUnitPrice()).isEqualTo(new BigDecimal("300.00"));
        assertThat(po.getTotalAmount()).isEqualTo(new BigDecimal("1500.00"));
    }

    @Test
    void t03_createPurchaseOrder_missingVendorName_rejected() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemDescription", "Item");
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("quantityOrdered", 1);
        items.add(item);

        Map<String, Object> payload = new HashMap<>();
        payload.put("items", items);

        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t04_createPurchaseOrder_noItems_rejected() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "NO-ITEMS-VENDOR-" + RUN);
        payload.put("items", new ArrayList<>());

        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t05_poNumberFormat_incrementsCounter() throws Exception {
        for (int i = 0; i < 2; i++) {
            List<Map<String, Object>> items = new ArrayList<>();
            Map<String, Object> item = new HashMap<>();
            item.put("itemDescription", "Item " + i);
            item.put("unitPrice", new BigDecimal("100.00"));
            item.put("quantityOrdered", 1);
            items.add(item);

            Map<String, Object> payload = new HashMap<>();
            payload.put("vendorName", "COUNTER-VENDOR-" + RUN + "-" + i);
            payload.put("items", items);

            mockMvc.perform(post("/api/purchase-orders")
                    .header("Authorization", "Bearer " + userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isOk());
        }

        List<PurchaseOrder> orders1 = purchaseOrderRepository.findByVendorName("COUNTER-VENDOR-" + RUN + "-0");
        List<PurchaseOrder> orders2 = purchaseOrderRepository.findByVendorName("COUNTER-VENDOR-" + RUN + "-1");
        assertThat(orders1).hasSize(1);
        assertThat(orders2).hasSize(1);

        String po1Num = orders1.get(0).getPoNumber();
        String po2Num = orders2.get(0).getPoNumber();
        assertThat(po1Num).matches("^PO-\\d{6}-\\d+$");
        assertThat(po2Num).matches("^PO-\\d{6}-\\d+$");
        assertThat(po1Num.substring(3, 9)).isEqualTo(po2Num.substring(3, 9)); // same date segment
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PO Status Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t06_updateStatus_incompleteToComplete() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemDescription", "Status Test Item");
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("quantityOrdered", 1);
        items.add(item);

        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "STATUS-VENDOR-" + RUN);
        payload.put("items", items);

        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        List<PurchaseOrder> orders = purchaseOrderRepository.findByVendorName("STATUS-VENDOR-" + RUN);
        assertThat(orders).hasSize(1);
        PurchaseOrder po = orders.get(0);
        assertThat(po.getStatus()).isEqualTo("INCOMPLETE");

        Map<String, String> statusPayload = new HashMap<>();
        statusPayload.put("status", "COMPLETE");

        mockMvc.perform(patch("/api/purchase-orders/" + po.getId() + "/status")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(statusPayload)))
                .andExpect(status().isOk());

        PurchaseOrder updated = purchaseOrderRepository.findById(po.getId()).orElse(null);
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void t07_updateStatus_invalidStatus_rejected() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemDescription", "Invalid Status Item");
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("quantityOrdered", 1);
        items.add(item);

        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "INVALID-STATUS-VENDOR-" + RUN);
        payload.put("items", items);

        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        List<PurchaseOrder> orders = purchaseOrderRepository.findByVendorName("INVALID-STATUS-VENDOR-" + RUN);
        assertThat(orders).hasSize(1);
        PurchaseOrder po = orders.get(0);

        Map<String, String> statusPayload = new HashMap<>();
        statusPayload.put("status", "INVALID");

        mockMvc.perform(patch("/api/purchase-orders/" + po.getId() + "/status")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(statusPayload)))
                .andExpect(status().isBadRequest());

        PurchaseOrder unchanged = purchaseOrderRepository.findById(po.getId()).orElse(null);
        assertThat(unchanged).isNotNull();
        assertThat(unchanged.getStatus()).isEqualTo("INCOMPLETE");
    }

    @Test
    void t08_noAuthToken_rejected() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemDescription", "No Token Item");
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("quantityOrdered", 1);
        items.add(item);

        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "NO-TOKEN-PO");
        payload.put("items", items);

        mockMvc.perform(post("/api/purchase-orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Supplier Snapshot & productId Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t09_createPO_supplierMapping_snapshotsProductIdAndItemCode() throws Exception {
        // t02 created the mapping for supplier1+product1 with supplierItemCode="VENDOR-P1"
        SupplierProductMapping mapping = mappingRepository
                .findBySupplierIdAndProductId(supplier1.getId(), product1.getId()).orElse(null);
        if (mapping == null) return; // t02 failed — skip gracefully

        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemDescription", "Snapshot Test Item");
        item.put("productId", product1.getId());
        item.put("quantityOrdered", 10);
        // No unitPrice — should come from mapping
        items.add(item);

        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "SNAPSHOT-VENDOR-" + RUN);
        payload.put("supplierId", supplier1.getId());
        payload.put("items", items);

        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].supplierItemCode").value("VENDOR-P1"))
                .andExpect(jsonPath("$.items[0].productId").value(product1.getId().intValue()))
                .andExpect(jsonPath("$.items[0].unitPrice").value(300.00));

        // Store IDs for the receive tests (t11–t13)
        List<PurchaseOrder> orders = purchaseOrderRepository.findByVendorName("SNAPSHOT-VENDOR-" + RUN);
        assertThat(orders).hasSize(1);
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(orders.get(0).getId()).orElse(null);
        assertThat(po).isNotNull();
        assertThat(po.getItems().get(0).getProductId()).isEqualTo(product1.getId());
        assertThat(po.getItems().get(0).getSupplierItemCode()).isEqualTo("VENDOR-P1");
        receivePOId   = po.getId();
        receiveItemId = po.getItems().get(0).getId();
    }

    @Test
    void t10_createPO_explicitUnitPrice_overridesMappingCost() throws Exception {
        // Mapping cost is 300.00; explicit price of 99.00 must win
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemDescription", "Override Price Item");
        item.put("productId", product1.getId());
        item.put("quantityOrdered", 2);
        item.put("unitPrice", new BigDecimal("99.00"));
        items.add(item);

        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "OVERRIDE-VENDOR-" + RUN);
        payload.put("supplierId", supplier1.getId());
        payload.put("items", items);

        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(99.00))
                .andExpect(jsonPath("$.totalAmount").value(198.00));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Receive / Fulfillment Tests  (depend on t09 having run)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t11_receiveItem_partial_updatesStockAndStatus() throws Exception {
        if (receivePOId == null || receiveItemId == null) return; // t09 failed — skip

        int stockBefore = productRepository.findById(product1.getId())
                .map(Product::getStockWh1).orElse(0);

        Map<String, Object> payload = new HashMap<>();
        payload.put("receivedQty", 3);
        payload.put("drNumber", "DR-TEST-" + RUN);
        payload.put("warehouse", "wh1");

        mockMvc.perform(patch("/api/purchase-orders/" + receivePOId + "/items/" + receiveItemId + "/receive")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.items[0].fulfilledQty").value(3))
                .andExpect(jsonPath("$.items[0].isFulfilled").value(false))
                .andExpect(jsonPath("$.items[0].drNumber").value("DR-TEST-" + RUN));

        // WH1 stock must have increased by 3
        int stockAfter = productRepository.findById(product1.getId())
                .map(Product::getStockWh1).orElse(0);
        assertThat(stockAfter).isEqualTo(stockBefore + 3);

        // A RESTOCK inventory movement must be logged
        List<InventoryMovement> movements = inventoryMovementRepository
                .findByProductIdOrderByCreatedAtDesc(product1.getId());
        assertThat(movements).isNotEmpty();
        InventoryMovement latest = movements.get(0);
        assertThat(latest.getMovementType()).isEqualTo("RESTOCK");
        assertThat(latest.getQuantity()).isEqualTo(3);
        assertThat(latest.getWarehouse()).isEqualTo("wh1");

        // A PENDING payable must have been created with no delivery_log link
        List<Payable> payables = payableRepository.findByReceiptNumber("DR-TEST-" + RUN);
        assertThat(payables).hasSize(1);
        Payable payable = payables.get(0);
        assertThat(payable.getDeliveryLogId()).isNull();
        assertThat(payable.getStatus()).isEqualTo("PENDING");
        assertThat(payable.getTotalAmount()).isEqualByComparingTo(new BigDecimal("900.00")); // 300.00 × 3
    }

    @Test
    void t12_receiveItem_remaining_statusBecomesComplete() throws Exception {
        if (receivePOId == null || receiveItemId == null) return; // t09/t11 failed — skip

        int stockBefore = productRepository.findById(product1.getId())
                .map(Product::getStockWh1).orElse(0);

        // Ordered 10, received 3 in t11 — receive the remaining 7
        Map<String, Object> payload = new HashMap<>();
        payload.put("receivedQty", 7);
        payload.put("drNumber", "DR-TEST-" + RUN + "-2");
        payload.put("warehouse", "wh1");

        mockMvc.perform(patch("/api/purchase-orders/" + receivePOId + "/items/" + receiveItemId + "/receive")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.items[0].fulfilledQty").value(10))
                .andExpect(jsonPath("$.items[0].isFulfilled").value(true));

        // WH1 stock must have increased by 7 more
        int stockAfter = productRepository.findById(product1.getId())
                .map(Product::getStockWh1).orElse(0);
        assertThat(stockAfter).isEqualTo(stockBefore + 7);

        // A second PENDING payable must have been created for the final receipt
        List<Payable> payables = payableRepository.findByReceiptNumber("DR-TEST-" + RUN + "-2");
        assertThat(payables).hasSize(1);
        assertThat(payables.get(0).getDeliveryLogId()).isNull();
        assertThat(payables.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(payables.get(0).getTotalAmount()).isEqualByComparingTo(new BigDecimal("2100.00")); // 300.00 × 7
    }

    @Test
    void t13_receiveItem_zeroQty_rejected() throws Exception {
        if (receivePOId == null || receiveItemId == null) return; // t09 failed — skip

        Map<String, Object> payload = new HashMap<>();
        payload.put("receivedQty", 0);
        payload.put("warehouse", "wh1");

        mockMvc.perform(patch("/api/purchase-orders/" + receivePOId + "/items/" + receiveItemId + "/receive")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("receivedQty must be greater than 0"));
    }
}
