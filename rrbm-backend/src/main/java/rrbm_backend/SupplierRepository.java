package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    /** Active suppliers only, sorted A-Z — used for the default list endpoint. */
    List<Supplier> findByIsActiveTrueOrderByNameAsc();

    /** All suppliers including inactive, sorted A-Z — used with ?includeInactive=true. */
    List<Supplier> findAllByOrderByNameAsc();
}
