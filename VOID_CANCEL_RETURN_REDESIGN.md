# RRBM — Void, Cancel, and Return Flow Redesign
**Status:** COMPLETE — all 12 steps built and integration tested (Jun 3, 2026)  
**Authored:** Jun 1, 2026  
**Build started:** Jun 2, 2026  
**Reference:** See RRBM-BUILD-LOG.md NEXT UP section for the pending void action on Order List

---

## Section 1 — Business Logic Definitions

Three distinct operations exist in the system. They are not interchangeable and must be treated as separate flows with separate permissions, separate UI entry points, and separate backend endpoints.

---

### 1.1 Void

A void is the removal of one or more specific items from an order, or the reduction of a specific item's quantity, while the order still has remaining value or while the entire order is being zeroed out by explicit admin action.

**Rules:**
- A void is only available on orders that were created within the current business day, before the daily report for that day is closed. Once the daily report is closed, the void option is gone entirely. There is no post-close void.
- A void is available on orders regardless of their current status — including orders already marked as DELIVERED — because damaged or wrong items may be discovered after the delivery has been recorded.
- When voiding items from a delivered order, the admin must specify for each voided item whether the physical item is sellable (will be returned to warehouse inventory) or rejected/damaged (will not be returned to inventory and must be recorded separately).
- A void has two tiers based on scope:
  - **Tier 1 — Partial void:** reducing the quantity of one or more items but leaving the order with remaining non-zero value. Requires a standard security key.
  - **Tier 2 — Full void:** zeroing out every item on the order so the order total reaches zero. Requires a master key. The system must also collect a disposition (sellable or rejected) for every item before submission is allowed. After a Tier 2 void is confirmed, the order status is set to CANCELLED and `cancellation_type` is set to `VOIDED`. This keeps the order visually closed in the order list while preserving a clear programmatic distinction from a standard cancellation (`STANDARD`) or a replacement cancellation (`REPLACEMENT`).
- After a void, the original quantities on the order are preserved and visible. The voided quantities are stored separately. The effective order total is reduced by the value of the voided items.
- All void actions must be attributed in the activity log to the identity of the authenticated session user, not a name taken from a form field.

---

### 1.2 Cancel

A cancellation removes the entire order from active fulfillment. There are two cancellation scenarios with different requirements.

**Standard cancellation:**
- The entire order is voided because it is no longer needed. The cancellation reason is recorded. Stock disposition is handled the same way as the void flow (sellable vs rejected question applies). A standard security key is required.

**Cancellation for replacement:**
- The order is being cancelled because the items need to be re-encoded, replaced, or corrected. This requires a master key and a higher level of authorization.
- The cancellation reason must be recorded as replacement-related.
- The original order must store a reference to the replacement order that is created.
- The replacement order must store a reference back to the original cancelled order.
- If only some items on a multi-item order need replacement, those specific items are voided using the void flow rather than cancelling the entire order. A new replacement order is then created for those specific items only. The voided items on the original order store a reference to the replacement order.
- **Build note:** Per-item references on `order_items` pointing to a replacement order were not implemented. Two-way linking exists at the order level only (`replacement_order_id` on the original, `original_order_id` on the replacement). The order-level link is sufficient for all current display and navigation requirements.

---

### 1.3 Return and Adjustment

A return is a post-sale operation that records goods physically coming back from a customer. It is not a same-day operation and has no day-close restriction.

**Rules:**
- Available at any time regardless of when the order was placed or whether the daily report is closed.
- Admin only. Requires a security key.
- The admin specifies which items are being returned and, for each item, how many units are sellable and how many are rejected/damaged. The sellable and rejected counts must add up to the total quantity returned for that item.
- Sellable returned items: stock is restored to the correct warehouse. A movement record is written.
- Rejected/damaged returned items: stock is not restored to warehouse inventory. A movement record is written for the rejection, but the warehouse stock count does not increase.
- A refund is optional and separate from the stock adjustment. If the customer is receiving a refund, the financial transaction and the stock adjustment are written together in the same atomic operation. If either fails, both roll back.
- If the customer wants a replacement item instead of a refund, a linked replacement order is created as a separate action.

---

## Section 2 — Permission Matrix

| Action | Who can perform | Authorization required |
|--------|----------------|----------------------|
| Reduce item quantities (partial void, Tier 1) | Staff with order management permission | Standard security key |
| Zero out all items on an order (full void, Tier 2) | Admin | Master key + disposition per item |
| Cancel an order for replacement | Admin | Master key |
| Standard order cancellation | Admin | Security key |
| Process a return or adjustment | Admin only | Security key |
| Create a replacement order | Any staff with order processing authority | Standard login (existing session) |

---

## Section 3 — Void Flow Full Specification

### Availability
- The void option appears only on orders created on the current business day.
- If the daily report for today has been closed, the void option is hidden entirely on all orders. There is no override or bypass.
- The void option is available on orders in any status, including DELIVERED.

### Delivered-order voiding
- When an admin voids an item from an order that is already in DELIVERED status, the system requires a stock disposition for every item being voided.
- Disposition choices: **sellable** (restore to the correct warehouse) or **rejected** (record the return but do not restore inventory).
- An admin cannot submit the void on a delivered order until every voided item has a disposition selected.

### Tier classification — determined by the result, not by the admin's intent
- After the admin enters all quantities, the system checks whether the result is a partial removal (some items or quantity remain) or a full removal (all quantities reach zero).
- If partial: the form uses the standard security key field.
- If all items reach zero: the security key field is replaced by a master key field, and a disposition selector appears for every item. The submit button remains locked until the master key is provided and every item has a disposition.
- The system does not auto-cancel the order silently when all quantities are zeroed. The admin must explicitly confirm the full void and provide the master key.

### What is recorded when a void is submitted
- The voided quantity is recorded per line item, separate from the original quantity. The original quantity is never overwritten.
- The order total is reduced by the monetary value of the voided items (voided quantity × unit price per item).
- A financial transaction is written to the ledger for the voided value (negative amount, effective date = today).
- An inventory movement record is written for each voided item, indicating the item, the quantity, the warehouse, and the disposition (for delivered orders). The movement type for item-level voiding is `ITEM_VOID`.
- The activity log entry uses the identity of the authenticated session user.

---

## Section 4 — Cancel Flow Full Specification

### Cancellation for replacement — single-item order
- Requires a master key.
- The cancellation reason is recorded. The reason field already exists on the orders table (`cancellationReason`). No new field needed for the reason itself, but the system should flag or categorize the reason as replacement-related so it can be distinguished from a standard cancellation.
- The original order stores a reference to the new replacement order ID once the replacement is created.
- The replacement order stores a reference back to the original cancelled order ID.

### Cancellation for replacement — multi-item order
- If only specific items need replacement, do not cancel the entire order. Use the void flow to zero out those specific items on the original order. Create a new replacement order for those items only.
- The voided items on the original order carry a reference to the replacement order they were replaced by.
- This keeps the non-replaced items on the original order intact and traceable.

### Stock disposition on cancellation
- The same sellable vs rejected question from the void flow applies here. When cancelling an order, the admin specifies for each item whether the physical goods are sellable (restore to inventory) or rejected (record without restoring).
- For orders never fulfilled (never delivered), all cancelled items are typically sellable returns unless the admin specifies otherwise.

### Activity log
- All cancellation actions are attributed to the authenticated session identity. Actor name is never taken from a form field.

---

## Section 5 — Return and Adjustment Flow Full Specification

### Access
- Available at any time. No day-close restriction.
- Admin only. Security key required.

### What the admin enters
- A list of the original order items is shown.
- For each item being returned, the admin enters:
  - Total quantity being returned for that item
  - Of that total: how many units are sellable
  - Of that total: how many units are rejected/damaged
- The sellable and rejected quantities must equal the total returned quantity. The system validates this and blocks submission if they do not match.
- Items not being returned are left unchanged — the admin only fills in quantities for items that are physically coming back.

### Sellable returned items
- The quantity is restored to the warehouse that the item originally shipped from.
- A movement record is written to the inventory log using movement type `RETURN_SELLABLE`.
- The stock count on the product increases for the correct warehouse.

### Rejected/damaged returned items
- The quantity is NOT added to warehouse inventory.
- A movement record is written to the inventory log using movement type `RETURN_REJECTED`. The actual rejected quantity is recorded (not zero) because rejected units physically arrived at the warehouse and the count is meaningful for waste and damage tracking.
- The stock count on the product is not touched. The movement record alone is the audit trail for the rejection.

### Refund handling
- A refund toggle appears at the bottom of the return form.
- If the admin enables the refund toggle, the refund amount field appears.
- The refund financial transaction and the stock adjustments are written in the same atomic database operation. If the refund write fails, the stock adjustment also rolls back. If the stock adjustment fails, the refund also rolls back. The user receives a clear error message if either fails.
- If the customer is not receiving a refund, the toggle is left off and only the stock adjustment is written.

### Replacement option
- If the customer wants the same item re-sent, no refund is issued. The replacement order is created separately.
- If the customer wants a different item, the admin can initiate a linked replacement order from within the return flow. The new order is pre-populated with the customer's details and the replacement item. See Section 6.

### Activity log
- All return and adjustment actions are attributed to the authenticated session identity.

---

## Section 6 — Replacement Order Flow Full Specification

### Who creates it
- Any staff member with order processing authority (standard login is sufficient; no elevated key required to create the order itself).

### Pre-population
- The replacement order form is pre-populated from the original order:
  - Customer name
  - Contact details (if stored on the order)
  - Delivery address (if applicable)
  - Order type and source channel
- The staff member adjusts or confirms the item and quantity being replaced, then submits.
- The replacement order is a full new order in the system, not a clone or edit of the original.

### No price difference tracking
- The system does not calculate or track the monetary difference between the original item and the replacement item.
- If the replacement item costs more than the original, a new order is created at the full new price and payment is handled outside the system.
- If the replacement item costs less, the difference is handled through the return flow as a refund — separate operation, not automatic.

### Two-way linking
- The original order (or the cancelled order) stores a reference to the replacement order ID.
- The replacement order stores a reference to the original order ID.
- Both references are displayed in the UI as clickable navigation links so staff can move between the two orders in one click.
- The existing order ID format (`DDMMYY-NNNNNN`) is used for all display and linking. No new identifier format is introduced.

### Activity log
- Replacement order creation is logged under the authenticated session identity.

---

## Section 7 — UI and UX Specifications

> This section describes requirements only. No code or design files are included. These are frontend requirements to be built during the build phase.

---

### 7.1 Order List Status Badges

The following badge states are needed in the order list view:

| State | Badge requirement |
|-------|------------------|
| Standard cancelled order | Existing CANCELLED badge — no change needed |
| Cancelled for replacement | New distinct badge indicating the order was replaced. Must include a clickable link that navigates directly to the replacement order using its existing order ID. |
| Active order with voided items | A badge or indicator that some items have been partially removed, but the order is still active. |
| Replacement order | A badge indicating this is a replacement order. Must include a clickable link that navigates back to the original order using its existing order ID. |
| Fully voided order (Tier 2) | A distinct **Fully Voided** badge for orders where `cancellation_type = 'VOIDED'`. Visually different from the standard CANCELLED badge to make clear this was an item-level void, not an operator cancellation. |

---

### 7.2 Order Detail View

**For orders with voided items:**
- Each line item shows three values clearly: the original quantity as ordered, the voided quantity that was removed, and the remaining active quantity.
- These are three separate visible numbers, not just a single updated quantity that hides the history.

**For cancelled-and-replaced orders:**
- A clearly visible notice at the top or prominent position of the order detail that this order was cancelled and replaced.
- A direct clickable link to the replacement order using the system's existing order ID format.
- The notice must be immediately visible, not buried in a log or notes field.

**For replacement orders:**
- A clearly visible notice at the top or prominent position that this is a replacement order.
- A direct clickable link back to the original order using the system's existing order ID format.
- Same visibility requirement — immediately visible, not hidden in a log.

---

### 7.3 Void Modal

The void modal is triggered from the order detail view or order list action button for same-day orders only.

**Content:**
- A list of all items on the order
- Per item: a quantity input field (pre-filled with the current quantity) that the admin adjusts downward
- A running total showing the new order value as quantities are adjusted, updating live as the admin makes changes
- A reason field

**Tier 2 behavior (all items reach zero):**
- The standard security key field is hidden and replaced by a master key field
- A disposition selector appears next to each item asking whether the item is sellable or rejected
- The submit button is disabled until the master key is entered and every item has a disposition selected
- If the admin reduces quantities back above zero (partial void), the form reverts to Tier 1 behavior

---

### 7.4 Return Modal

The return modal is triggered from the order detail view on any order, any time, by an admin.

**Content:**
- A list of the original order items
- Per item:
  - Total returned quantity input
  - Sellable quantity input
  - Rejected quantity input
  - Live validation indicator confirming that sellable + rejected = total returned, highlighted in error until all three are consistent
- A refund toggle at the bottom of the modal. When enabled, a refund amount field appears.
- An option to create a replacement order. When selected, the replacement order form pre-populates with the customer's details and the replacement item.

---

## Section 8 — Schema Changes Required

> No migration code is written here. The following describes what needs to be added or changed and why.

---

### 8.1 order_items table

A new column is needed to record how many units of each line item were voided. This column stores the cumulative voided quantity per line. The original `quantity` column is never modified — it remains the permanent record of what was ordered. The effective quantity for a line is derived as `quantity - voided_quantity`. Column name: `voided_quantity` (added in V39).

The existing `CHECK (quantity > 0)` constraint on the `quantity` column must remain unchanged because the original quantity is never touched. No constraint change is needed here.

---

### 8.2 orders table

The following new fields are needed on the `orders` table:

1. **Total voided amount** — a running total of the monetary value that has been removed from the order through voids. Updated when any void is applied. The effective order value is `total - voided_amount`. Column name: `voided_amount` (added in V39).

2. **Reference to replacement order** — stores the order ID of the replacement order created after a cancellation-for-replacement. Nullable. Column name: `replacement_order_id` (added in V39).

3. **Reference to original order** — stores the order ID of the order this one was created to replace. Nullable. Column name: `original_order_id` (added in V39).

4. **Cancellation type** — distinguishes how the order was cancelled. Column name: `cancellation_type` (added in V39). Values: `NULL` = not cancelled, `STANDARD` = regular cancellation, `REPLACEMENT` = cancelled for replacement, `VOIDED` = Tier 2 full item void. This resolved the approach question below.

**Existing field note:** The `cancellationReason` column already exists on the `orders` table. The approach for distinguishing cancellation types was resolved by the `cancellation_type` column described above (item 4).

---

### 8.3 inventory_movements movement type constraint

The `chk_movement_type` constraint currently allows (after V37 migration): `ORDER_OUT`, `CANCELLED_RETURN`, `MANUAL_ADJUST`, `RESTOCK`, `TRANSFER`, `REFUND_RETURN`, `VOID_RETURN`.

The following movement types were added in V40:

| Purpose | Movement type |
|---------|--------------|
| Item-level same-day void — stock restored to warehouse | `ITEM_VOID` |
| Cancel-for-replacement — rejected disposition, no stock restore | `CANCEL_REJECTED` |
| Customer return — sellable goods restored to warehouse | `RETURN_SELLABLE` |
| Customer return — rejected/damaged goods, no stock change | `RETURN_REJECTED` |

**Reconciliation with existing types:**

- `REFUND_RETURN` (added in V37): kept in the constraint. The old refund endpoint that uses it is still active during the frontend build period. Will be reviewed for retirement when the old endpoint is removed (see Section 9).
- `VOID_RETURN` (added in V37): kept in the constraint. Was added for the post-close void stock restore path. No new code writes `VOID_RETURN` movements — it exists only for historical records. Will be reviewed for retirement in the same pass as the old void endpoint removal.

---

## Section 9 — What Is Being Removed

### POST /api/transactions/void

**REMOVED — Session 62, Jun 3, 2026.**

**Why it is being removed:**
- It was built as a post-close monetary adjustment — a financial-only operation that reduced a ledger amount but had no principled connection to physical inventory.
- It had no basis in the actual business logic of the company. The company has no concept of a post-close monetary void. What they have is: (a) same-day item removal via the new void flow, and (b) post-sale returns via the new return and adjustment flow.
- The inventory restore logic it contained was proportional to the monetary amount entered, which was completely wrong — it assumed that monetary fraction maps to physical stock fraction, which it does not.
- It is fully replaced by the void flow (Section 3) and the return/adjustment flow (Section 5) described in this document.

All backend and frontend references must be removed together in the final cleanup step.

---

## Section 10 — Build Sequence and Progress

> ✅ = backend complete and API-tested | ⚠️ = partial (see note) | 🔲 = not yet started

1. ✅ **Schema migrations** — V39 (voided_quantity on order_items; voided_amount, replacement_order_id, original_order_id, cancellation_type on orders) and V40 (expand chk_movement_type to include ITEM_VOID, RETURN_SELLABLE, RETURN_REJECTED, CANCEL_REJECTED). Applied and verified via psql Jun 2, 2026.

2. ✅ **Backend void endpoint** — `POST /api/orders/{id}/void`. Tier 1 (security key, partial) and Tier 2 (master key, full zero-out → CANCELLED + cancellation_type = VOIDED). Writes per-item voided_quantity, ITEM_VOID movement records, VOID ledger entry, activity log. All 11 test cases passed Jun 2, 2026.
   - ⚠️ **T11 (day-close guard) — code-verified only.** The guard (`closedAt != null` on today's DailyReport → 400) is present and correct in the code, but could not be runtime-tested because the existing `close-daily` endpoint correctly returned 409 Conflict due to an active unfulfilled order being present during testing. **Retest T11 during final cross-flow integration testing (Step 12) when we have full control over order states.**

3. ✅ **Backend cancel for replacement endpoint** — `POST /api/orders/{id}/cancel-for-replacement`. Master key required. Per-item SELLABLE/REJECTED disposition for DELIVERED orders (auto-SELLABLE for non-DELIVERED). Writes CANCELLED_RETURN or CANCEL_REJECTED movement records, VOID ledger entry for remaining effective value (order.total − order.voidedAmount), cancellationType = REPLACEMENT. SET product support included. All 12 test cases + ledger fix retest passed Jun 2, 2026.
   - `cancellationType = STANDARD` added to existing `cancelOrder()` in the same pass.
   - `recordVoid(Order, BigDecimal, Long, String)` overload added to TransactionService so the cancel-for-replacement ledger entry uses the effective remaining amount, not the original total. Original `recordVoid(Order, Long, String)` unchanged.

4. ✅ **Backend return and adjustment endpoint** — `POST /api/orders/{id}/return`. Admin security key required. Per-item sellableQty + rejectedQty routing, with server-side validation that they sum to totalReturned. RETURN_SELLABLE movements restore stock (SET product decomposition supported); RETURN_REJECTED movements write actual qty as audit trail (no stock change). Optional refundAmount: atomic with stock adjustments, writes RETURN ledger entry. refundedAt set on first refund only; multiple returns on same order accumulate correctly in daily report. No date or day-close restriction. All 13 tests + probe passed Jun 2, 2026. No open findings.

5. ✅ **Replacement order creation with pre-population** — `POST /api/orders/{id}/replacement`. Standard JWT only (no elevated key). Guards: original must exist (404), cancellationType must be REPLACEMENT (400), replacementOrderId must be null to prevent duplicate replacement (400). Atomic single transaction: new order save + stock deduction + SALE ledger entry + write-back of replacementOrderId to original. originalOrderId set on new order at creation; replacementOrderId written back to original in same transaction. All downstream effects identical to regular order (ORDER_OUT movement, SALE ledger, same-day inclusion). Activity log uses CREATE_REPLACEMENT_ORDER (not CREATE_ORDER). All 8 test scenarios + 3 DB confirmations passed Jun 2, 2026. No open findings.

6. ✅ **Frontend void modal** — Tier 1 / Tier 2 switching, running total, master key gate, disposition selectors. Built and tested V1/V2/V3 (Session 60).

7. ✅ **Frontend cancel flow** — standard cancel vs cancel-for-replacement UI, master key gate. Built and tested C1/C2/C3 (Session 61).

8. ✅ **Frontend return modal** — item list, sellable/rejected inputs, live validation, refund toggle, replacement option. Built and tested R1/R2/R3 (Session 61).

9. ✅ **Frontend replacement order form** — pre-populated from original, confirmation before submit. Built and tested P1 (Session 61).

10. ✅ **Order list badges and status indicators** — four badge states per Section 7.1. All 8 today's orders verified (Session 62).

11. ✅ **Order detail view linking and navigation** — two-way links, voided item display per Section 7.2. 3-column void layout + CFR amber + replacement purple banners verified (Session 62).

12. ✅ **Full end-to-end testing per flow** — T11 day-close guard; V1/V2/V3 void; C1/C2/C3 cancel; R1/R2/R3 return; P1 replacement. **11/11 tests passed — Jun 3, 2026** (Sessions 60–62).

**POST /api/transactions/void** and **`recordPostCloseVoid()`** removed — Session 62, Jun 3, 2026. `node --check` and `mvn compile` both clean.

---

*Design finalized Jun 1, 2026. Build complete Jun 3, 2026.*

---

## Post-QA Backlog

Items identified during this redesign. Either deferred intentionally or confirmed resolved during Step 12 audit.

---

### ✅ RESOLVED — Void button on Order List

**Originally noted:** The build log referenced `askVoid()` as the function wired to the void button in `renderOrderRows`. That reference was outdated — `askVoid()` is from the old pre-redesign void flow and no longer exists.

**Confirmed during Step 12 audit (Jun 3, 2026):** The void button calls `openItemVoidModal()` (the new item-level void built in this redesign) and is present on all three applicable statuses in `renderOrderRows`:

| Status | Void button present | Condition |
|--------|-------------------|-----------|
| ACTIVE | ✅ line 533 | `canManageOrders() && !appState.dailyClosed` |
| PENDING | ✅ line 538 | same |
| DELIVERED | ✅ line 544 | `canManageOrders() && !appState.dailyClosed` |
| CANCELLED | — intentionally absent | no void on cancelled orders |
| PENDING_COLLECTION | — intentionally absent | collection-only action in this view |

No action required. This item is closed.

---

### 🕐 LOW PRIORITY — Orphaned endpoints cleanup

Two endpoints exist in `OrderController.java` that are not called by any frontend view:

- `GET /api/orders` — returns the full orders table with no pagination. Unusable for any real data volume. Needs pagination before it can be wired to a UI.
- `GET /api/orders/search` — cross-all-time customer name search. Could replace the client-side `filterOrderHistory()` to support larger datasets, but requires frontend wiring.

**Do not remove these now.** Removing unused endpoints without confirming nothing calls them is a risk. Leave for a dedicated cleanup pass. Options when that pass happens: (a) add pagination + wire to a future admin/search UI, (b) wire `/search` to replace client-side Order History filtering, (c) document and leave as debug/Postman endpoints.

---

### ✅ RESOLVED — M-26: Phantom-debit on cancel/defer/collect paths

**Jun 5, 2026 (Session 68)** — Fixed as a 3-site net-basis correction in `TransactionService.java` and `OrderService.java`. Root cause was broader than the original double-VOID diagnosis: all three ledger-write paths (`cancelOrder`, `recordDeferralVoid`, `recordCollectionSale`) used gross `order.getTotal()` instead of net `total − voidedAmount`. Full details in RRBM-BUILD-LOG.md Session 68.

---

### ❌ CLOSED — V47: Drop `item_code` from `products` and `po_items`

**Jun 5, 2026** — V47 reservation closed. The drop is not executable without removing live dependencies:

- **`po_items.item_code`** — still the **Attempt 1** match key in the 3-attempt DR→PO reconciliation chain in `ProductController.processDelivery()` and `DeliveryReportController.findPoItemForDrItem()`. Dropping it would break the primary PO match path.
- **`products.item_code`** — core inventory infrastructure used throughout the system (inventory table display, CSV imports, PO matching). Not a candidate for removal.

**V49** is the next available migration number. V47 is permanently unassigned.
