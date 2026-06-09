package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * U3 — import history and status tracking.
 * Tests for GET /api/import/history and GET /api/import/history/{importRef}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportU3Test {

    @Autowired private MockMvc             mockMvc;
    @Autowired private UserRepository      userRepository;
    @Autowired private OrderRepository     orderRepository;
    @Autowired private JwtUtil             jwtUtil;

    private User   testUser;
    private String jwt;

    private static final String ORDER_ID   = "060626-U3T001";
    private static final String IMPORT_REF = "U3-IMPORT-REF-" + UUID.randomUUID().toString().substring(0, 8);

    private String adminFullName;

    @BeforeAll
    void setUp() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        String unique    = UUID.randomUUID().toString().substring(0, 8);
        adminFullName    = "U3Admin-" + unique;

        testUser = new User();
        testUser.setEmail("u3admin-" + unique + "@test.invalid");
        testUser.setFullName(adminFullName);
        testUser.setUsername("u3admin-" + unique);
        testUser.setPasswordHash(encoder.encode("password123"));
        testUser.setRole("ACCOUNTING");
        testUser.setAdminSecurityKey(encoder.encode("seckey123"));
        testUser.setStatus("ACTIVE");
        testUser = userRepository.save(testUser);

        jwt = jwtUtil.generateToken(testUser);

        // Insert an imported order directly via repo (bypasses service validation)
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setCustomerName("U3 Import Customer");
        order.setSource("WALK_IN");
        order.setPaymentMode("CASH");
        order.setStatus("ACTIVE");
        order.setOrderType("STANDARD");
        order.setDiscount(BigDecimal.ZERO);
        order.setDeliveryFee(BigDecimal.ZERO);
        order.setSubtotal(new BigDecimal("150.00"));
        order.setTotal(new BigDecimal("150.00"));
        order.setVoidedAmount(BigDecimal.ZERO);
        order.setImported(true);
        order.setImportRef(IMPORT_REF);
        order.setCreatedBy(testUser);
        // @PrePersist sets createdAt automatically
        orderRepository.save(order);
    }

    @AfterAll
    void tearDown() {
        orderRepository.deleteById(ORDER_ID);
        userRepository.delete(testUser);
    }

    // U3-a: no JWT → 401
    @Test
    @org.junit.jupiter.api.Order(1)
    void history_noJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/import/history"))
               .andExpect(status().isUnauthorized());
    }

    // U3-b: valid JWT → 200; response is an array
    @Test
    @org.junit.jupiter.api.Order(2)
    void history_validJwt_returns200Array() throws Exception {
        mockMvc.perform(get("/api/import/history")
               .header("Authorization", "Bearer " + jwt))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray());
    }

    // U3-c: the imported order from setUp() appears in the list with ordersCount >= 1
    @Test
    @org.junit.jupiter.api.Order(3)
    void history_importedOrder_appearsWithCorrectCounts() throws Exception {
        String body = mockMvc.perform(get("/api/import/history")
               .header("Authorization", "Bearer " + jwt))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isArray())
               .andReturn().getResponse().getContentAsString();

        // The unique adminFullName should appear in at least one batch entry
        assertThat(body).contains(adminFullName);
        // That batch should have ordersCount >= 1
        // Since we inserted exactly 1 order for this admin, the count is 1
        assertThat(body).contains("\"ordersCount\":1");
    }

    // U3-d: per-receipt detail for the known importRef → 200; orderId present
    @Test
    @org.junit.jupiter.api.Order(4)
    void historyDetail_knownRef_returns200WithOrderId() throws Exception {
        mockMvc.perform(get("/api/import/history/" + IMPORT_REF)
               .header("Authorization", "Bearer " + jwt))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.orderId").value(ORDER_ID))
               .andExpect(jsonPath("$.importRef").value(IMPORT_REF))
               .andExpect(jsonPath("$.isImported").value(true));
    }

    // U3-e: unknown importRef → 404
    @Test
    @org.junit.jupiter.api.Order(5)
    void historyDetail_unknownRef_returns404() throws Exception {
        mockMvc.perform(get("/api/import/history/UNKNOWN-REF-DOES-NOT-EXIST-U3")
               .header("Authorization", "Bearer " + jwt))
               .andExpect(status().isNotFound());
    }
}
