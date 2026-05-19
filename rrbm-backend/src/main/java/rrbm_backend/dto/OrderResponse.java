package rrbm_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class OrderResponse {
    private String id;
    private String customerName;
    private String source;
    private String agentName;          // populated when source = AGENT
    private String fbPage;             // populated when source = FACEBOOK_PAGE
    private String ecommercePlatform;  // SHOPEE, TIKTOK, LAZADA (when source = ECOMMERCE)
    private String paymentMode;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private String status;
    private String notes;
    private LocalDateTime createdAt;
    private String createdByName;
    private List<OrderItemResponse> items;

    @Data
    @AllArgsConstructor
    public static class OrderItemResponse {
        private Long id;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
        private String warehouse;
    }
}
