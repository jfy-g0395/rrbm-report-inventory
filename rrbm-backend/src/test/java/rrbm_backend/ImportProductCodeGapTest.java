package rrbm_backend;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression tests for the batch-import inventory gap.
 *
 * Before the fix: a sales row whose Product Code did not match any product parsed as VALID
 * (with a null productId). It committed as a real order but InventoryService.deductStockForOrder()
 * silently skipped the null-productId item, so revenue was recorded without deducting stock.
 * Especially likely for ECOMMERCE rows whose marketplace SKUs differ from internal codes.
 *
 * After the fix: an unmatched-but-non-blank Product Code is a hard needsFix error, so it can
 * never reach commit unresolved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportProductCodeGapTest {

    @Autowired private MockMvc           mockMvc;
    @Autowired private UserRepository    userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private JwtUtil           jwtUtil;

    private final BCryptPasswordEncoder encoder      = new BCryptPasswordEncoder();
    private static final String         SECURITY_KEY = "PCGAP-test-key-2026";

    private User    accountingUser;
    private String  accountingJwt;
    private Product testProduct;

    @BeforeAll
    void setUpAll() {
        String suffix = String.valueOf(System.currentTimeMillis());

        accountingUser = new User();
        accountingUser.setEmail("pcgap-acct-" + suffix + "@test.rrbm.internal");
        accountingUser.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        accountingUser.setFullName("PCGAP Accounting User");
        accountingUser.setRole("ACCOUNTING");
        accountingUser.setAdminSecurityKey(encoder.encode(SECURITY_KEY));
        accountingUser = userRepository.save(accountingUser);
        accountingJwt  = jwtUtil.generateToken(accountingUser);

        testProduct = new Product();
        testProduct.setName("PCGAP Test Product " + suffix);
        testProduct.setProductCode("PCGAP" + suffix.substring(suffix.length() - 2));
        testProduct.setItemCode("PCGIC" + suffix.substring(suffix.length() - 6));
        testProduct.setStockWh1(100);
        testProduct.setStockWh2(0);
        testProduct.setStockWh3(0);
        testProduct.setActive(true);
        testProduct.setIsSet(false);
        testProduct.setUnitPrice(new BigDecimal("100.00"));
        testProduct.setSellingTag("SLOW");
        testProduct.setThresholdLow(0);
        testProduct.setThresholdCritical(0);
        testProduct = productRepository.save(testProduct);
    }

    @AfterAll
    void tearDownAll() {
        if (testProduct    != null) productRepository.delete(testProduct);
        if (accountingUser != null) userRepository.delete(accountingUser);
    }

    // The reported scenario: an ECOMMERCE row with a marketplace SKU that has no matching
    // internal product. Must land in needsFix, NOT valid.
    @Test
    void ecommerceUnmatchedProductCode_goesToNeedsFix_notValid() throws Exception {
        String csv = "Date,Receipt#,Time,Customer,Source,Agent,Payment Method,Platform,Product Code,Qty,Unit Price,OP per Unit,Payment Status\r\n" +
                     "2026-01-15,PCGAP-ECOM-001,10:00,Shopee Buyer,ECOMMERCE,,COD,SHOPEE,NO-SUCH-SKU-999,2,250.00,,\r\n";

        mockMvc.perform(multipart("/api/import/upload/sales")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("adminSecurityKey", SECURITY_KEY)
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.validCount").value(0))
                .andExpect(jsonPath("$.needsFix.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.needsFix[*].errors[*]",
                        hasItem(containsString("NO-SUCH-SKU-999"))));
    }

    // Positive control: a matching product code still parses as valid.
    @Test
    void matchedProductCode_staysValid() throws Exception {
        String csv = "Date,Receipt#,Time,Customer,Source,Agent,Payment Method,Platform,Product Code,Qty,Unit Price,OP per Unit,Payment Status\r\n" +
                     "2026-01-15,PCGAP-OK-001,10:00,Walk In,WALK_IN,,CASH,," + testProduct.getProductCode() + ",1,100.00,,\r\n";

        mockMvc.perform(multipart("/api/import/upload/sales")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .param("adminSecurityKey", SECURITY_KEY)
                        .header("Authorization", "Bearer " + accountingJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.validCount").value(greaterThanOrEqualTo(1)));
    }
}
