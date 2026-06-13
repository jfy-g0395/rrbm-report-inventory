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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S13 — Order Reads (W-10)
 *
 * <p>Seeds one CASH/WALK_IN order via the real POST endpoint, then exercises every
 * order-read endpoint: list-all, today, single-by-id, history-range, and search.
 * Covers the happy path, shape validation, boundary cases, and the 401 gate on
 * every endpoint.
 *
 * <p>Run command (DB up + migrated):
 * <pre>mvn test -Dtest=OrderReadIT</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class OrderReadIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CommissionEntryRepository commissionEntryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private JwtUtil jwtUtil;

    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private User user;
    private String jwt;
    private Product product;
    private String orderId;
    private String customerName;

    @BeforeAll
    void seed() throws Exception {
        customerName = "S13-Read-Customer-" + RUN;

        user = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "s13rd-" + RUN + "@test.rrbm.internal",
                "S13 ReadIT " + RUN, "S13RD-Secret!", "S13RD-key");
        jwt = ITSupport.jwtFor(jwtUtil, user);

        product = ITSupport.seedProduct(productRepository,
                "S13P" + (RUN % 99), "S13 Read Product " + RUN,
                new BigDecimal("400.00"), 200);

        // Create one CASH WALK_IN order via the real endpoint
        Map<String, Object> item = new HashMap<>();
        item.put("productId",   product.getId());
        item.put("productName", product.getName());
        item.put("quantity",    2);
        item.put("unitPrice",   new BigDecimal("400.00"));
        item.put("warehouse",   "wh1");

        Map<String, Object> req = new HashMap<>();
        req.put("customerName", customerName);
        req.put("paymentMode",  "CASH");
        req.put("source",       "WALK_IN");
        req.put("items",        List.of(item));

        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jwt)
                        .content(MAPPER.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        orderId = MAPPER.readTree(body).path("id").asText();
        assertThat(orderId).isNotBlank();
    }

    @AfterAll
    void clean() {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        LocalDateTime tomorrow  = LocalDateTime.now().plusDays(1);
        activityLogRepository.deleteAll();
        transactionRepository.deleteAll();
        inventoryMovementRepository.deleteAll();
        commissionEntryRepository.deleteAll();
        orderRepository.findByCreatedAtBetween(yesterday, tomorrow)
                .forEach(o -> orderRepository.deleteById(o.getId()));
        if (product != null) productRepository.deleteById(product.getId());
        if (user    != null) userRepository.deleteById(user.getId());
    }

    // ── GET /api/orders ──────────────────────────────────────────────────────

    @Test
    void t01_getAllOrders_seededOrderPresent() throws Exception {
        String body = mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();

        boolean found = false;
        for (JsonNode order : arr) {
            if (orderId.equals(order.path("id").asText(""))) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .withFailMessage("Expected seeded order %s in GET /api/orders response", orderId)
                .isTrue();
    }

    @Test
    void t02_getAllOrders_responseShape() throws Exception {
        String body = mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isGreaterThanOrEqualTo(1);

        // Verify shape on the first entry
        JsonNode first = arr.get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("customerName")).isTrue();
        assertThat(first.has("status")).isTrue();
        assertThat(first.has("total")).isTrue();
        assertThat(first.has("createdAt")).isTrue();
        assertThat(first.has("items")).isTrue();
        assertThat(first.path("items").isArray()).isTrue();
    }

    // ── GET /api/orders/today ────────────────────────────────────────────────

    @Test
    void t03_getTodaysOrders_seededOrderPresent() throws Exception {
        String body = mockMvc.perform(get("/api/orders/today")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();

        boolean found = false;
        for (JsonNode order : arr) {
            if (orderId.equals(order.path("id").asText(""))) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .withFailMessage("Expected seeded order %s in GET /api/orders/today response", orderId)
                .isTrue();
    }

    // ── GET /api/orders/{id} ─────────────────────────────────────────────────

    @Test
    void t04_getOrderById_returnsCorrectOrder() throws Exception {
        String body = mockMvc.perform(get("/api/orders/" + orderId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode order = MAPPER.readTree(body);
        assertThat(order.path("id").asText()).isEqualTo(orderId);
        assertThat(order.path("customerName").asText()).isEqualTo(customerName);
        assertThat(order.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(order.path("paymentMode").asText()).isEqualTo("CASH");
        assertThat(order.path("total").decimalValue()).isEqualByComparingTo(new BigDecimal("800.00"));

        // items array must be present and contain 1 entry
        JsonNode items = order.path("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isEqualTo(1);

        JsonNode item = items.get(0);
        assertThat(item.path("productName").asText()).isEqualTo(product.getName());
        assertThat(item.path("quantity").asInt()).isEqualTo(2);
        assertThat(item.path("unitPrice").decimalValue()).isEqualByComparingTo(new BigDecimal("400.00"));
    }

    @Test
    void t05_getOrderById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/orders/999999-UNKNOWN")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/orders/history ──────────────────────────────────────────────

    @Test
    void t06_getOrderHistory_explicitTodayRange_seededOrderPresent() throws Exception {
        // The default range is minusMonths(1)→yesterday, which would exclude today's order.
        // Pass start=today&end=today explicitly to find the seeded order.
        String today = LocalDate.now().toString();

        String body = mockMvc.perform(get("/api/orders/history")
                        .param("start", today)
                        .param("end",   today)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();

        boolean found = false;
        for (JsonNode order : arr) {
            if (orderId.equals(order.path("id").asText(""))) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .withFailMessage("Expected seeded order %s in history?start=%s&end=%s", orderId, today, today)
                .isTrue();
    }

    @Test
    void t07_getOrderHistory_defaultRange_returnsArrayAndExcludesTodayOrder() throws Exception {
        // No params → defaults to minusMonths(1)→yesterday; today's order must NOT appear.
        String body = mockMvc.perform(get("/api/orders/history")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();

        for (JsonNode order : arr) {
            assertThat(order.path("id").asText(""))
                    .withFailMessage("Today's order %s must not appear in the default history range (minusMonths(1)→yesterday)", orderId)
                    .isNotEqualTo(orderId);
        }
    }

    // ── GET /api/orders/search ───────────────────────────────────────────────

    @Test
    void t08_searchOrders_byCustomerName_seededOrderFound() throws Exception {
        String body = mockMvc.perform(get("/api/orders/search")
                        .param("customerName", customerName)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isGreaterThanOrEqualTo(1);

        boolean found = false;
        for (JsonNode order : arr) {
            if (orderId.equals(order.path("id").asText(""))) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .withFailMessage("Expected seeded order %s in search results for customerName=%s", orderId, customerName)
                .isTrue();
    }

    @Test
    void t09_searchOrders_noiseString_returnsEmptyArray() throws Exception {
        String body = mockMvc.perform(get("/api/orders/search")
                        .param("customerName", "XNOISEXXNOISEXX-" + RUN)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = MAPPER.readTree(body);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(0);
    }

    // ── 401 gates ────────────────────────────────────────────────────────────

    @Test
    void t10_getAllOrders_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void t11_getTodaysOrders_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/orders/today"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void t12_getOrderById_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void t13_getOrderHistory_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/orders/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void t14_searchOrders_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/orders/search")
                        .param("customerName", "anything"))
                .andExpect(status().isUnauthorized());
    }
}
