package rrbm_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delivery-report item editing (V83) — mandatory reason gate + full stock re-sync + change log.
 *
 * <p>Receives a delivery for a fresh product (stock 0 → 10), then edits the line down to 8 and
 * asserts inventory is re-synced (10 → 8), the change is recorded in the report's change log, and
 * an edit with an empty reason is rejected.
 *
 * <p>Run command (DB up + migrated):
 * <pre>mvn test -Dtest=DeliveryEditItemsIT</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class DeliveryEditItemsIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DeliveryLogRepository deliveryLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEC_KEY = "DEIT-key-" + RUN;

    private User admin;
    private String jwt;
    private Product product;
    private Long deliveryId;
    private final String receipt = "DEIT" + RUN;   // matches ^[A-Za-z0-9-]{2,20}$

    @BeforeAll
    void seed() throws Exception {
        admin = ITSupport.seedUser(userRepository, "SUPER_ADMIN",
                "deit-" + RUN + "@test.rrbm.internal", "DEIT Admin " + RUN, "DEIT-Secret!", SEC_KEY);
        jwt = ITSupport.jwtFor(jwtUtil, admin);
        // product_code is varchar(6) — build a 6-char unique code from RUN.
        product = ITSupport.seedProduct(productRepository, "D" + String.format("%05d", RUN), "DEIT Widget " + RUN,
                new BigDecimal("10.00"), 0);

        // Receive a delivery: 10 units into wh1 → product stock becomes 10.
        Map<String, Object> req = Map.of(
                "receiptNumber", receipt,
                "supplierName", "DEIT Supplier",
                "receiverName", "Receiver",
                "encodedByName", admin.getFullName(),
                "items", List.of(Map.of(
                        "productId", product.getId(),
                        "quantity", 10,
                        "received", 10,
                        "rejected", 0,
                        "unitCost", 5.0,
                        "warehouse", "wh1")));
        MvcResult res = mockMvc.perform(post("/api/products/delivery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(MAPPER.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = MAPPER.readTree(res.getResponse().getContentAsString());
        deliveryId = body.get("id").asLong();
    }

    @AfterAll
    void clean() {
        // Best-effort: real FKs (payables, inventory_movements→users) reference these rows.
        // Leftover RUN-suffixed rows on the local test DB are harmless, so don't fail on cleanup.
        try { if (deliveryId != null) deliveryLogRepository.findById(deliveryId).ifPresent(deliveryLogRepository::delete); } catch (Exception ignored) {}
        try { if (admin != null) userRepository.deleteById(admin.getId()); } catch (Exception ignored) {}
    }

    @Test
    void t01_receive_added_stock() {
        assertEquals(10, productRepository.findById(product.getId()).orElseThrow().getStockWh1(),
                "Receiving the delivery should add 10 to wh1");
    }

    @Test
    void t02_edit_without_reason_is_rejected() throws Exception {
        Map<String, Object> body = Map.of(
                "securityKey", SEC_KEY,
                "reason", "",
                "items", List.of(Map.of("productId", product.getId(), "receivedQty", 8, "warehouse", "wh1", "unitCost", 5.0)));
        mockMvc.perform(patch("/api/delivery-reports/" + deliveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        // Stock must be untouched by a rejected edit.
        assertEquals(10, productRepository.findById(product.getId()).orElseThrow().getStockWh1());
    }

    @Test
    void t03_edit_quantity_resyncs_stock_and_logs_change() throws Exception {
        Map<String, Object> body = Map.of(
                "securityKey", SEC_KEY,
                "reason", "Wrong quantity encoded — should be 8",
                "items", List.of(Map.of("productId", product.getId(), "receivedQty", 8, "warehouse", "wh1", "unitCost", 5.0)));
        mockMvc.perform(patch("/api/delivery-reports/" + deliveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk());

        // Full re-sync: 10 reversed, 8 re-applied → 8.
        assertEquals(8, productRepository.findById(product.getId()).orElseThrow().getStockWh1(),
                "Editing received qty 10→8 should re-sync wh1 stock to 8");

        DeliveryLog log = deliveryLogRepository.findById(deliveryId).orElseThrow();
        assertNotNull(log.getChangeLog(), "Change log should be recorded");
        assertTrue(log.getChangeLog().contains("Reason:"), "Change log should include the reason");
        assertEquals(8, log.getTotalQuantity(), "Total quantity should reflect the edited line");
    }

    @Test
    void t04_edit_unknown_product_is_rejected() throws Exception {
        Map<String, Object> body = Map.of(
                "securityKey", SEC_KEY,
                "reason", "Trying an invalid product",
                "items", List.of(Map.of("productId", 999999999L, "receivedQty", 3, "warehouse", "wh1")));
        mockMvc.perform(patch("/api/delivery-reports/" + deliveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
