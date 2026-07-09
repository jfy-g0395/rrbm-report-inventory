package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResellerRepository extends JpaRepository<Reseller, Long> {

    Optional<Reseller> findByResellerCode(String resellerCode);

    List<Reseller> findByStatusOrderByNameAsc(String status);

    List<Reseller> findByTypeOrderByNameAsc(String type);

    List<Reseller> findByTypeAndStatusOrderByNameAsc(String type, String status);

    // Count of orders linked to a reseller via the orders.reseller_id FK.
    @Query("SELECT COUNT(o) FROM Order o WHERE o.resellerId = :resellerId")
    long countOrdersByResellerId(@Param("resellerId") Long resellerId);

    // Outstanding (pending-collection) order count + amount for a reseller.
    @Query("SELECT COUNT(o), COALESCE(SUM(o.total), 0) FROM Order o " +
           "WHERE o.resellerId = :resellerId AND o.status = 'PENDING_COLLECTION'")
    Object[] outstandingForReseller(@Param("resellerId") Long resellerId);

    // Max 4-digit sequence for a code prefix (e.g. 'RSL-2026-%'). SUBSTRING position 10
    // extracts NNNN from "RSL-YYYY-NNNN" (positions 1-9 = "RSL-YYYY-").
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(reseller_code, 10) AS INTEGER)), 0) " +
                   "FROM resellers WHERE reseller_code LIKE :prefix",
           nativeQuery = true)
    int maxSequenceForPrefix(@Param("prefix") String prefix);
}
