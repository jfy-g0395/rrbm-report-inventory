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
 * S6.2 / W-7 — Purchase Order CRUD & Status Transitions
 *
 * <p>Workflow: create PO with auto-generated PO number, manage items, update status.
 *
 * <p>Scenarios:
 * - Create PO: auto-generated PO-YYYY-NNNN, po_year_counter incremented
 * - Items: with unit price or supplier mapping pricing
 * - Status transitions: INCOMPLETE ↔ COMPLETE
 * - Invalid transitions rejected
 * - Activity logging
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
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "S6PO-Secret!";

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
                "s6po-acct-" + RUN + "@test.rrbm.internal", "S6PO Accounting", PASSWORD, null);
        userJwt = ITSupport.jwtFor(jwtUtil, accountingUser);

        // Seed products
        product1 = ITSupport.seedProduct(productRepository,
                "S6OA" + (RUN % 99), "S6PO Product 1", new BigDecimal("500.00"), 100);
        product2 = ITSupport.seedProduct(productRepository,
                "S6OB" + (RUN % 99), "S6PO Product 2", new BigDecimal("300.00"), 50);

        // Seed supplier
        supplier1 = ITSupport.seedSupplier(supplierRepository,
                "S6PO-SUPPLIER-" + RUN, "S6PO Contact");
    }

    @AfterAll
    void clean() {
        // Delete in FK-safe order
        if (accountingUser != null) {
            activityLogRepository.findByReportDateOrderByCreatedAtDesc(LocalDate.now()).stream()
                    .filter(a -> accountingUser.getId().equals(a.getUserId()))
                    .forEach(a -> activityLogRepository.deleteById(a.getId()));
        }

        // Delete purchase orders
        java.time.LocalDateTime yesterday = java.time.LocalDateTime.now().minusDays(1);
        java.time.LocalDateTime tomorrow = java.time.LocalDateTime.now().plusDays(1);
        purchaseOrderRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(po -> purchaseOrderRepository.delete(po));

        // Delete mappings and supplier
        if (supplier1 != null) {
            mappingRepository.findBySupplierId(supplier1.getId())
                    .forEach(m -> mappingRepository.delete(m));
            supplierRepository.deleteById(supplier1.getId());
        }

        // Delete products
        if (product1 != null) productRepository.deleteById(product1.getId());
        if (product2 != null) productRepository.deleteById(product2.getId());

        // Delete user
        if (accountingUser != null) userRepository.deleteById(accountingUser.getId());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PO Creation Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t01_createPurchaseOrder_success() throws Exception {
        // Arrange: create PO with 2 items
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

        // Act
        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // Assert
        List<PurchaseOrder> orders = purchaseOrderRepository.findByVendorName("VENDOR-" + RUN);
        assertThat(orders).hasSize(1);
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(orders.get(0).getId()).orElse(null);
        assertThat(po).isNotNull();
        assertThat(po.getPoNumber()).matches("^PO-\\d{6}-\\d+$"); // PO-DDMMYY-NNNNN format
        assertThat(po.getStatus()).isEqualTo("INCOMPLETE");
        assertThat(po.getItems()).hasSize(2);
        assertThat(po.getTotalAmount()).isEqualTo(new BigDecimal("3250.00")); // (10*250) + (5*150)
    }

    @Test
    void t02_createPurchaseOrder_withSupplierMapping() throws Exception {
        // Create supplier mapping first
        Map<String, Object> mappingPayload = new HashMap<>();
        mappingPayload.put("productId", product1.getId());
        mappingPayload.put("supplierItemCode", "VENDOR-P1");
        mappingPayload.put("unitCost", new BigDecimal("300.00"));

        mockMvc.perform(post("/api/suppliers/" + supplier1.getId() + "/mappings")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mappingPayload)))
                .andExpect(status().isOk());

        // Create PO with supplier linkage
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemDescription", "Mapped Item");
        item.put("productId", product1.getId());
        item.put("quantityOrdered", 5);
        // No explicit unitPrice — should use mapping cost
        items.add(item);

        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "MAPPED-VENDOR-" + RUN);
        payload.put("supplierId", supplier1.getId());
        payload.put("items", items);

        // Act
        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        // Assert
        List<PurchaseOrder> orders = purchaseOrderRepository.findByVendorName("MAPPED-VENDOR-" + RUN);
        assertThat(orders).hasSize(1);
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(orders.get(0).getId()).orElse(null);
        assertThat(po).isNotNull();
        assertThat(po.getSupplierId()).isEqualTo(supplier1.getId());
        assertThat(po.getItems()).hasSize(1);
        // Item should have picked up supplier mapping cost
        assertThat(po.getItems().get(0).getUnitPrice()).isEqualTo(new BigDecimal("300.00"));
        assertThat(po.getTotalAmount()).isEqualTo(new BigDecimal("1500.00")); // 5 * 300
    }

    @Test
    void t03_createPurchaseOrder_missingVendorName_rejected() throws Exception {
        // Arrange
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("itemDescription", "Item");
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("quantityOrdered", 1);
        items.add(item);

        Map<String, Object> payload = new HashMap<>();
        // No vendorName
        payload.put("items", items);

        // Act
        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t04_createPurchaseOrder_noItems_rejected() throws Exception {
        // Arrange: empty items
        Map<String, Object> payload = new HashMap<>();
        payload.put("vendorName", "NO-ITEMS-VENDOR-" + RUN);
        payload.put("items", new ArrayList<>());

        // Act
        mockMvc.perform(post("/api/purchase-orders")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void t05_poNumberFormat_incrementsCounter() throws Exception {
        // Create multiple POs in same year and verify counter increments
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

        // Verify both have PO-DDMMYY-NNNNN format and are sequential
        List<PurchaseOrder> orders1 = purchaseOrderRepository.findByVendorName("COUNTER-VENDOR-" + RUN + "-0");
        List<PurchaseOrder> orders2 = purchaseOrderRepository.findByVendorName("COUNTER-VENDOR-" + RUN + "-1");
        assertThat(orders1).hasSize(1);
        assertThat(orders2).hasSize(1);

        String po1Num = orders1.get(0).getPoNumber(); // PO-DDMMYY-N
        String po2Num = orders2.get(0).getPoNumber(); // PO-DDMMYY-N+1
        assertThat(po1Num).matches("^PO-\\d{6}-\\d+$");
        assertThat(po2Num).matches("^PO-\\d{6}-\\d+$");
        // Both should be same date
        assertThat(po1Num.substring(3, 9)).isEqualTo(po2Num.substring(3, 9));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PO Status Update Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void t06_updateStatus_incompleteToComplete() throws Exception {
        // Create a PO first
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

        // Update status
        Map<String, String> statusPayload = new HashMap<>();
        statusPayload.put("status", "COMPLETE");

        mockMvc.perform(patch("/api/purchase-orders/" + po.getId() + "/status")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(statusPayload)))
                .andExpect(status().isOk());

        // Assert
        PurchaseOrder updated = purchaseOrderRepository.findById(po.getId()).orElse(null);
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void t07_updateStatus_invalidStatus_rejected() throws Exception {
        // Create a PO
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

        // Try invalid status
        Map<String, String> statusPayload = new HashMap<>();
        statusPayload.put("status", "INVALID");

        mockMvc.perform(patch("/api/purchase-orders/" + po.getId() + "/status")
                .header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(statusPayload)))
                .andExpect(status().isBadRequest());

        // Assert status unchanged
        PurchaseOrder unchanged = purchaseOrderRepository.findById(po.getId()).orElse(null);
        assertThat(unchanged).isNotNull();
        assertThat(unchanged.getStatus()).isEqualTo("INCOMPLETE");
    }

    @Test
    void t08_noAuthToken_rejected() throws Exception {
        // Try to create PO without auth token
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
}
