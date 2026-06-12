package rrbm_backend.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for POST /api/orders/{id}/return
 *
 * securityKey   — caller's personal admin security key (BCrypt); required.
 * reason        — free-text explanation recorded in the activity log; required.
 * items         — one entry per order item being returned.  Items not listed
 *                 are assumed not returned on this call.
 * refundAmount  — optional.  Present and > 0 when the customer is receiving
 *                 money back.  The refund transaction and stock adjustments are
 *                 written atomically — if either fails, both roll back.
 *
 * Per-item contract:
 *   totalReturned  = sellableQty + rejectedQty  (validated on the server)
 *   totalReturned  ≤ item.quantity              (original qty ceiling)
 *   totalReturned  > 0
 *   sellableQty   ≥ 0
 *   rejectedQty   ≥ 0
 *
 * sellableQty > 0 → stock restored to originating warehouse; RETURN_SELLABLE
 *                   movement written with positive quantity.
 * rejectedQty > 0 → no stock change; RETURN_REJECTED movement written with the
 *                   actual rejected quantity (meaningful for waste/damage tracking).
 */
@Data
public class ReturnOrderRequest {

    private String securityKey;
    private String reason;
    private List<ReturnItemRequest> items;
    private BigDecimal refundAmount; // null or 0 = no refund

    @Data
    public static class ReturnItemRequest {
        /** Primary key of the order_items row being returned. */
        private Long orderItemId;

        /** Total physical units coming back from the customer. */
        private Integer totalReturned;

        /** Of the returned units: how many are in resaleable condition. */
        private Integer sellableQty;

        /** Of the returned units: how many are damaged / unrecoverable. */
        private Integer rejectedQty;

        /**
         * Destination warehouse for SELLABLE units.
         * Required when sellableQty > 0; ignored otherwise.
         * Must be one of: wh1, wh2, wh3.
         */
        private String restockWarehouse;
    }
}
