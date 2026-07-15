package rrbm_backend;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One record per Return / Replace action taken on an order through the unified flow.
 *
 * <p>event_type distinguishes a physical RETURN (goods came back → returned units are
 * voided off the original, sellable stock restored, a refund may be owed, and a linked
 * replacement order may be created) from a data-only CORRECTION (wrong item recorded,
 * nothing physically returned → the line is swapped and stock corrected, no refund).
 *
 * <p>Refund is recorded but NOT paid here: refundOwed/refundStatus back the "To Refund"
 * tab on the Collections page; cash only moves when staff press the Refund button.
 *
 * <p>Multiple replacements over time are simply multiple rows (linked 1-to-many to the
 * original via {@link Order#getOriginalOrderId()} on each replacement order).
 */
@Entity
@Table(name = "return_events")
public class ReturnEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", length = 20, nullable = false)
    private String orderId;

    @Column(name = "event_type", length = 20, nullable = false)
    private String eventType;   // RETURN | CORRECTION

    @Column(length = 500)
    private String reason;

    @Column(name = "refund_owed", precision = 15, scale = 5, nullable = false)
    private BigDecimal refundOwed = BigDecimal.ZERO;

    @Column(name = "refund_status", length = 10, nullable = false)
    private String refundStatus = "NONE";   // NONE | OWED | REFUNDED

    @Column(name = "refunded_amount", precision = 15, scale = 5)
    private BigDecimal refundedAmount;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "replacement_order_id", length = 20)
    private String replacementOrderId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_by_name", length = 120)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "returnEvent", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = true)
    private List<ReturnEventItem> items = new ArrayList<>();

    public void addItem(ReturnEventItem item) {
        item.setReturnEvent(this);
        this.items.add(item);
    }

    // ── Getters / setters ────────────────────────────────────────────────────
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public BigDecimal getRefundOwed() { return refundOwed; }
    public void setRefundOwed(BigDecimal refundOwed) { this.refundOwed = refundOwed; }
    public String getRefundStatus() { return refundStatus; }
    public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    public LocalDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(LocalDateTime refundedAt) { this.refundedAt = refundedAt; }
    public String getReplacementOrderId() { return replacementOrderId; }
    public void setReplacementOrderId(String replacementOrderId) { this.replacementOrderId = replacementOrderId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<ReturnEventItem> getItems() { return items; }
    public void setItems(List<ReturnEventItem> items) { this.items = items; }
}
