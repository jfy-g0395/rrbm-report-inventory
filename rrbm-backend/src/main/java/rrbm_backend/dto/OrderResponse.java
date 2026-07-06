package rrbm_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class OrderResponse {
    private String id;
    private String customerName;
    private String source;
    private String agentName;          // populated when source = AGENT / RESELLER / DISTRIBUTOR
    private String fbPage;             // populated when source = FACEBOOK_PAGE
    private String ecommercePlatform;  // SHOPEE, TIKTOK, LAZADA (when source = ECOMMERCE)
    private String paymentMode;
    private String paymentStatus;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal deliveryFee;
    private BigDecimal total;
    private String status;
    private String cancellationReason;   // null for non-cancelled orders
    private String notes;
    private String orderType;
    private String address;
    private LocalDateTime createdAt;
    private String createdByName;
    private List<OrderItemResponse> items;
    // Cancellation tracking — null for non-cancelled orders
    private LocalDateTime cancelledAt;
    private String cancelledByName;
    // Collection tracking — null for uncollected orders
    private OffsetDateTime collectedAt;
    private String collectedBy;
    // Refund tracking — non-null if at least one refund has been issued
    private OffsetDateTime refundedAt;

    // Void / cancel-for-replacement tracking (V39)
    // Running monetary total of all item-level voids; effective total = total - voidedAmount
    private BigDecimal voidedAmount;
    // Set on a cancelled-for-replacement order; points to the replacement order ID
    private String replacementOrderId;
    // Set on a replacement order; points back to the original cancelled order ID
    private String originalOrderId;
    // NULL | 'STANDARD' | 'REPLACEMENT' | 'VOIDED'
    private String cancellationType;

    // FK to agents.id — set when source = AGENT and an agent registry entry is linked (A1/A2)
    private Long agentId;

    // Import tracking (U1 — CSV upload pipeline)
    private boolean imported;
    private String importRef;

    // Deferred delivery (V93) — set only for SCHEDULED_DELIVERY / delivered scheduled orders.
    // Frontend derives the "overdue" flag from scheduledDeliveryDate < today; the change log
    // holds the created/rescheduled/delivered audit trail (reschedule count = its line count − 1).
    private LocalDate scheduledDeliveryDate;
    private OffsetDateTime deliveredAt;
    private String deliveryChangeLog;

    // Final-order confirmation gate (V95) — a scheduled order must be confirmed
    // before it can be delivered; editing its items clears this. The frontend
    // hides "Mark Delivered" until deliveryConfirmed is true.
    private boolean deliveryConfirmed;
    private OffsetDateTime deliveryConfirmedAt;

    @Data
    @AllArgsConstructor
    public static class OrderItemResponse {
        private Long id;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
        private String warehouse;
        // Cumulative units voided from this line; effective qty = quantity - voidedQuantity
        private Integer voidedQuantity;
        // A2 + U15: O.P. fields — null for non-agent orders
        private BigDecimal basePrice;
        private BigDecimal opRate;      // legacy — kept for backward compat
        private BigDecimal opPerUnit;   // U15: flat overprice per unit
        private BigDecimal opAmount;
    }
}
