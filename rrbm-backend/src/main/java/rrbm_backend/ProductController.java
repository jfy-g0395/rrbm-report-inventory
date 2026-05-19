package rrbm_backend;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = "*")
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // GET /api/products — returns all active products (for the order form dropdown)
    @GetMapping
    public List<Product> getAllActiveProducts() {
        return productRepository.findByActiveTrueOrderByNameAsc();
    }

    // GET /api/products/search?name=pizza — search as user types
    @GetMapping("/search")
    public List<Product> searchProducts(@RequestParam String name) {
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(name);
    }

    // GET /api/products/all — returns ALL products including inactive (for admin)
    @GetMapping("/all")
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }
}
