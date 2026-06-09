package rrbm_backend.dto;

import lombok.Data;
import java.util.List;

/**
 * Request body for POST /api/orders/{id}/cancel-for-replacement
 *
 * masterKey   — system master key; required (elevated auth, same key as Tier 2 void).
 * reason      — free-text explanation recorded in the activity log and on the order.
 * items       — per-item disposition list.
 *
 *   For DELIVERED orders: every item on the order must appear in this list with a
 *   disposition of 'SELLABLE' (stock restored) or 'REJECTED' (no stock restore;
 *   CANCEL_REJECTED movement written as audit trail).
 *
 *   For non-DELIVERED orders (ACTIVE / PENDING / PENDING_COLLECTION): this list
 *   is ignored — all items are automatically treated as SELLABLE because the goods
 *   were never physically dispatched.
 *
 * replacementOrderId is NOT set here.  It is written in the replacement-order
 * creation step (Step 5) once the replacement order actually exists.
 */
@Data
public class CancelForReplacementRequest {

    private String masterKey;
    private String reason;
    private List<CancelItemDisposition> items;

    @Data
    public static class CancelItemDisposition {
        /** Primary key of the order_items row. */
        private Long orderItemId;

        /**
         * 'SELLABLE' — goods coming back to warehouse stock.
         * 'REJECTED' — goods damaged / unrecoverable; no stock restore.
         *
         * Required for every order item when the parent order is DELIVERED.
         * Ignored for non-DELIVERED orders.
         */
        private String disposition;
    }
}
