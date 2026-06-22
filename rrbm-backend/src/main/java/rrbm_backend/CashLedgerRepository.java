package rrbm_backend;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface CashLedgerRepository extends JpaRepository<CashLedgerEntry, Long> {

    /** Current cash on hand = signed sum of every ledger row. */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CashLedgerEntry c")
    BigDecimal getCashOnHand();

    /** History, newest first, paged. */
    List<CashLedgerEntry> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** Net signed amount already recorded for one source event (for reversal math). */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CashLedgerEntry c "
         + "WHERE c.entryType = :type AND c.referenceType = :refType AND c.referenceId = :refId")
    BigDecimal sumForReference(@Param("type") String entryType,
                               @Param("refType") String referenceType,
                               @Param("refId") String referenceId);

    /** Whether any row exists for this source event (idempotency guard). */
    boolean existsByEntryTypeAndReferenceTypeAndReferenceId(
            String entryType, String referenceType, String referenceId);
}
