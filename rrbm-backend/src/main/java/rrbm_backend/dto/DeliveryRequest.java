package rrbm_backend.dto;

import lombok.Data;
import java.util.List;

/**
 * Request payload for processing a delivery receipt.
 * It contains the receipt identifier, personnel details, optional notes, and a list of items
 * that should be added to inventory.
 */
@Data
public class DeliveryRequest {
    private String receiptNumber;   // 6‑7 alphanumeric characters
    private String receiverName;    // Person who received the delivery
    private String verifierName;    // Person who verified the delivery
    private String notes;           // Optional remarks (e.g., damaged items)
    private List<DeliveryItem> items;

    @Data
    public static class DeliveryItem {
        private Long productId;
        private Integer quantity;
        private String warehouse; // "WH1", "WH2", or "WH3"
    }
}
