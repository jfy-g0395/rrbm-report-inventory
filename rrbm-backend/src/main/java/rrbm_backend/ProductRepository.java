package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // ── Concurrency-safe stock access (fixes lost updates under rapid cancels) ──

    /** Pessimistic write lock — used by deduction so concurrent stock changes on the
     *  same product take turns instead of overwriting each other (lost update). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    java.util.Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /** Atomic in-DB increments for stock restoration (add can't oversell, so no lock needed). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stockWh1 = p.stockWh1 + :delta WHERE p.id = :id")
    int addStockWh1(@Param("id") Long id, @Param("delta") int delta);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stockWh2 = p.stockWh2 + :delta WHERE p.id = :id")
    int addStockWh2(@Param("id") Long id, @Param("delta") int delta);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stockWh3 = p.stockWh3 + :delta WHERE p.id = :id")
    int addStockWh3(@Param("id") Long id, @Param("delta") int delta);

    // Get all active products sorted alphabetically
    List<Product> findByActiveTrueOrderByNameAsc();

    // Search by name (case-insensitive) among active products
    List<Product> findByNameContainingIgnoreCaseAndActiveTrue(String name);

    // Find by category among active products
    List<Product> findByCategoryAndActiveTrue(String category);

    @Query("SELECT DISTINCT p.category FROM Product p WHERE p.category IS NOT NULL AND TRIM(p.category) <> '' ORDER BY p.category")
    List<String> findDistinctCategory();

    @Query("SELECT DISTINCT p.subCategory FROM Product p WHERE p.subCategory IS NOT NULL AND TRIM(p.subCategory) <> '' ORDER BY p.subCategory")
    List<String> findDistinctSubCategory();

    @Query("SELECT DISTINCT p.subCategory FROM Product p WHERE p.category = :category AND p.subCategory IS NOT NULL AND TRIM(p.subCategory) <> '' ORDER BY p.subCategory")
    List<String> findDistinctSubCategoryByCategory(@org.springframework.data.repository.query.Param("category") String category);

    java.util.Optional<Product> findByItemCode(String itemCode);

    java.util.Optional<Product> findByProductCode(String productCode);
}
