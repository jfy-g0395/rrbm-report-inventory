package rrbm_backend.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateOrderRequest {
    private String customerName;
    private String source; // WALK_IN, AGENT, ECOMMERCE, FACEBOOK_PAGE
    private String agentName;
    private String fbPage;
    private String ecommercePlatform; // SHOPEE, TIKTOK, LAZADA
    private String paymentMode;
    private BigDecimal discount;
    private String notes;
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {
        private Long productId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private String warehouse;
    }
}
