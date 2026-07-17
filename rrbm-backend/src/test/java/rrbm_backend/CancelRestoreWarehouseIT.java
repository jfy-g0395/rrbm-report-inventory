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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression for the cancel/restore warehouse-asymmetry bug (#3).
 *
 * A SET's components are deducted greedily ACROSS warehouses, but the old cancel returned the whole
 * quantity to a single warehouse — leaving one warehouse with a phantom shortage (the reported
 * "−500 out of nowhere"). The fix restores from the inventory_movements ledger, so each warehouse
 * gets back exactly what it lost.
 *
 * Setup: component with WH1=500, WH2=500 (total 1000). Order 800 sets → 500 pulled from WH1, 300
 * from WH2. After cancel, both warehouses must return to 500 (not WH1=800 / WH2=200).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancelRestoreWarehouseIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductSetComponentRepository setComponentRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final String PASSWORD = "CRW-Secret!";
    private static final String SEC_KEY  = "CRW-key-" + RUN;

    private final ObjectMapper mapper = new ObjectMapper();

    private User user;
    private String jwt;
    private Product component;
    private Product setProduct;
    private ProductSetComponent link;
    private String orderId;

    @BeforeAll
    void seed() {
        user = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "crw-" + RUN + "@test.rrbm.internal", "CRW Accounting", PASSWORD, SEC_KEY);
        jwt = ITSupport.jwtFor(jwtUtil, user);

        // Component split across two warehouses: WH1=500, WH2=500, WH3=0
        component = ITSupport.seedProduct(productRepository,
                "CRWC" + (RUN % 99), "CRW Component", new BigDecimal("50.00"), 500);
        component.setStockWh2(500);
        component.setStockWh3(0);
        component.setIsSet(false);
        component = productRepository.save(component);

        // Set product (no own stock) made of 1× component
        setProduct = new Product();
        setProduct.setProductCode("CRWS" + (RUN % 99));
        setProduct.setName("CRW Set");
        setProduct.setUnitPrice(new BigDecimal("100.00"));
        setProduct.setUnitCost(new BigDecimal("60.00"));
        setProduct.setStockWh1(0);
        setProduct.setStockWh2(0);
        setProduct.setStockWh3(0);
        setProduct.setActive(true);
        setProduct.setIsSet(true);
        setProduct = productRepository.save(setProduct);

        link = new ProductSetComponent();
        link.setSetProductId(setProduct.getId());
        link.setComponentProductId(component.getId());
        link.setQuantityPerSet(1);
        link = setComponentRepository.save(link);
    }

    @AfterAll
    void clean() {
        if (orderId != null) {
            inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(orderId)
                    .forEach(inventoryMovementRepository::delete);
            transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                    .forEach(transactionRepository::delete);
            orderRepository.deleteById(orderId);
        }
        if (link != null)       setComponentRepository.deleteById(link.getId());
        if (setProduct != null) productRepository.deleteById(setProduct.getId());
        if (component != null)  productRepository.deleteById(component.getId());
        if (user != null)       userRepository.deleteById(user.getId());
    }

    @Test
    void cancellingSplitWarehouseSet_returnsStockToEachOriginWarehouse() throws Exception {
        // Create an order for 800 sets → deducts 500 from WH1 + 300 from WH2 of the component
        Map<String, Object> item = new HashMap<>();
        item.put("productId", setProduct.getId());
        item.put("productName", setProduct.getName());
        item.put("quantity", 800);
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("warehouse", "wh1"); // ignored for sets (greedy), but avoids any warehouse-required check

        Map<String, Object> request = new HashMap<>();
        request.put("customerName", "CRW Customer " + RUN);
        request.put("source", "WALK_IN");
        request.put("paymentMode", "CASH");
        request.put("items", List.of(item));

        String resp = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        orderId = mapper.readTree(resp).get("id").asText();

        // Sanity: component drained 500 from WH1 and 300 from WH2
        Product afterOrder = productRepository.findById(component.getId()).get();
        assertThat(afterOrder.getStockWh1()).isEqualTo(0);
        assertThat(afterOrder.getStockWh2()).isEqualTo(200);

        // Cancel
        Map<String, String> cancel = new HashMap<>();
        cancel.put("securityKey", SEC_KEY);
        cancel.put("reason", "regression test");
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(mapper.writeValueAsString(cancel)))
                .andExpect(status().isOk());

        // Each warehouse must be back to its pre-order level — no phantom shortage/surplus
        Product afterCancel = productRepository.findById(component.getId()).get();
        assertThat(afterCancel.getStockWh1()).isEqualTo(500);
        assertThat(afterCancel.getStockWh2()).isEqualTo(500);
        assertThat(afterCancel.getStockWh3()).isEqualTo(0);
    }
}
