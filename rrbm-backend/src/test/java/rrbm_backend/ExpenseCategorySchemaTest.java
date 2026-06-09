package rrbm_backend;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E1 integration tests — expense_categories schema, seed rows, void columns,
 * and the ACCOUNTING role constraint fix.
 *
 * Uses @SpringBootTest so Flyway runs V49 + V50 before any test executes.
 * Hibernate's ddl-auto=validate (configured in application.properties) also
 * confirms that every new entity field matches the migrated schema — a separate
 * assertion would be redundant.
 *
 * @Transactional: each test runs in its own transaction that is rolled back
 * after the test, so inserts created here never persist to the live database.
 *
 * Seed rows (inserted by V49, committed before the test transaction starts)
 * are readable inside the test transaction but are never modified or removed
 * by these tests.
 */
@SpringBootTest
@Transactional
class ExpenseCategorySchemaTest {

    @Autowired private ExpenseCategoryRepository categoryRepository;
    @Autowired private ExpenseRepository         expenseRepository;
    @Autowired private UserRepository            userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // ── T1: Primary category seed count ──────────────────────────────────────

    /**
     * V49 must seed exactly 8 primary categories (parent_id IS NULL):
     * FACILITY, UTILITY, SUPPLY, INVENTORY, OPERATIONS, PERSONNEL, SERVICES, MISC.
     */
    @Test
    void primaryCategories_seedCount_isEight() {
        long count = categoryRepository.countByParentIdIsNull();
        assertEquals(8L, count,
                "Expected 8 seeded primary categories; found " + count +
                ". Check V49 seed block — one INSERT per primary category.");
    }

    // ── T2: Sub-category seed count ───────────────────────────────────────────

    /**
     * V49 seeds 32 sub-categories + V64 adds 2 (Parking Fee, Checker Fee) = 34:
     *   FACILITY 3 · UTILITY 3 · SUPPLY 3 · INVENTORY 5
     *   OPERATIONS 7 · PERSONNEL 5 · SERVICES 4 · MISC 4
     */
    @Test
    void subCategories_seedCount_isThirtyFour() {
        long count = categoryRepository.countByParentIdIsNotNull();
        assertEquals(34L, count,
                "Expected 34 seeded sub-categories; found " + count +
                ". Check V49 + V64 seed blocks for each primary group.");
    }

    // ── T3: Representative primary category detail ────────────────────────────

    /**
     * Spot-check that FACILITY was seeded with the correct name and flags.
     * If this fails, something is wrong with the seed INSERT or the entity mapping.
     */
    @Test
    void facilityCategory_seededWithCorrectAttributes() {
        Optional<ExpenseCategory> opt = categoryRepository.findByCode("FACILITY");

        assertTrue(opt.isPresent(), "FACILITY category must be present after V49 seed");

        ExpenseCategory facility = opt.get();
        assertEquals("Facility Costs", facility.getName());
        assertNull(facility.getParentId(),      "Primary category must have null parentId");
        assertTrue(facility.isSystemDefined(),  "System categories must be marked system-defined");
        assertTrue(facility.isActive(),         "System categories must default to active");
        assertFalse(facility.isRequiresReceipt(),"FACILITY does not require a receipt by default");
    }

    // ── T4: Sub-categories exist under OPERATIONS ─────────────────────────────

    /**
     * OPERATIONS should have exactly 7 sub-categories per §1.2 (V64 adds Parking Fee & Checker Fee).
     * This also exercises findByParentIdOrderBySortOrderAscNameAsc().
     */
    @Test
    void operationsCategory_hasSevenSubCategories() {
        ExpenseCategory ops = categoryRepository.findByCode("OPERATIONS")
                .orElseThrow(() -> new AssertionError("OPERATIONS category not found"));

        long subCount = categoryRepository.countByParentIdIsNotNull();
        // Narrow count to just OPERATIONS children
        long opsChildren = categoryRepository
                .findByParentIdOrderBySortOrderAscNameAsc(ops.getId())
                .size();

        assertEquals(7, opsChildren,
                "OPERATIONS must have exactly 7 sub-categories; found " + opsChildren);
    }

    // ── T5: Legacy expense (null category_id) still loads without error ───────

    /**
     * An ExpenseItem with categoryId = NULL (as all pre-V49 rows have) must
     * load cleanly — no JPA mapping error, no NOT-NULL violation.
     * This verifies the ADD COLUMN IF NOT EXISTS was done without a DEFAULT
     * constraint that would prevent null values.
     */
    @Test
    void legacyExpenseItem_withNullCategoryId_savesAndLoadsWithoutError() {
        // Build a minimal expense with a free-text item and no category — exactly
        // how existing rows in the live DB look after V49 alters the table.
        Expense expense = new Expense();
        expense.setDate(LocalDate.now());
        expense.setAdminId(null);
        expense.setAdminName("Test Admin (E1)");

        ExpenseItem item = new ExpenseItem();
        item.setItemDescription("Legacy free-text item — no category");
        item.setCategoryId(null); // explicit null: pre-V49 style
        item.setAmount(new BigDecimal("250.00"));
        item.setExpense(expense);
        expense.getItems().add(item);
        expense.recalculateTotal();

        Expense saved = expenseRepository.save(expense);
        entityManager.flush();
        entityManager.clear(); // evict from first-level cache so the reload hits the DB

        Expense loaded = expenseRepository.findById(saved.getId())
                .orElseThrow(() -> new AssertionError("Saved expense not found on reload"));

        assertEquals(1, loaded.getItems().size(), "Expected exactly one item");
        assertNull(loaded.getItems().get(0).getCategoryId(),
                "categoryId must survive round-trip as null for legacy rows");
        assertEquals("Legacy free-text item — no category",
                loaded.getItems().get(0).getItemDescription());
        assertEquals(new BigDecimal("250.00"), loaded.getItems().get(0).getAmount());

        // Void columns must default correctly
        assertFalse(loaded.isVoided(), "New expense must default to is_voided = false");
        assertNull(loaded.getVoidedAt());
        assertNull(loaded.getVoidedBy());
        assertNull(loaded.getVoidReason());
    }

    // ── T6: ACCOUNTING role inserts without constraint violation ─────────────

    /**
     * Before V50 the chk_role constraint on users only allowed:
     *   SUPER_ADMIN, ADMIN, ADMINISTRATOR, STAFF, STANDARD_USER
     * V50 adds ACCOUNTING.  This test fails (ConstraintViolationException) if
     * V50 was not applied, confirming the migration is required and working.
     */
    @Test
    void accountingRole_user_insertsWithoutConstraintViolation() {
        User user = new User();
        // Use a timestamp-suffix to avoid unique-constraint clashes across test runs
        String ts = String.valueOf(System.currentTimeMillis());
        user.setEmail("e1-test-acct-" + ts + "@test.rrbm.internal");
        user.setPasswordHash("$2a$10$placeholderHashForTestOnly000000000000000000000000000");
        user.setFullName("E1 Accounting Test User");
        user.setRole("ACCOUNTING");

        User saved = userRepository.save(user);
        entityManager.flush(); // forces the INSERT and fires the DB constraint check now

        assertNotNull(saved.getId(),
                "User must receive a generated id after successful save");
        assertEquals("ACCOUNTING", saved.getRole(),
                "Role must be preserved as ACCOUNTING after save");
    }
}
