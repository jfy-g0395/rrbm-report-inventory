package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByAgentCode(String agentCode);

    List<Agent> findByStatusOrderByFullNameAsc(String status);

    List<Agent> findByTerritoryIgnoreCaseOrderByFullNameAsc(String territory);

    List<Agent> findByRegistrationDateBetweenOrderByFullNameAsc(LocalDate from, LocalDate to);

    // Count of orders linked to a specific agent via the agent_id FK on the orders table.
    @Query("SELECT COUNT(o) FROM Order o WHERE o.agentId = :agentId")
    long countOrdersByAgentId(@Param("agentId") Long agentId);

    // Max 4-digit sequence number already used for a given year prefix (e.g. 'AGENT-2026-%').
    // SUBSTRING position 12 extracts NNNN from "AGENT-YYYY-NNNN" (positions 1-11 = "AGENT-YYYY-").
    // Native query: Hibernate JPQL CAST of a string SUBSTRING is fragile across versions.
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(agent_code, 12) AS INTEGER)), 0) " +
                   "FROM agents WHERE agent_code LIKE :prefix",
           nativeQuery = true)
    int maxSequenceForYear(@Param("prefix") String prefix);
}
