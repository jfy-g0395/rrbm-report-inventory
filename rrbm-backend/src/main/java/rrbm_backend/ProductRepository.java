package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // Get all active products sorted alphabetically
    List<Product> findByActiveTrueOrderByNameAsc();

    // Search by name (case-insensitive) among active products
    List<Product> findByNameContainingIgnoreCaseAndActiveTrue(String name);

    // Find by category among active products
    List<Product> findByCategoryAndActiveTrue(String category);
}
