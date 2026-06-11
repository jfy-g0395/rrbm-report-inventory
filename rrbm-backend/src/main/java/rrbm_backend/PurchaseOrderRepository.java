package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByPoNumber(String poNumber);

    List<PurchaseOrder> findByVendorName(String vendorName);

    List<PurchaseOrder> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /** All POs with their items eagerly loaded (avoids N+1). */
    @Query("SELECT DISTINCT po FROM PurchaseOrder po LEFT JOIN FETCH po.items " +
           "ORDER BY po.createdAt DESC")
    List<PurchaseOrder> findAllWithItems();

    /** Single PO with items eagerly loaded. */
    @Query("SELECT po FROM PurchaseOrder po LEFT JOIN FETCH po.items WHERE po.id = :id")
    Optional<PurchaseOrder> findByIdWithItems(@Param("id") Long id);
}
