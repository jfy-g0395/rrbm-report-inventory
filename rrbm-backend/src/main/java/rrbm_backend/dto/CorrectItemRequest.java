package rrbm_backend.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Request body for POST /api/orders/{id}/correct-item
 *
 * A standalone "wrong input" failsafe — swaps one recorded order item for the
 * correct product/quantity/price, reconciling inventory and sales. This is a
 * SEPARATE feature from return / replacement / void; it shares no request type
 * or endpoint with them.
 *
 * securityKey          — caller's personal admin security key (BCrypt); required.
 * reason               — free-text explanation recorded in the audit log; required.
 * orderItemId          — the recorded (wrong) order_items row to correct.
 * replacementProductId — catalog product to record instead.
 * replacementQty       — corrected quantity (> 0).
 * replacementUnitPrice — corrected unit price (> 0); asked every time so the
 *                        sales recording stays accurate even when the new item
 *                        carries a different price.
 * warehouse            — warehouse the replacement stock is deducted from
 *                        (wh1/wh2/wh3). The recorded item's stock is restored to
 *                        its own original warehouse automatically.
 *
 * All side-effects (stock restore + stock deduct + order mutation + ADJUSTMENT
 * ledger entry dated today + activity log) are written in one transaction.
 */
@Data
public class CorrectItemRequest {
    private String     securityKey;
    private String     reason;
    private Long       orderItemId;
    private Long       replacementProductId;
    private Integer    replacementQty;
    private BigDecimal replacementUnitPrice;
    private String     warehouse;
}
