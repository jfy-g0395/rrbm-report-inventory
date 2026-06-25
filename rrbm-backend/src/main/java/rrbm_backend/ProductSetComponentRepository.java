package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ProductSetComponentRepository extends JpaRepository<ProductSetComponent, Long> {

    /** All component rows for a given set product. */
    List<ProductSetComponent> findBySetProductId(Long setProductId);

    /** Distinct product ids that are a component of at least one set (read-only).
     *  Used to flag products as not-independently-sellable in the order form. */
    @Query("SELECT DISTINCT c.componentProductId FROM ProductSetComponent c")
    List<Long> findAllComponentProductIds();

    /** True when the given product is a component of at least one set.
     *  Backstop for "components are not independently sellable" — enforced
     *  server-side so non-UI paths (batch import, raw API) are covered too. */
    boolean existsByComponentProductId(Long componentProductId);

    /** All set rows that contain the given product as a component.
     *  Used to name the correct set code in rejection messages. */
    List<ProductSetComponent> findByComponentProductId(Long componentProductId);

    /** Remove all component rows for a set product (used before re-inserting on edit).
     *  @Modifying forces an immediate SQL DELETE before any pending INSERTs flush,
     *  preventing duplicate-key violations on the unique constraint. */
    @Modifying
    @Query("DELETE FROM ProductSetComponent c WHERE c.setProductId = :setProductId")
    void deleteBySetProductId(@Param("setProductId") Long setProductId);
}
