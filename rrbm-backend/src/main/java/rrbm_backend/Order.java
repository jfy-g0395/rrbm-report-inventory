package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
    private String source; // WALK_IN, AGENT, ECOMMERCE, FACEBOOK_PAGE, RESELLER, DISTRIBUTOR
    
    @Column(name = "agent_name", length = 100)
    private String agentName;
    
    @Column(name = "fb_page", length = 100)
    private String fbPage;
    
    @Column(name = "ecommerce_platform", length = 30)
    private String ecommercePlatform; // SHOPEE, TIKTOK, LAZADA
    
    @Column(name = "payment_mode", length = 30, nullable = false)
    private String paymentMode = "CASH";

    @Column(name = "payment_status", length = 10)
    private String paymentStatus;
    
    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;
    
    @Column(precision = 10, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "delivery_fee", precision = 10, scale = 2, nullable = false)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal total = BigDecimal.ZERO;
    
    @Column(length = 20, nullable = false)
    private String status = "ACTIVE"; // ACTIVE, PENDING, CANCELLED, CLOSED, PENDING_COLLECTION
    
    @Column(columnDefinition = "TEXT")
    private String notes;

    // Fulfilment method: STANDARD, PICK_UP, COD, DELIVERY
    @Column(name = "order_type", length = 30)
    private String orderType = "STANDARD";

    // Optional delivery address / pickup location
    @Column(columnDefinition = "TEXT")
    private String address;

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

    // Collection tracking (V27)
    @Column(name = "pending_collection_at")
    private OffsetDateTime pendingCollectionAt;

    @Column(name = "collected_at")
    private OffsetDateTime collectedAt;

    @Column(name = "collected_by", length = 100)
    private String collectedBy;

    // Refund tracking — set when any refund is issued against this order
    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    // Void / cancel-for-replacement tracking (V39)
    // Running monetary total of all item-level voids applied to this order.
    // Effective order value = total - voidedAmount.
    @Column(name = "voided_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal voidedAmount = BigDecimal.ZERO;

    // Set on the cancelled original when a replacement order is created.
    // Points forward to the replacement order.
    @Column(name = "replacement_order_id", length = 20)
    private String replacementOrderId;

    // Set on the replacement order at creation.
    // Points back to the original cancelled order.
    @Column(name = "original_order_id", length = 20)
    private String originalOrderId;

    // NULL = not cancelled.
    // 'STANDARD'    = regular cancellation.
    // 'REPLACEMENT' = cancelled in order to be re-encoded as a replacement order.
    // 'VOIDED'      = Tier 2 full void — all items zeroed out via the void flow.
    @Column(name = "cancellation_type", length = 20)
    private String cancellationType;

    // FK to the registered agent (A1). Nullable — legacy orders keep agent_name as plain text.
    @Column(name = "agent_id")
    private Long agentId;

    // Import tracking (V59 — CSV upload U-series)
    @Column(name = "is_imported", nullable = false)
    private boolean imported = false;

    @Column(name = "import_ref", columnDefinition = "TEXT")
    private String importRef;

    // Late-import flag (V60) — true when order was backdated into an already-closed daily report
    @Column(name = "late_imported", nullable = false)
    private boolean lateImported = false;

    public boolean isLateImported() { return lateImported; }
    public void setLateImported(boolean lateImported) { this.lateImported = lateImported; }

    // Recording-only flag (V84) — true when imported for the record only:
    // inventory stock and cash-on-hand were intentionally NOT touched.
    @Column(name = "recording_only", nullable = false)
    private boolean recordingOnly = false;

    public boolean isRecordingOnly() { return recordingOnly; }
    public void setRecordingOnly(boolean recordingOnly) { this.recordingOnly = recordingOnly; }

    // One order has many items
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();
    
    @PrePersist
    protected void onCreate() {
        // Conditional: import pipeline pre-sets createdAt for backdating — skip when already set.
        // Live order creation never pre-sets this field, so behaviour is unchanged for all normal paths.
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
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
        
        // Calculate final total (subtotal - discount + deliveryFee)
        BigDecimal discountValue = discount != null ? discount : BigDecimal.ZERO;
        BigDecimal feeValue = deliveryFee != null ? deliveryFee : BigDecimal.ZERO;
        total = subtotal.subtract(discountValue).add(feeValue);
    }
}