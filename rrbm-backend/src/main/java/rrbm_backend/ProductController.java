package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rrbm_backend.dto.DeliveryRequest;
import java.util.Map;
import rrbm_backend.ActivityLogService;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = "*")
public class ProductController {

    private final ProductRepository productRepository;
    private final ActivityLogService activityLogService;

    public ProductController(ProductRepository productRepository, ActivityLogService activityLogService) {
        this.productRepository = productRepository;
        this.activityLogService = activityLogService;
    }

    // GET /api/products — returns all active products (for the order form dropdown)
    @GetMapping
    public List<Product> getAllActiveProducts() {
        return productRepository.findByActiveTrueOrderByNameAsc();
    }

    // GET /api/products/search?name=pizza — search as user types
    @GetMapping("/search")
    public List<Product> searchProducts(@RequestParam String name) {
        activityLogService.log(null, "product_search", "Searched for: " + name);
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(name);
    }

    // GET /api/products/categories – distinct category list for dropdowns
    @GetMapping("/categories")
    public List<String> getCategories() {
        activityLogService.log(null, "category_fetch", "Fetched distinct product categories");
        return productRepository.findDistinctCategory();
    }

    // POST /api/products/delivery – process delivery receipt and add/update stock
    @PostMapping("/delivery")
    public ResponseEntity<?> processDelivery(@RequestBody DeliveryRequest request) {
        // Validate receipt number (6-7 alphanumeric chars)
        if (!request.getReceiptNumber().matches("^[A-Za-z0-9]{6,7}$")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid receipt number format"));
        }
        // Log the receipt (could be persisted later)
        activityLogService.log(null, "delivery_receipt", "Receipt: " + request.getReceiptNumber());
        // Update each product's stock according to delivered items
        request.getItems().forEach(item -> {
            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product != null) {
                switch (item.getWarehouse().toLowerCase()) {
                    case "wh1": product.setStockWh1(product.getStockWh1() + item.getQuantity()); break;
                    case "wh2": product.setStockWh2(product.getStockWh2() + item.getQuantity()); break;
                    case "wh3": product.setStockWh3(product.getStockWh3() + item.getQuantity()); break;
                }
                productRepository.save(product);
                activityLogService.log(null, "stock_update", "Added " + item.getQuantity() + " to " + product.getName());
            }
        });
        return ResponseEntity.ok().build();
    }

    // GET /api/products/all — returns ALL products including inactive (for admin)
    @GetMapping("/all")
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }
}
