package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductSetComponentRepository extends JpaRepository<ProductSetComponent, Long> {

    /** All component rows for a given set product. */
    List<ProductSetComponent> findBySetProductId(Long setProductId);

    /** Remove all component rows for a set product (used before re-inserting on edit). */
    void deleteBySetProductId(Long setProductId);
}
