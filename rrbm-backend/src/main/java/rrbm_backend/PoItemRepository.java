package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PoItemRepository extends JpaRepository<PoItem, Long> {

    /** Unfulfilled PO items for a given product — FIFO order (oldest PO item first).
     *  Primary key for delivery-receipt PO matching: a PO line and a delivery line
     *  are the same product when they share product_id. */
    List<PoItem> findByProductIdAndIsFulfilledFalseOrderByIdAsc(Long productId);

    /** Fallback: unfulfilled PO items matching by item description (case-insensitive) — FIFO order.
     *  Used only when product_id can't resolve a line (defensive; all live PO lines carry product_id). */
    List<PoItem> findByItemDescriptionIgnoreCaseAndIsFulfilledFalseOrderByIdAsc(String itemDescription);

    /** All PO items whose last DR number matches the given receipt — used during
     *  delivery cancellation reversal; ordered by id asc for deterministic processing. */
    List<PoItem> findByDrNumberOrderByIdAsc(String drNumber);
}
