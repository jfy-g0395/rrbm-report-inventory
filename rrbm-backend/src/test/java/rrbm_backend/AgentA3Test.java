package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A3 integration tests — commission engine schema and period management.
 *
 * Lifecycle:
 *   @BeforeAll  — create test user (with BCrypt security key), test agent, test product
 *   @AfterAll   — delete entries → orders → transactions → inventory → periods → agent → product → user
 *
 * Tests run in declaration order via @TestMethodOrder so later tests can
 * rely on state (period IDs, order IDs) captured by earlier ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentA3Test {

    @Autowired private MockMvc                      mockMvc;
    @Autowired private UserRepository               userRepository;
    @Autowired private AgentRepository              agentRepository;
    @Autowired private ProductRepository            productRepository;
    @Autowired private OrderRepository              orderRepository;
    @Autowired private TransactionRepository        transactionRepository;
    @Autowired private InventoryMovementRepository  inventoryMovementRepository;
    @Autowired private CommissionPeriodRepository   periodRepository;
    @Autowired private CommissionEntryRepository    entryRepository;
    @Autowired private JwtUtil                      jwtUtil;

    private final ObjectMapper    mapper          = new ObjectMapper();
    private       User            testUser;
    private       String          jwt;
    private       Agent           testAgent;
    private       Product         testProduct;
    private       Long            testPeriodId;   // created in b, closed in d, released in e
    private final List<String>    createdOrderIds = new ArrayList<>();
    private final List<Long>      createdPeriodIds = new ArrayList<>();

    private static final String   TEST_SECURITY_KEY = "a3TestSecurityKey";

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());

        testUser = new User();
        testUser.setEmail("a3-test-" + suffix + "@test.rrbm.internal");
        testUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        testUser.setFullName("A3 Test Admin");
        testUser.setRole("ADMIN");
        testUser.setAdminSecurityKey(new BCryptPasswordEncoder().encode(TEST_SECURITY_KEY));
        testUser = userRepository.save(testUser);
        jwt = jwtUtil.generateToken(testUser);

        testAgent = new Agent();
        testAgent.setAgentCode("AGENT-A3-" + suffix.substring(suffix.length() - 4));
        testAgent.setFullName("A3 Test Agent " + suffix);
        testAgent.setContactNumber("09170000003");
        testAgent.setTerritory("Test Territory A3");
        testAgent.setStatus("ACTIVE");
        testAgent = agentRepository.save(testAgent);

        testProduct = new Product();
        testProduct.setName("A3 Test Product " + suffix);
        testProduct.setSku("A3SKU-" + suffix.substring(suffix.length() - 6));
        testProduct.setUnitPrice(new BigDecimal("50.00"));
        testProduct.setStockWh1(9999);
        testProduct.setActive(true);
        testProduct = productRepository.save(testProduct);

        // Remove any OPEN periods left from prior test runs (entries first, then periods)
        periodRepository.findByStatusOrderByStartDateDesc("OPEN")
                .forEach(p -> {
                    entryRepository.deleteAll(entryRepository.findByPeriodId(p.getId()));
                    periodRepository.delete(p);
                });
    }

    @AfterAll
    void tearDownAll() {
        // Commission entries must go before orders (FK: entries.order_id → orders.id)
        createdPeriodIds.forEach(pid ->
                entryRepository.deleteAll(entryRepository.findByPeriodId(pid)));

        createdOrderIds.forEach(orderId -> {
            transactionRepository.deleteAll(
                    transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId));
            inventoryMovementRepository.deleteAll(
                    inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(orderId));
        });
        createdOrderIds.forEach(orderId ->
                orderRepository.findById(orderId).ifPresent(orderRepository::delete));

        createdPeriodIds.forEach(pid -> periodRepository.deleteById(pid));

        agentRepository.delete(testAgent);
        inventoryMovementRepository.deleteAll(
                inventoryMovementRepository.findByProductIdOrderByCreatedAtDesc(testProduct.getId()));
        productRepository.delete(testProduct);
        userRepository.delete(testUser);
    }

    // ── A3-a: POST /api/commissions/periods without JWT → 401 ────────────────

    @Test
    @Order(1)
    void postPeriod_noJwt_returns401() throws Exception {
        mockMvc.perform(post("/api/commissions/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-07-01\",\"endDate\":\"2026-07-15\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── A3-b: valid body → 201; periodCode matches YYYY-MM-[A-Z] ─────────────

    @Test
    @Order(2)
    void postPeriod_validBody_returns201WithCorrectCodePattern() throws Exception {
        Map<String, Object> body = Map.of(
                "startDate", "2026-06-28",
                "endDate",   "2026-07-12",
                "notes",     "A3 test period Jul 1st cut-off"
        );

        String resp = mockMvc.perform(post("/api/commissions/periods")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        Number id = (Number) data.get("id");
        assertNotNull(id, "Response must contain id");
        testPeriodId = id.longValue();
        createdPeriodIds.add(testPeriodId);

        String code = (String) data.get("periodCode");
        assertNotNull(code, "Response must contain periodCode");
        assertTrue(code.matches("\\d{4}-\\d{2}-[A-Z]"),
                "periodCode must match YYYY-MM-[A-Z], got: " + code);
        assertEquals("OPEN", data.get("status"));
    }

    // ── A3-c: overlapping dates → 400 ────────────────────────────────────────

    @Test
    @Order(3)
    void postPeriod_overlappingDates_returns400() throws Exception {
        // Overlaps with the July period created in test b (2026-06-28 → 2026-07-12)
        Map<String, Object> body = Map.of(
                "startDate", "2026-07-01",
                "endDate",   "2026-07-15"
        );

        mockMvc.perform(post("/api/commissions/periods")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // ── A3-d: close period → 200; status = CLOSED ────────────────────────────

    @Test
    @Order(4)
    void closePeriod_returns200WithClosedStatus() throws Exception {
        String resp = mockMvc.perform(post("/api/commissions/periods/" + testPeriodId + "/close")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        assertEquals("CLOSED", data.get("status"), "Period status must be CLOSED after close");
        assertNotNull(data.get("closedAt"), "closedAt must be set");
    }

    // ── A3-e: release period with valid security key → 200; status = RELEASED ─

    @Test
    @Order(5)
    void releasePeriod_withValidSecurityKey_returns200WithReleasedStatus() throws Exception {
        Map<String, String> body = Map.of("adminSecurityKey", TEST_SECURITY_KEY);

        String resp = mockMvc.perform(post("/api/commissions/periods/" + testPeriodId + "/release")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> data = mapper.readValue(resp, Map.class);
        assertEquals("RELEASED", data.get("status"), "Period status must be RELEASED after release");
        assertNotNull(data.get("releasedAt"), "releasedAt must be set");
    }

    // ── A3-f: order with agentId + opAmount while OPEN period covers today → entries created ─

    @Test
    @Order(6)
    void createOrder_withAgentAndOpenPeriod_createsCommissionEntries() throws Exception {
        // Create a fresh OPEN period covering today using cut-off schema
        // Cut-off 1: 28th → 12th, Cut-off 2: 13th → 27th
        LocalDate today = LocalDate.now();
        int dom = today.getDayOfMonth();
        LocalDate startDate, endDate;
        if (dom <= 12) {
            startDate = today.minusMonths(1).withDayOfMonth(28);
            endDate   = today.withDayOfMonth(12);
        } else if (dom <= 27) {
            startDate = today.withDayOfMonth(13);
            endDate   = today.withDayOfMonth(27);
        } else {
            startDate = today.withDayOfMonth(28);
            endDate   = today.plusMonths(1).withDayOfMonth(12);
        }
        Map<String, Object> periodBody = Map.of(
                "startDate", startDate.toString(),
                "endDate",   endDate.toString()
        );

        String periodResp = mockMvc.perform(post("/api/commissions/periods")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(periodBody)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> periodData = mapper.readValue(periodResp, Map.class);
        Long newPeriodId = ((Number) periodData.get("id")).longValue();
        createdPeriodIds.add(newPeriodId);

        // Create order with agentId + basePrice + opPerUnit
        Map<String, Object> item = new java.util.HashMap<>();
        item.put("productId",   testProduct.getId());
        item.put("productName", testProduct.getName());
        item.put("quantity",    2);
        item.put("unitPrice",   "50.00");
        item.put("warehouse",   "wh1");
        item.put("basePrice",   "40.00");
        item.put("opPerUnit",   "6.00");

        Map<String, Object> orderBody = new java.util.HashMap<>();
        orderBody.put("customerName", "A3 Test Customer");
        orderBody.put("source",       "AGENT");
        orderBody.put("paymentMode",  "CASH");
        orderBody.put("agentId",      testAgent.getId());
        orderBody.put("items",        List.of(item));

        String orderResp = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(orderBody)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> orderData = mapper.readValue(orderResp, Map.class);
        String orderId = (String) orderData.get("id");
        assertNotNull(orderId, "Order id must be present");
        createdOrderIds.add(orderId);

        // Verify commission entries were created for this agent in the new period
        List<CommissionEntry> entries = entryRepository.findByPeriodIdAndAgentId(
                newPeriodId, testAgent.getId());
        assertFalse(entries.isEmpty(), "Commission entries must be created when OPEN period covers order date");
        assertEquals(1, entries.size(), "One entry per qualifying item");

        CommissionEntry entry = entries.get(0);
        assertEquals(orderId, entry.getOrderId(), "Entry must reference the created order");
        assertEquals(0, new BigDecimal("12.00").compareTo(entry.getOpAmount()),
                "opAmount must equal 6.00 * 2 = 12.00");
        assertEquals("PENDING", entry.getStatus(), "New entry status must be PENDING");
    }
}
