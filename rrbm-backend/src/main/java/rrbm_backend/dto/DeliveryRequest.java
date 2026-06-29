package rrbm_backend.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request payload for processing a delivery receipt.
 * It contains the receipt identifier, personnel details, optional notes, and a list of items
 * that should be added to inventory.
 */
@Data
public class DeliveryRequest {
    private String receiptNumber;   // Supplier DR number (2–20 chars, letters/numbers/hyphens)
    private String supplierName;    // Name of the supplying vendor
    private String receiverName;    // Person who received the delivery
    private String verifierName;    // Person who verified the delivery
    private String encodedByName;   // Logged-in admin who encoded the receipt
    private String notes;           // Optional remarks (e.g., damaged items)
    private String poNumber;        // Optional — links this DR to a specific Purchase Order
    private String truckPlate;      // Optional — delivering truck's plate number
    private String driverName;      // Optional — delivery driver's name
    private List<DeliveryItem> items;

    @Data
    public static class DeliveryItem {
        private Long productId;
        private Integer quantity;
        private Integer received;     // total units physically received (for accounting)
        private Integer rejected;     // units rejected/returned
        private String warehouse;     // "wh1", "wh2", or "wh3"
        private BigDecimal unitCost;  // actual invoice cost — overrides stored product cost for payable calc
        private Long poItemId;        // optional — the exact PO line this row fulfils (per-line PO tagging)
        private String poNumber;      // optional — fallback when only the PO (not the exact line) is known
    }
}
