package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    // Get all movements for a specific product (for product history view)
    List<InventoryMovement> findByProductIdOrderByCreatedAtDesc(Long productId);

    // Get all movements linked to a specific order
    List<InventoryMovement> findByReferenceIdOrderByCreatedAtDesc(String referenceId);
}
