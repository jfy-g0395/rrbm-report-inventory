package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PoItemRepository extends JpaRepository<PoItem, Long> {

    /** Unfulfilled PO items matching a given legacy item code — FIFO order (oldest PO item first). */
    List<PoItem> findByItemCodeAndIsFulfilledFalseOrderByIdAsc(String itemCode);

    /** Unfulfilled PO items whose supplier_item_code equals the given code — FIFO order.
     *  Used as the second match attempt when a product's itemCode matches a PO item's
     *  supplier SKU rather than the legacy item_code column. */
    List<PoItem> findBySupplierItemCodeAndIsFulfilledFalseOrderByIdAsc(String supplierItemCode);

    /** Fallback: unfulfilled PO items matching by item description (case-insensitive) — FIFO order. */
    List<PoItem> findByItemDescriptionIgnoreCaseAndIsFulfilledFalseOrderByIdAsc(String itemDescription);

    /** All PO items whose last DR number matches the given receipt — used during
     *  delivery cancellation reversal; ordered by id asc for deterministic processing. */
    List<PoItem> findByDrNumberOrderByIdAsc(String drNumber);
}
