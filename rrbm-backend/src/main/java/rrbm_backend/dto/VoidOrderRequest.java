package rrbm_backend.dto;

import lombok.Data;
import java.util.List;

/**
 * Request body for POST /api/orders/{id}/void
 *
 * items       — one entry per order item being voided; orderItemId identifies
 *               the specific order_items row, voidQuantity is how many units
 *               to remove, disposition is required for DELIVERED orders.
 * reason      — free-text explanation recorded in the activity log.
 * securityKey — personal admin security key; required for Tier 1 (partial void).
 * masterKey   — system master key; required for Tier 2 (all items reach zero).
 *
 * Exactly one of securityKey or masterKey should be present.  The backend
 * determines the tier from the resulting quantities and validates whichever
 * key is required.
 */
@Data
public class VoidOrderRequest {

    private List<VoidItemRequest> items;
    private String reason;
    private String securityKey;
    private String masterKey;

    @Data
    public static class VoidItemRequest {
        /** Primary key of the order_items row being voided. */
        private Long orderItemId;

        /** How many units to remove from this line. */
        private Integer voidQuantity;

        /**
         * 'SELLABLE' — goods are returning to warehouse stock.
         * 'REJECTED' — goods are damaged / unrecoverable; no stock restore.
         *
         * Required for every voided item when the parent order is DELIVERED.
         * Ignored (and always treated as SELLABLE for stock purposes) for
         * non-DELIVERED orders, since undelivered goods are still in the warehouse.
         */
        private String disposition;

        /**
         * Destination warehouse for restocked units.
         * Required when the line will restock: any line on a non-DELIVERED order,
         * or a DELIVERED order line with disposition SELLABLE.
         * Must be one of: wh1, wh2, wh3.
         */
        private String restockWarehouse;
    }
}
