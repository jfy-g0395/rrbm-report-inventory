package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for OrderItem entities.
 * Provides access to order line items for queries and updates.
 */
@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
