package rrbm_backend.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class CreateOrderRequest {
    private String customerName;
    private String source; // WALK_IN, AGENT, ECOMMERCE, FACEBOOK_PAGE, RESELLER, DISTRIBUTOR
    private String agentName;   // also used for reseller / distributor contact name
    private Long agentId;       // FK to agents.id — optional; triggers O.P. tracking when present
    private Long resellerId;    // FK to resellers.id — optional; set when source is RESELLER/DISTRIBUTOR
    private String fbPage;
    private String ecommercePlatform; // SHOPEE, TIKTOK, LAZADA
    private String paymentMode;
    private String orderType;   // STANDARD, PICK_UP, COD, DELIVERY
    private String address;     // optional delivery / pickup address
    private BigDecimal discount;
    private BigDecimal deliveryFee;
    private String notes;
    // Fix 5 — delivery coordination, used by edit-order-items on scheduled deliveries.
    private String deliveryDriver;
    private String deliveryHelpers;       // one helper name per line
    private String deliveryCoordinatedBy;
    private String deliveryNotes;
    // Deferred delivery (V93) — when present the order is created as
    // SCHEDULED_DELIVERY and records nothing until fulfilled on the delivery day.
    private LocalDate scheduledDeliveryDate;
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {
        private Long productId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private String warehouse;
        private BigDecimal basePrice;  // company base price per unit; optional
        private BigDecimal opRate;     // legacy — kept for backward compat
        private BigDecimal opPerUnit;  // U15: flat overprice per unit (e.g. 50.00); optional
    }
}
