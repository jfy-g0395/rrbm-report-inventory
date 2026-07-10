package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface CommissionAdjustmentRepository extends JpaRepository<CommissionAdjustment, Long> {

    List<CommissionAdjustment> findByPeriodIdAndAgentId(Long periodId, Long agentId);

    List<CommissionAdjustment> findByPeriodId(Long periodId);

    long countByPeriodId(Long periodId);

    /**
     * Net adjustments for a set of periods: SUM(BONUS) − SUM(DEDUCTION).
     * Returns 0 when no adjustments exist.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN a.adjustmentType = 'BONUS' " +
           "THEN a.amount ELSE -a.amount END), 0) " +
           "FROM CommissionAdjustment a WHERE a.periodId IN :periodIds")
    BigDecimal sumNetAdjustmentsForPeriods(@Param("periodIds") List<Long> periodIds);
}
