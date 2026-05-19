package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "orders")
public class Order {
    
    @Id
    @Column(length = 20)
    private String id; // Format: DDMMYY-NNNNNN
    
    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;
    
    @Column(length = 30, nullable = false)
    private String source; // WALK_IN, AGENT, ECOMMERCE, FACEBOOK_PAGE
    
    @Column(name = "agent_name", length = 100)
    private String agentName;
    
    @Column(name = "fb_page", length = 100)
    private String fbPage;
    
    @Column(name = "ecommerce_platform", length = 30)
    private String ecommercePlatform; // SHOPEE, TIKTOK, LAZADA
    
    @Column(name = "payment_mode", length = 30, nullable = false)
    private String paymentMode = "CASH";
    
    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;
    
    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal total = BigDecimal.ZERO;
    
    @Column(length = 20, nullable = false)
    private String status = "ACTIVE"; // ACTIVE, PENDING, CANCELLED, CLOSED
    
    @Column(columnDefinition = "TEXT")
    private String notes;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;
    
    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;
    
    // One order has many items
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    // Helper method to add item to order
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
    
    // Helper method to calculate totals
    public void calculateTotals() {
        // First, ensure all items have their subtotals calculated
        items.forEach(item -> {
            if (item.getSubtotal() == null && item.getQuantity() != null && item.getUnitPrice() != null) {
                item.setSubtotal(item.getUnitPrice().multiply(new BigDecimal(item.getQuantity())));
            }
        });
        
        // Sum up all item subtotals
        subtotal = items.stream()
            .map(item -> item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Calculate final total (subtotal - discount)
        BigDecimal discountValue = discount != null ? discount : BigDecimal.ZERO;
        total = subtotal.subtract(discountValue);
    }
}