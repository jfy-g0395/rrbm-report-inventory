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

    // Cumulative units voided from this line (V39).
    // The original quantity column is NEVER modified.
    // Effective quantity = quantity - voidedQuantity.
    @Column(name = "voided_quantity", nullable = false)
    private Integer voidedQuantity = 0;

    // A2 + U15: O.P. (Over Price) fields — nullable; only set for agent-linked orders.
    @Column(name = "base_price", precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "op_rate", precision = 5, scale = 4)
    private BigDecimal opRate;

    // U15: Flat per-unit overprice; commission = op_per_unit * qty.
    @Column(name = "op_per_unit", precision = 10, scale = 2)
    private BigDecimal opPerUnit;

    // base_price * op_rate * qty (legacy) OR op_per_unit * qty (current).
    @Column(name = "op_amount", precision = 10, scale = 2)
    private BigDecimal opAmount;
}
