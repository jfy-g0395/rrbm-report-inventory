package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

// This is the JPA entity that maps to the "order_items" table.
// Each row represents one product line in an order.
// For example, "100 pcs of Pizza Box 10" White at ₱13.00 each"

@Data
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Many order items belong to one order.
    // This creates the foreign key: order_items.order_id → orders.id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // We store product_id as a plain Long (not a @ManyToOne relation).
    // This is because product_id is nullable in the database — 
    // if someone types a product manually instead of selecting from dropdown,
    // there's no matching product record.
    @Column(name = "product_id")
    private Long productId;

    // Denormalized product name — stored directly so that
    // if the product is later renamed, the order history stays accurate.
    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    // quantity × unitPrice — calculated before saving
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    // Which warehouse this item ships from (wh1, wh2, wh3)
    @Column(length = 10)
    private String warehouse = "wh1";
}
