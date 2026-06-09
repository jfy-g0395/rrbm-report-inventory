package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {

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
