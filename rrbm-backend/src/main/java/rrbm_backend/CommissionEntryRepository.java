package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface CommissionEntryRepository extends JpaRepository<CommissionEntry, Long> {

    List<CommissionEntry> findByPeriodIdAndAgentId(Long periodId, Long agentId);

    List<CommissionEntry> findByPeriodId(Long periodId);

    List<CommissionEntry> findByOrderId(String orderId);

    long countByPeriodId(Long periodId);

    @Query("SELECT e.agentId, SUM(e.opAmount) FROM CommissionEntry e " +
           "WHERE e.periodId = :periodId GROUP BY e.agentId")
    List<Object[]> sumByAgentForPeriod(@Param("periodId") Long periodId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE CommissionEntry e SET e.status = 'RELEASED' WHERE e.periodId = :periodId")
    void releaseAllByPeriodId(@Param("periodId") Long periodId);

    /** Sum of opAmount for RELEASED entries in the given period IDs. */
    @Query("SELECT COALESCE(SUM(e.opAmount), 0) FROM CommissionEntry e " +
           "WHERE e.periodId IN :periodIds AND e.status = 'RELEASED'")
    BigDecimal sumReleasedOpAmountForPeriods(@Param("periodIds") List<Long> periodIds);

    /** Per-agent sum of RELEASED opAmount for a single period — used when writing agent_commissions rows. */
    @Query("SELECT e.agentId, SUM(e.opAmount) FROM CommissionEntry e " +
           "WHERE e.periodId = :periodId AND e.status = 'RELEASED' GROUP BY e.agentId")
    List<Object[]> sumReleasedOpByAgentForPeriod(@Param("periodId") Long periodId);

    /** Sum of PENDING opAmount for a specific agent across all periods. */
    @Query("SELECT COALESCE(SUM(e.opAmount), 0) FROM CommissionEntry e " +
           "WHERE e.agentId = :agentId AND e.status = 'PENDING'")
    BigDecimal sumPendingOpAmountByAgentId(@Param("agentId") Long agentId);

    /** True if at least one commission entry exists for this order (idempotency guard). */
    boolean existsByOrderId(String orderId);
}
