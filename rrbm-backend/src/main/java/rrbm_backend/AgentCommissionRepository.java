package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentCommissionRepository extends JpaRepository<AgentCommission, Long> {

    List<AgentCommission> findByAgentId(Long agentId);

    List<AgentCommission> findByPeriodId(Long periodId);

    Optional<AgentCommission> findByAgentIdAndPeriodId(Long agentId, Long periodId);
}
