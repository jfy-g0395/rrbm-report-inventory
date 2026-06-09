package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Maps to the "products" table in the database.
// Uses Lombok @Data to auto-generate getters, setters, toString, etc.

@Data
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Unique stock-keeping unit code (e.g. "PB-10W") — legacy, kept for data compat
    @Column(length = 50, unique = true)
    private String sku;

    // 6-character alphanumeric product code shown in inventory UI (e.g. "PB20BN")
    @Column(name = "product_code", length = 6, unique = true)
    private String productCode;

    // Product name — e.g. "Pizza Box 10\" White"
    @Column(nullable = false, length = 200)
    private String name;

    // Category — e.g. "Pizza Box", "Pastry Box", "Supplies"
    @Column(length = 80)
    private String category;

    // Sub-category — e.g. "Plain Pizza Box", "Mailer Box", "Tapes"
    @Column(name = "sub_category", length = 100)
    private String subCategory;

    // Optional product description (dimensions, material, notes, etc.)
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // Item Code — used for Purchase Orders; separate from productCode (6-char UI identifier)
    // e.g. supplier's part number or internal PO reference code
    @Column(name = "item_code", length = 50, unique = true)
    private String itemCode;

    // HOT, SELLING, or SLOW — indicates sales velocity
    @Column(name = "selling_tag", length = 20)
    private String sellingTag = "SELLING";

    // Selling price per unit
    @Column(name = "unit_price", precision = 10, scale = 2, nullable = false)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    // Cost price per unit (for profit calculation)
    @Column(name = "unit_cost", precision = 10, scale = 2)
    private BigDecimal unitCost = BigDecimal.ZERO;

    // Stock level that triggers CRITICAL warning (red)
    @Column(name = "threshold_critical")
    private Integer thresholdCritical = 1000;

    // Stock level that triggers LOW warning (yellow)
    @Column(name = "threshold_low")
    private Integer thresholdLow = 0;

    // Stock in each warehouse
    @Column(name = "stock_wh1", nullable = false)
    private Integer stockWh1 = 0;

    @Column(name = "stock_wh2", nullable = false)
    private Integer stockWh2 = 0;

    @Column(name = "stock_wh3", nullable = false)
    private Integer stockWh3 = 0;

    // Set product flag — true means this product is composed of multiple inventory components.
    // Components are deducted from stock on order; the set itself has no stock of its own.
    @Column(name = "is_set", nullable = false)
    private Boolean isSet = false;

    // Transient: populated by ProductController on GET — NOT persisted by JPA.
    // Each entry: { componentProductId, componentProductName, quantityPerSet }
    @Transient
    private List<Map<String, Object>> components = new ArrayList<>();

    // Soft delete flag — false means discontinued/hidden
    private Boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Helper: total stock across all warehouses
    public int getTotalStock() {
        return (stockWh1 != null ? stockWh1 : 0)
             + (stockWh2 != null ? stockWh2 : 0)
             + (stockWh3 != null ? stockWh3 : 0);
    }
}
