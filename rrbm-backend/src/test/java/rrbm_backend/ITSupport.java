package rrbm_backend;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cross-session integration-test helpers (built in S1, reused everywhere).
 *
 * <p>Deliberately a plain package-private utility class — <b>not</b> a base test — so every
 * {@code *IT} keeps its own explicit {@code @BeforeAll} workflow visible (readability over
 * inheritance, per INTEGRATION-TEST-PLAN.md §3). Each helper takes the autowired repositories
 * as parameters rather than holding Spring state.
 *
 * <p>All seeded entities are created in <i>production shape</i> (real hashed credentials, real
 * role/status) and use caller-supplied unique suffixes on natural keys so concurrent runs against
 * the shared local Postgres never collide.
 */
final class ITSupport {

    /** Single shared encoder — bcrypt is stateless, so reuse is safe. */
    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private ITSupport() { }

    /**
     * Persist a real {@link User} with a bcrypt-hashed password and (optionally) a hashed
     * personal admin security key.
     *
     * @param rawPassword     plaintext password — stored only as a bcrypt hash
     * @param rawSecurityKey  plaintext admin security key, or {@code null} to leave unassigned
     */
    static User seedUser(UserRepository userRepository,
                         String role,
                         String email,
                         String fullName,
                         String rawPassword,
                         String rawSecurityKey) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(ENCODER.encode(rawPassword));
        u.setFullName(fullName);
        u.setRole(role);
        u.setStatus("ACTIVE");
        if (rawSecurityKey != null) {
            u.setAdminSecurityKey(ENCODER.encode(rawSecurityKey));
        }
        return userRepository.save(u);
    }

    /** Mint a real signed JWT for the given user (same path the login endpoint uses). */
    static String jwtFor(JwtUtil jwtUtil, User user) {
        return jwtUtil.generateToken(user);
    }

    /** Persist a real {@link MasterKey} row with a bcrypt-hashed key (never the raw value). */
    static MasterKey seedMasterKey(MasterKeyRepository masterKeyRepository, String raw, String label) {
        MasterKey mk = new MasterKey();
        mk.setKeyHash(ENCODER.encode(raw));
        mk.setLabel(label);
        mk.setActive(true);
        return masterKeyRepository.save(mk);
    }

    /**
     * Persist a real {@link Product} with standard warehouses and pricing.
     *
     * @param productCode unique product code
     * @param price selling price (unitPrice field)
     * @param stockWh1 initial stock for warehouse 1
     */
    static Product seedProduct(ProductRepository productRepository,
                               String productCode,
                               String name,
                               BigDecimal price,
                               int stockWh1) {
        Product p = new Product();
        p.setProductCode(productCode);
        p.setName(name);
        p.setUnitPrice(price);
        p.setUnitCost(price.multiply(new BigDecimal("0.6"))); // Set cost to 60% of selling price
        p.setStockWh1(stockWh1);
        p.setStockWh2(50);
        p.setStockWh3(25);
        p.setActive(true);
        return productRepository.save(p);
    }

    /**
     * Persist a real {@link Agent} with ACTIVE status.
     */
    static Agent seedAgent(AgentRepository agentRepository,
                           String agentCode,
                           String fullName,
                           String territory) {
        Agent a = new Agent();
        a.setAgentCode(agentCode);
        a.setFullName(fullName);
        a.setTerritory(territory);
        a.setStatus("ACTIVE");
        a.setContactNumber("09991234567"); // Required field
        return agentRepository.save(a);
    }

    /**
     * Persist a real {@link CommissionPeriod} in OPEN status.
     * Note: CommissionPeriods are global (not per-agent). Agent linkage is made
     * through CommissionEntry rows which carry the agentId.
     */
    static CommissionPeriod seedOpenPeriod(CommissionPeriodRepository periodRepository,
                                          LocalDate startDate,
                                          LocalDate endDate) {
        long now = System.currentTimeMillis() % 100000;
        CommissionPeriod p = new CommissionPeriod();
        p.setPeriodCode("TEST-PERIOD-" + now);
        p.setStartDate(startDate);
        p.setEndDate(endDate);
        p.setStatus("OPEN");
        return periodRepository.save(p);
    }

    /**
     * Persist a real {@link Supplier} with ACTIVE status.
     *
     * @param supplierName unique supplier name
     */
    static Supplier seedSupplier(SupplierRepository supplierRepository,
                                 String supplierName,
                                 String contactPerson) {
        Supplier s = new Supplier();
        s.setName(supplierName);
        s.setContactPerson(contactPerson);
        s.setContactNumber("09991234567");
        s.setPaymentTerms("NET-30");
        s.setIsActive(true);
        return supplierRepository.save(s);
    }
}
