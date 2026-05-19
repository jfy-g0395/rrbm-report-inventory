package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// Maps to the "products" table in the database.
// Uses Lombok @Data to auto-generate getters, setters, toString, etc.

@Data
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Unique stock-keeping unit code (e.g. "PB-10W")
    @Column(length = 50, unique = true)
    private String sku;

    // Product name — e.g. "Pizza Box 10\" White"
    @Column(nullable = false, length = 200)
    private String name;

    // Category — e.g. "Pizza Box", "Pastry Box", "Supplies"
    @Column(length = 80)
    private String category;

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
