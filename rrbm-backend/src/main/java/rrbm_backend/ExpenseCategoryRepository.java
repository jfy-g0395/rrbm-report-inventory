package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, Long> {

    /** All primary categories (no parent), ordered for display. */
    List<ExpenseCategory> findByParentIdIsNullOrderBySortOrderAscNameAsc();

    /** All sub-categories under a given parent, ordered for display. */
    List<ExpenseCategory> findByParentIdOrderBySortOrderAscNameAsc(Long parentId);

    /** Look up a primary category by its short code (e.g. "FACILITY"). */
    Optional<ExpenseCategory> findByCode(String code);

    /** Count of primary categories — used by tests to verify seed rows. */
    long countByParentIdIsNull();

    /** Count of sub-categories — used by tests to verify seed rows. */
    long countByParentIdIsNotNull();

    /** All active categories — handy for populating dropdowns. */
    List<ExpenseCategory> findByActiveTrue();
}
