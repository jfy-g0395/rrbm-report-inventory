package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    // Get all movements for a specific product (for product history view)
    List<InventoryMovement> findByProductIdOrderByCreatedAtDesc(Long productId);

    // Get all movements linked to a specific order
    List<InventoryMovement> findByReferenceIdOrderByCreatedAtDesc(String referenceId);

    // All movements within a datetime range (for the daily/weekly Movement Log report)
    List<InventoryMovement> findByCreatedAtBetweenOrderByCreatedAtDesc(
            java.time.LocalDateTime start, java.time.LocalDateTime end);

    // Find rejected movements (void/cancel/return) within a date range for the rejected items report
    @Query("SELECT m FROM InventoryMovement m " +
           "WHERE m.movementType IN ('VOID_REJECTED', 'CANCEL_REJECTED', 'RETURN_REJECTED') " +
           "  AND m.quantity > 0 " +
           "  AND m.createdAt >= :start " +
           "  AND m.createdAt < :end " +
           "ORDER BY m.createdAt DESC")
    List<InventoryMovement> findRejectedMovementsByDateRange(
            @Param("start") java.time.LocalDateTime start,
            @Param("end")   java.time.LocalDateTime end);
}
