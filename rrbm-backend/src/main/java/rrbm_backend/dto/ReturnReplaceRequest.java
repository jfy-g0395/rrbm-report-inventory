package rrbm_backend.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for POST /api/orders/{id}/return-replace — the unified Return / Replace flow.
 *
 * mode = RETURN (default): physical goods came back. Each returnItems entry voids that many
 * units off the original line (sellableQty restocked to restockWarehouse, rejectedQty scrapped);
 * the unreturned remainder stays a live sale. Optionally, replacementItems create ONE linked
 * replacement order (new SALE + stock deducted, linked via original_order_id — multiple over
 * time are allowed). refundOwed (≥ 0) records an excess payment to settle later via the Refund
 * button; no cash moves on this call.
 *
 * mode = CORRECTION is reserved for the data-only item swap folded in from the retired
 * "Correct Recorded Item" (implemented in a later step).
 *
 * securityKey — caller's personal admin security key (BCrypt); required.
 *
 * Per return line:  sellableQty + rejectedQty ≤ returnedQty ≤ remaining (qty − alreadyVoided);
 *                   returnedQty > 0; restockWarehouse required when sellableQty > 0.
 */
@Data
public class ReturnReplaceRequest {

    private String mode = "RETURN";   // RETURN | CORRECTION
    private String reason;
    private String securityKey;

    private List<ReturnLine> returnItems;
    private List<ReplacementLine> replacementItems;   // optional — creates a linked replacement order
    private BigDecimal refundOwed;                     // optional — excess payment to settle later (≥ 0)

    @Data
    public static class ReturnLine {
        private Long orderItemId;
        private Integer returnedQty;
        private Integer sellableQty;
        private Integer rejectedQty;
        private String restockWarehouse;   // required when sellableQty > 0
    }

    @Data
    public static class ReplacementLine {
        private Long productId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private String warehouse;
    }
}
