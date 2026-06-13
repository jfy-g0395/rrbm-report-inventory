package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierProductMappingRepository extends JpaRepository<SupplierProductMapping, Long> {

    /** All mappings for a supplier — used by the supplier mappings list endpoint. */
    List<SupplierProductMapping> findBySupplierId(Long supplierId);

    /** All mappings for a product — used by the product suppliers lookup endpoint. */
    List<SupplierProductMapping> findByProductId(Long productId);

    /** Exact lookup for one supplier+product pair — used during PO creation to resolve
     *  the snapshot supplierItemCode and supplierDescription for a line item. */
    Optional<SupplierProductMapping> findBySupplierIdAndProductId(Long supplierId, Long productId);

    /** All preferred mappings for a product — used by Option A enforcement to clear
     *  existing preferred flags before setting a new one. */
    List<SupplierProductMapping> findByProductIdAndIsPreferredTrue(Long productId);

    /** Existence check used before insert to avoid DataIntegrityViolationException
     *  on the (supplier_id, product_id) unique constraint. */
    boolean existsBySupplierIdAndProductId(Long supplierId, Long productId);
}
