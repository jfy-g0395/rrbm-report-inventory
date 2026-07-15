package rrbm_backend;

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
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms the CSV e-commerce import fix (/api/orders/batch):
 *  (1) a large import COMPLETES and returns its imported/failed/skipped report
 *      (the O(N^2) persistence-context growth that used to exceed the proxy
 *      timeout is gone — entityManager.clear() per row), and
 *  (2) the duplicate guard still reports already-imported rows as skipped.
 *
 * Runs against an isolated Postgres; discarded after. Not a strict timing gate
 * (CI timing is noisy) — it prints the elapsed time and relies on the fact that
 * an O(N^2) run over this many stock-deducting rows would not finish promptly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchImportPerfIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CommissionPeriodRepository commissionPeriodRepository;
    @Autowired private JwtUtil jwtUtil;

    private final ObjectMapper om = new ObjectMapper();
    private static final long RUN = System.currentTimeMillis() % 100000;
    private static final int  N   = 250;

    private User   user;
    private String jwt;
    private Product product;

    @BeforeAll
    void seed() {
        user = ITSupport.seedUser(userRepository, "ACCOUNTING",
                "perf-" + RUN + "@test.rrbm.internal", "Perf User", "Perf-Secret!", "perf-key");
        jwt  = ITSupport.jwtFor(jwtUtil, user);
        // Plenty of stock so all N single-unit orders deduct successfully.
        product = ITSupport.seedProduct(productRepository,
                "PERF" + (RUN % 99), "Perf Product", new BigDecimal("150.00"), 100000);
        ITSupport.seedOpenPeriod(commissionPeriodRepository,
                LocalDate.now(), LocalDate.now().plusDays(30));
    }

    @AfterAll
    void noop() { /* isolated throwaway DB — cleanup not required */ }

    private Map<String, Object> order(String ref) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId",   product.getId());
        item.put("productName", product.getName());
        item.put("quantity",    1);
        item.put("unitPrice",   new BigDecimal("150.00"));
        item.put("warehouse",   "wh1");

        Map<String, Object> o = new HashMap<>();
        o.put("customerName",      "Perf Cust " + ref);
        o.put("source",            "ECOMMERCE");
        o.put("ecommercePlatform", "SHOPEE");
        o.put("paymentMode",       "COD");
        o.put("orderType",         "STANDARD");
        o.put("notes",             "Order No: " + ref + " |");
        o.put("discount",          0);
        o.put("deliveryFee",       0);
        o.put("items",             List.of(item));
        return o;
    }

    @Test
    void largeImport_completes_andReturnsReport() throws Exception {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (int i = 0; i < N; i++) payload.add(order("PERF" + RUN + "-" + i));

        long t0 = System.currentTimeMillis();
        MvcResult res = mockMvc.perform(post("/api/orders/batch")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        long ms = System.currentTimeMillis() - t0;

        Map<String, Object> body = om.readValue(res.getResponse().getContentAsString(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        System.out.println("[BatchImportPerfIT] imported " + N + " orders in " + ms + " ms");

        // (1) The report is present and everything went in.
        assertThat(body).containsKeys("imported", "failed", "succeeded", "errors", "skipped");
        assertThat(((Number) body.get("imported")).intValue()).isEqualTo(N);
        assertThat(((Number) body.get("failed")).intValue()).isZero();

        // Generous completion bound — an O(N^2) run over N stock-deducting rows would not
        // finish anywhere near this quickly; a healthy O(N) run is a few seconds.
        assertThat(ms).as("large import must complete well within the proxy timeout").isLessThan(60000L);
    }

    @Test
    void duplicateOrders_areReportedAsSkipped() throws Exception {
        String ref = "DUP" + RUN;
        String one = om.writeValueAsString(List.of(order(ref)));

        // First import: goes in.
        mockMvc.perform(post("/api/orders/batch")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON).content(one))
                .andExpect(status().isOk());

        // Second import of the same order-ref: reported as skipped, not re-imported.
        MvcResult res = mockMvc.perform(post("/api/orders/batch")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON).content(one))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> body = om.readValue(res.getResponse().getContentAsString(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(((Number) body.get("imported")).intValue()).isZero();
        assertThat((List<?>) body.get("skipped")).hasSize(1);
    }
}
