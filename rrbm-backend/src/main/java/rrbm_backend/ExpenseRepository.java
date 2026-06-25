package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /** All expenses for a specific date, newest first. */
    @Query("SELECT DISTINCT e FROM Expense e LEFT JOIN FETCH e.items WHERE e.date = :date ORDER BY e.createdAt DESC")
    List<Expense> findByDateWithItems(@Param("date") LocalDate date);

    /** Expenses for a set of ids with their line items eagerly loaded. Used to
     *  build descriptive cash-ledger labels (category per item) outside a tx. */
    @Query("SELECT DISTINCT e FROM Expense e LEFT JOIN FETCH e.items WHERE e.id IN :ids")
    List<Expense> findByIdInWithItems(@Param("ids") java.util.Collection<Long> ids);

    /** All expenses in a date range (inclusive), newest first. */
    @Query("SELECT DISTINCT e FROM Expense e LEFT JOIN FETCH e.items WHERE e.date BETWEEN :start AND :end ORDER BY e.date DESC, e.createdAt DESC")
    List<Expense> findByDateRangeWithItems(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** Sum of totalAmount for non-voided expenses on a single date (null if no rows). */
    @Query("SELECT SUM(e.totalAmount) FROM Expense e " +
           "WHERE e.date = :date AND e.voided = false AND e.status <> 'VOIDED'")
    BigDecimal sumNonVoidedForDate(@Param("date") LocalDate date);

    /** Count of non-voided expenses on a single date. */
    @Query("SELECT COUNT(e) FROM Expense e " +
           "WHERE e.date = :date AND e.voided = false AND e.status <> 'VOIDED'")
    long countNonVoidedForDate(@Param("date") LocalDate date);

    /** Sum of totalAmount for non-voided expenses in an inclusive date range (null if no rows). */
    @Query("SELECT SUM(e.totalAmount) FROM Expense e " +
           "WHERE e.date BETWEEN :start AND :end AND e.voided = false AND e.status <> 'VOIDED'")
    BigDecimal sumNonVoidedForDateRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * Data-integrity sentinel: expenses that have voided=true but status != 'VOIDED'.
     * Should normally be 0; a non-zero count indicates a state inconsistency.
     */
    @Query("SELECT COUNT(e) FROM Expense e WHERE e.voided = true AND e.status <> 'VOIDED'")
    long countPendingVoids();

    /**
     * Month-to-date spend grouped by primary expense category.
     *
     * Walk-up rule: if an item's category has a parentId (sub-category), the parent
     * (primary) category is used for grouping; items whose category is already a
     * primary (parentId IS NULL) are grouped under themselves. Items with null
     * categoryId are excluded. Voided expenses are excluded.
     *
     * Returns rows of [categoryCode (String), categoryName (String), total (BigDecimal)],
     * ordered by total DESC, filtered to > 0 spend.
     */
    @Query("SELECT COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name), SUM(i.amount) " +
           "FROM Expense e JOIN e.items i " +
           "JOIN ExpenseCategory cat ON cat.id = i.categoryId " +
           "LEFT JOIN ExpenseCategory prim ON prim.id = cat.parentId " +
           "WHERE e.voided = false AND e.status <> 'VOIDED' " +
           "AND i.categoryId IS NOT NULL " +
           "AND EXTRACT(YEAR FROM e.date) = :year " +
           "AND EXTRACT(MONTH FROM e.date) = :month " +
           "GROUP BY COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name) " +
           "HAVING SUM(i.amount) > 0 " +
           "ORDER BY SUM(i.amount) DESC")
    List<Object[]> sumByPrimaryCategoryForMonth(@Param("year") int year,
                                                @Param("month") int month);

    // ── E5 queries ─────────────────────────────────────────────────────────────

    /**
     * Daily spend grouped by primary category for a single date.
     * Returns rows of [categoryCode, categoryName, total (BigDecimal), entries (Long)].
     */
    @Query("SELECT COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name), " +
           "SUM(i.amount), COUNT(DISTINCT e.id) " +
           "FROM Expense e JOIN e.items i " +
           "JOIN ExpenseCategory cat ON cat.id = i.categoryId " +
           "LEFT JOIN ExpenseCategory prim ON prim.id = cat.parentId " +
           "WHERE e.voided = false AND e.status <> 'VOIDED' " +
           "AND i.categoryId IS NOT NULL " +
           "AND e.date = :date " +
           "GROUP BY COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name) " +
           "HAVING SUM(i.amount) > 0 " +
           "ORDER BY SUM(i.amount) DESC")
    List<Object[]> sumByPrimaryCategoryForDate(@Param("date") LocalDate date);

    /**
     * Daily spend grouped by payment method for a single date.
     * Returns rows of [paymentMethod (String), total (BigDecimal), count (Long)].
     */
    @Query("SELECT e.paymentMethod, SUM(e.totalAmount), COUNT(e) " +
           "FROM Expense e WHERE e.date = :date " +
           "AND e.voided = false AND e.status <> 'VOIDED' " +
           "GROUP BY e.paymentMethod")
    List<Object[]> sumByPaymentMethodForDate(@Param("date") LocalDate date);

    /**
     * Monthly spend grouped by primary category, including entry count.
     * Returns rows of [categoryCode, categoryName, total (BigDecimal), entries (Long)].
     */
    @Query("SELECT COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name), " +
           "SUM(i.amount), COUNT(DISTINCT e.id) " +
           "FROM Expense e JOIN e.items i " +
           "JOIN ExpenseCategory cat ON cat.id = i.categoryId " +
           "LEFT JOIN ExpenseCategory prim ON prim.id = cat.parentId " +
           "WHERE e.voided = false AND e.status <> 'VOIDED' " +
           "AND i.categoryId IS NOT NULL " +
           "AND EXTRACT(YEAR FROM e.date) = :year " +
           "AND EXTRACT(MONTH FROM e.date) = :month " +
           "GROUP BY COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name) " +
           "HAVING SUM(i.amount) > 0 " +
           "ORDER BY SUM(i.amount) DESC")
    List<Object[]> sumByPrimaryCategoryForMonthWithCount(@Param("year") int year,
                                                         @Param("month") int month);

    /**
     * Per-day totals for non-voided expenses in an inclusive date range.
     * Only days that have at least one non-voided expense appear; caller fills gaps.
     * Returns rows of [date (LocalDate), total (BigDecimal), count (Long)].
     */
    @Query("SELECT e.date, SUM(e.totalAmount), COUNT(e) " +
           "FROM Expense e WHERE e.date BETWEEN :start AND :end " +
           "AND e.voided = false AND e.status <> 'VOIDED' " +
           "GROUP BY e.date ORDER BY e.date ASC")
    List<Object[]> sumByDayForDateRange(@Param("start") LocalDate start,
                                        @Param("end") LocalDate end);

    /**
     * All voided expenses in an inclusive date range, ordered by date then voidedAt.
     */
    @Query("SELECT e FROM Expense e WHERE e.date BETWEEN :start AND :end " +
           "AND (e.voided = true OR e.status = 'VOIDED') " +
           "ORDER BY e.date ASC, e.voidedAt ASC")
    List<Expense> findVoidedByDateRange(@Param("start") LocalDate start,
                                        @Param("end") LocalDate end);

    // ── A16 query ──────────────────────────────────────────────────────────────

    /**
     * Spend grouped by primary category for an inclusive date range.
     * Returns rows of [categoryCode, categoryName, total (BigDecimal)], ordered total DESC.
     * Only non-voided expenses with a non-null categoryId are counted.
     */
    @Query("SELECT COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name), SUM(i.amount) " +
           "FROM Expense e JOIN e.items i " +
           "JOIN ExpenseCategory cat ON cat.id = i.categoryId " +
           "LEFT JOIN ExpenseCategory prim ON prim.id = cat.parentId " +
           "WHERE e.voided = false AND e.status <> 'VOIDED' " +
           "AND i.categoryId IS NOT NULL " +
           "AND e.date BETWEEN :start AND :end " +
           "GROUP BY COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name) " +
           "HAVING SUM(i.amount) > 0 " +
           "ORDER BY SUM(i.amount) DESC")
    List<Object[]> sumByPrimaryCategoryForDateRange(@Param("start") LocalDate start,
                                                    @Param("end") LocalDate end);

    // ── Corporate monthly report query ──────────────────────────────────────────

    /**
     * Monthly spend grouped by sub-category (leaf), carrying its primary category for
     * nesting in the corporate report. Mirrors {@link #sumByPrimaryCategoryForMonthWithCount}
     * but keeps the leaf category split out so the report can show sub-category detail
     * under each primary.
     *
     * <p>Walk-up rule for the primary columns is the same: a sub-category's parent is the
     * primary; a category that is itself a primary (parentId IS NULL) reports itself as both
     * primary and leaf. Voided expenses and null-category items are excluded.
     *
     * Returns rows of [primaryCode (String), primaryName (String), subName (String),
     * total (BigDecimal), entries (Long)], ordered by primary total then sub total DESC.
     */
    @Query("SELECT COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name), cat.name, " +
           "SUM(i.amount), COUNT(DISTINCT e.id) " +
           "FROM Expense e JOIN e.items i " +
           "JOIN ExpenseCategory cat ON cat.id = i.categoryId " +
           "LEFT JOIN ExpenseCategory prim ON prim.id = cat.parentId " +
           "WHERE e.voided = false AND e.status <> 'VOIDED' " +
           "AND i.categoryId IS NOT NULL " +
           "AND EXTRACT(YEAR FROM e.date) = :year " +
           "AND EXTRACT(MONTH FROM e.date) = :month " +
           "GROUP BY COALESCE(prim.code, cat.code), COALESCE(prim.name, cat.name), cat.name " +
           "HAVING SUM(i.amount) > 0 " +
           "ORDER BY COALESCE(prim.name, cat.name) ASC, SUM(i.amount) DESC")
    List<Object[]> sumBySubCategoryForMonthWithCount(@Param("year") int year,
                                                     @Param("month") int month);

    // ── U1 queries ─────────────────────────────────────────────────────────────

    /**
     * All non-voided imported expenses in an inclusive date range, with items eagerly fetched.
     * Used by daily/monthly expense reports to build the importedEntries section and
     * to compute importedOnly totals without an extra round-trip.
     */
    @Query("SELECT DISTINCT e FROM Expense e LEFT JOIN FETCH e.items " +
           "WHERE e.date BETWEEN :start AND :end " +
           "AND e.imported = true AND e.voided = false AND e.status <> 'VOIDED' " +
           "ORDER BY e.date ASC, e.createdAt ASC")
    List<Expense> findImportedByDateRange(@Param("start") LocalDate start,
                                          @Param("end") LocalDate end);

    // ── U3 query ───────────────────────────────────────────────────────────────

    /**
     * All imported expenses in an inclusive date range, newest first.
     * adminName is denormalized on the entity so no join is required.
     */
    @Query("SELECT e FROM Expense e WHERE e.imported = true " +
           "AND e.date BETWEEN :start AND :end " +
           "ORDER BY e.date DESC, e.createdAt DESC")
    List<Expense> findImportedExpensesByDateRange(@Param("start") LocalDate start,
                                                   @Param("end") LocalDate end);

    // ── A11 queries ─────────────────────────────────────────────────────────────

    /**
     * All expenses in a date range for export, ordered by date ASC then id ASC.
     * Does NOT filter voided — caller handles inclusion based on the includeVoided param.
     */
    @Query("SELECT DISTINCT e FROM Expense e LEFT JOIN FETCH e.items " +
           "WHERE e.date BETWEEN :start AND :end " +
           "ORDER BY e.date ASC, e.id ASC")
    List<Expense> findByDateRangeForExport(@Param("start") LocalDate start,
                                            @Param("end") LocalDate end);

    /** Soft-duplicate check for CSV imports: same date + total_amount + reference_number. */
    boolean existsByDateAndTotalAmountAndReferenceNumber(
            LocalDate date, java.math.BigDecimal totalAmount, String referenceNumber);
}
