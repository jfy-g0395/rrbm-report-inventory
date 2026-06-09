package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /** All transactions tied to a specific order, newest first. */
    List<Transaction> findByOrderIdOrderByCreatedAtDesc(String orderId);

    /** All transactions on a single accounting date. */
    List<Transaction> findByEffectiveDateOrderByCreatedAtDesc(LocalDate date);

    /** All transactions within an inclusive date range. */
    List<Transaction> findByEffectiveDateBetweenOrderByCreatedAtDesc(LocalDate start, LocalDate end);

    /** True if a SALE (or any type) already exists for this order. Prevents duplicate backfill. */
    boolean existsByOrderIdAndTransactionType(String orderId, String transactionType);

    /**
     * True if a transaction with this exact code exists.
     * Used by the M-26 deferred-void guard to check for COLL-DEFER-{id}
     * and COLL-SALE-{id} without a full table scan (transaction_code is UNIQUE).
     */
    boolean existsByTransactionCode(String transactionCode);

    // ── Aggregate queries used by daily-close and accounting-summary ──

    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t WHERE t.effectiveDate = :date")
    BigDecimal sumByDate(@Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t WHERE t.effectiveDate BETWEEN :start AND :end")
    BigDecimal sumByDateRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t WHERE t.effectiveDate = :date AND t.transactionType = :type")
    BigDecimal sumByDateAndType(@Param("date") LocalDate date, @Param("type") String type);

    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t WHERE t.effectiveDate BETWEEN :start AND :end " +
           "AND t.transactionType = :type")
    BigDecimal sumByDateRangeAndType(@Param("start") LocalDate start,
                                     @Param("end")   LocalDate end,
                                     @Param("type")  String type);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.effectiveDate = :date")
    long countByEffectiveDate(@Param("date") LocalDate date);

    /**
     * Net revenue for a date range: SUM of SALE amounts + SUM of RETURN amounts.
     * RETURN amounts are stored as negative values, so adding them gives the net.
     * Only SALE and RETURN transaction types are included (excludes VOID, REFUND, etc.).
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
           "WHERE t.effectiveDate BETWEEN :start AND :end " +
           "AND t.transactionType IN ('SALE', 'RETURN')")
    BigDecimal sumSaleNetForDateRange(@Param("start") LocalDate start,
                                     @Param("end")   LocalDate end);

    /**
     * Filtered ledger list for the transaction ledger view.
     * When type is null all transaction types are returned.
     */
    @Query("SELECT t FROM Transaction t WHERE " +
           "(:type IS NULL OR t.transactionType = :type) AND " +
           "t.effectiveDate BETWEEN :start AND :end " +
           "ORDER BY t.effectiveDate DESC, t.createdAt DESC")
    List<Transaction> findFiltered(@Param("type")  String type,
                                   @Param("start") LocalDate start,
                                   @Param("end")   LocalDate end);

    /**
     * Per-type aggregate for the ledger report: [transactionType, sum(amount), count].
     * Used by GET /api/transactions/ledger/report.
     */
    @Query("SELECT t.transactionType, SUM(t.amount), COUNT(t) FROM Transaction t " +
           "WHERE t.effectiveDate BETWEEN :start AND :end " +
           "GROUP BY t.transactionType ORDER BY t.transactionType")
    List<Object[]> aggregateByTypeForDateRange(@Param("start") LocalDate start,
                                               @Param("end")   LocalDate end);
}
