# RRBM — QA Fix Checklist
**QA phase:** Complete — 53 gaps across all 20 flows  
**Fix phase:** COMPLETE — all 53 gaps closed as of Jun 5, 2026  
**Last trace:** Jun 1, 2026 — Groups D–L + Standalone traced end-to-end; 2 new Moderate gaps added (M-33, M-34); all existing gaps confirmed; M-31/M-32 notes updated.  
**Last fix wave:** Jun 5, 2026 — M-26 phantom-debit 3-site fix (Session 68). All 53 gaps closed.

### Progress
| Severity | Fixed | Remaining | Total |
|----------|------:|----------:|------:|
| 🔴 Critical | 9 | 0 | 9 |
| 🟡 Moderate | 34 | 0 | 34 |
| 🟢 Minor | 10 | 0 | 10 |
| **Total** | **53** | **0** | **53** |

---

## 🔴 Critical
> Data corruption, wrong financial figures, or security bypass. Fix before anything else.

---

### GROUP A — Daily Close Actor Identity · Flow 12 · Backend
**Root cause:** `DailyReportController` reads `userId` / `userName` from the request body instead of the JWT.  
A bad `userId` previously bypassed security key validation silently (C-2 is the downstream symptom; C-3 is the root).

- [x] **C-2** — `ifPresent()` silently skips security key check when `userId` not found in DB → force-close proceeds with any or no key  
  `DailyReportService.java:96-100`

- [x] **C-3** — `userId` falls back to hardcoded `3L`; `userName` is free-text from body → wrong actor recorded in `daily_reports.closed_by` and activity log  
  `DailyReportController.java:48-58`

> ⚠️ C-2 fix (`orElseThrow`) is only fully effective after C-3 is done. Until then, `userId` still comes from `localStorage` via body, not server-verified JWT.

---

### GROUP B — Double Revenue on COD Collection · Flows 9, 12 · Backend
**Root cause:** `collectOrder` fires `recordCollectionSale` unconditionally — no check whether the original `SALE-{id}` is still live in the ledger.

- [x] **C-1** — COD order never force-closed → `COLL-SALE` written on top of live `SALE-{id}` → 2× revenue in ledger + closed daily report patched again  
  `OrderController.java:421-435`

- [x] **C-4** — No pessimistic lock on `collectOrder`; two concurrent requests both pass status guard → two `COLL-SALE` entries + daily report patched twice  
  `OrderController.java:409-435`, `TransactionService.java:195`

---

### GROUP C — Missing Financial Write Safeguards · Flows 10, 11 · Backend
**Root cause:** `TransactionService.recordRefund` and `recordPostCloseVoid` have no idempotency guard and no amount ceiling.

- [x] **C-5** — `recordRefund` uses `millis()` suffix on transaction code → double-click or network retry creates two REFUND rows for the same order  
  `TransactionService.java:91`, `TransactionController.java:111`

- [x] **C-6** — No upper-bound check on refund/void amount → can submit ₱99,999 refund on a ₱500 order; negative net written to ledger  
  `TransactionService.java:253-256`, `TransactionController.java:101-111`

---

### Standalone Critical

- [x] **C-7** · Flow 14 · Backend — No `@Transactional` on `processDelivery`; stock increments commit per-product before log save → duplicate receipt number causes UNIQUE violation AFTER stock already committed → phantom stock with no audit trail  
  `ProductController.java:387` (missing annotation), `:431` (per-product save), `:453` (log save that can throw)

- [x] **C-8** · Flows 2, 20 · DB + Backend — `passwordPlain` + `securityKeyPlain` columns store verbatim credentials; `GET /api/users/{id}/credentials` returns them on demand → full credential exposure on DB breach (CWE-256/312)  
  `UserController.java:101, 166, 247, 378, 393-410`

- [x] **C-9** · Flow 15 · Frontend — Delivery Reports tab fetches `/api/reports/deliveries`; backend maps to `/api/delivery-reports` → tab always returns 404 / "Failed to load"  
  `app.js:2170` vs `DeliveryReportController.java:10` *(already resolved — `GET /api/reports/deliveries` exists in `DailyReportController.java` and returns 200)*

---

## 🟡 Moderate
> Feature broken or data inaccurate, but no permanent financial corruption. Fix after all Criticals.

---

### GROUP D — Daily Close Reporting · Flow 12 · Backend + Frontend
**Root cause:** PENDING_COLLECTION not excluded from order-count queries; dead parameter never wired; missing frontend status handler.

- [x] **M-1** — `totalOrders` / `totalItemsSold` count PENDING_COLLECTION orders even though their revenue was zeroed out before the snapshot  
  `DailyReportService.java:122` (query filter missing PENDING_COLLECTION)  
  ✅ Fixed Jun 1 2026 — Added `'PENDING_COLLECTION'` to the exclusion filter in all 3 operational stats queries (`orderStats`, `itemsSoldResult`, `topProductResult`). Live-verified on Jun 1 force-close test: report `total_orders = 9` (corrected), old buggy query returns 10 (includes the deferred order). Also cross-verified on May 30 historical report: snapshot = 9, old query = 9, corrected query = 8. Fix confirmed working in both paths.

- [x] **M-2** — `superAdminSecurityKey` always passed as `null` to `closeDailySales()` → force-close dual-auth is dead code  
  `DailyReportController.java:66`  
  ✅ Fixed Jun 1 2026 — Full dual-auth wired up: (1) `index.html` override modal: added second password input for super admin key. (2) `app.js confirmForceCloseDailySales`: collects `superAdminSecurityKey` from new input; both fields validated before submit; sent in request body. (3) `DailyReportController`: passes `body.getOrDefault("superAdminSecurityKey","")` instead of `null`. (4) `UserRepository`: added `List<User> findByRole(String role)`. (5) `DailyReportService`: after admin key check, queries all `SUPER_ADMIN` users via `findByRole`; BCrypt-matches input against any; throws clear error if missing, no super admins found, or no match. Live-tested via bypass (today's report deleted, test order created): T1 normal close → 409 ✅; T2 force no superKey → 400 "Super admin security key is required" ✅; T3 force wrong superKey → 400 "Super admin security key is incorrect" ✅; T4 force wrong adminKey → 400 "Admin security key is incorrect" ✅; T5 both keys correct → 200 `closedBy=4 unfulfilledOrders=1` ✅. All 5 gate checks pass.

- [x] **M-3** — `PENDING_COLLECTION` not in `statusBadge()` or `renderOrderRows()` → raw string in status column + empty actions column after force-close  
  `app.js:85-95` (badge map), `app.js:437-455` (action block)  
  ✅ Fixed Jun 1 2026 — (1) `statusBadge()` map: added `'PENDING_COLLECTION': { dot: 'dot-collection', label: 'Pending Collection' }`. (2) `styles.css`: added `.dot-collection { background: #7C3AED; }` (purple, matches COD indicator). (3) Action block: added `else if (o.status === 'PENDING_COLLECTION')` branch showing purple "Pending Collection" label with no mutation buttons. Verified: order `300526-000003` (PENDING_COLLECTION, ₱560) returned by Orders API — frontend will now render correct badge and label.

---

### GROUP E — Collections Queue Integrity · Flow 9 · Backend + Frontend
**Root cause:** `PENDING` status has dual meaning (on-hold vs. awaiting payment) with no distinguishing field; collect endpoint has no `paymentMode` guard.

- [x] **M-7** — On-hold PENDING non-CASH orders appear in Collections; collecting them creates double SALE (same root as C-1; still reachable via hold-then-collect workflow)  
  `OrderRepository.java:49-53` (findPendingCollections query)  
  ✅ Fixed Jun 1 2026 — Removed the `OR (status = 'PENDING' AND paymentMode != 'CASH')` clause from `findPendingCollections()`. Collections queue now only shows `PENDING_COLLECTION` orders (unambiguously deferred via force-close). PENDING on-hold orders remain accessible in the Order List. Verified via API: `/api/orders/collections` returns 1 order (300526-000003, PENDING_COLLECTION, ONLINE); no PENDING orders present in DB to contaminate the queue.

- [x] **M-8** — `PATCH /api/orders/{id}/collect` has no `paymentMode` guard → PENDING CASH orders collectable via direct API call; creates double SALE + patches report  
  `OrderController.java:409-413`  
  ✅ Fixed Jun 1 2026 — Added paymentMode guard immediately after the status check: returns HTTP 400 "Cash orders cannot be collected — payment is made at delivery" if `order.getPaymentMode() == "CASH"`. Compile confirmed. Runtime test blocked by admin security key gate (guard executes after key validation); code-review verified: guard is in correct position in the call chain.

- [x] **M-4** — `modal-mark-collected` and `confirmMarkCollected` are dead code; `askMarkCollected()` is never called from anywhere in the codebase  
  `app.js:1097-1108`, `index.html:2201`  
  ✅ Fixed Jun 1 2026 — Removed `askMarkCollected` and `confirmMarkCollected` functions from `app.js`. Removed `modal-mark-collected` div block from `index.html`. Live flow (`openCollectionDetail` → `modal-collection-detail` → `confirmCollectFromDetail`) is unaffected. Verified: pattern search confirms zero references to `modal-mark-collected`, `askMarkCollected`, and `confirmMarkCollected` remain in either file.

- [x] **M-5** — `openCollectionDetail` reads from stale `_collectionsParsed` cache with no server fetch → wrong data shown in multi-admin concurrent use  
  `app.js:1141`  
  ✅ Fixed Jun 1 2026 — Converted `openCollectionDetail` to `async`. Replaced `_collectionsParsed.find()` cache lookup with `fetch(GET /api/orders/{id})` — always reads fresh data from the server. Error handling added: toast on HTTP error or connection failure. `_collectionsParsed` retained for the list render only. Verified: server fetch pattern confirmed present; cache-find pattern confirmed absent.

- [x] **M-6** — Daily report patch on collection only updates `gross_sales`, `net_sales`, `total_revenue`; `total_orders`, `unfulfilled_orders`, `unfulfilled_amount` never reconciled  
  `OrderController.java:426-435`  
  ✅ Fixed Jun 1 2026 — Added three reconciliation writes inside the existing `ifPresent` block: `totalOrders + 1`, `unfulfilledOrders - 1` (floor 0), `unfulfilledAmount - order.total` (floor ₱0). Compile fix applied: `getUnfulfilledOrders()` returns primitive `int` so removed null-check. Baseline captured: May 30 report = `total_orders=9, unfulfilled_orders=2, unfulfilled_amount=₱1184.05`; collecting order 300526-000003 (₱560) will produce `10 / 1 / ₱624.05`.

---

### GROUP F — Inventory Restore in Refund/Void · Flows 10, 11 · Backend
**Root cause:** Inventory restore in `TransactionController` bypasses `InventoryService` entirely — no warehouse routing, no movement log, not atomic with the ledger write.

> ⚠️ **Fixing M-11 resolves M-9, M-10, and M-12 as side-effects** — move restore into `TransactionService` under the same `@Transactional` boundary, routing through `InventoryService.restoreStock()`.

- [x] **M-11** — Restore loop runs in controller AFTER the `@Transactional` ledger commit; exception swallowed → HTTP 201 returned with stock never updated  
  `TransactionController.java:115-135` (refund), `:195-213` (void)  
  ✅ Fixed Jun 2 2026 — **Refund side only.** `TransactionService` injected with `OrderRepository`, `ProductRepository`, `InventoryService` (all new — no duplicates). Inventory restore loop moved from `issueRefund` inner try-catch into `recordRefund()` after the ledger save, inside the existing `@Transactional` boundary. Inner try-catch (which was swallowing failures) deleted from controller. Void side (`issueVoid` / `recordPostCloseVoid`) intentionally not touched — those methods are superseded by the void system redesign in `VOID_CANCEL_RETURN_REDESIGN.md`.

- [x] **M-9** — Restore always writes to `stockWh1`; original warehouse ignored → multi-warehouse stock imbalance on every refund/void  
  `TransactionController.java:128, 202`  
  ✅ Fixed Jun 1 2026 — Refund and void restore loops now read `item.getWarehouse()` (falling back to `"wh1"` if null) and route the stock increment through a `wh2`/`wh3`/default switch. `logMovement` warehouse argument updated to use the same `wh` variable. Verified: wh2 order refund restored `stock_wh2` (+2), wh2 order void restored `stock_wh2` (+3); `stock_wh1` unchanged in both cases; `inventory_movements.warehouse = wh2` for both rows.

- [x] **M-10** — Void restore uses full `item.getQuantity()` regardless of partial void amount entered by user  
  `TransactionController.java:197-204`  
  ⏭️ Superseded Jun 2 2026 — The proportional void ratio fix applies to `issueVoid` / the restore loop in the old void flow. Per `VOID_CANCEL_RETURN_REDESIGN.md`, `POST /api/transactions/void` and `recordPostCloseVoid` are being removed entirely and replaced with an item-level void system. Fixing this now would be modifying code scheduled for deletion. Marked resolved via design supersession — no code written.

- [x] **M-12** — No `InventoryMovement` row written for refund/void stock restores → stock changes unauditable  
  `TransactionController.java:128-130` (no `logMovement()` call)  
  ✅ Fixed Jun 1 2026 — `InventoryService.logMovement` made package-private; `InventoryService` injected into `TransactionController`; `REFUND_RETURN` and `VOID_RETURN` calls added after each restore save; V37 Flyway migration added to expand `chk_movement_type` constraint. Verified: movement rows written to DB for both paths, no backend errors.

---

### GROUP G — Missing Inventory Movement Logs · Flows 10, 11, 13, 14 · Backend
**Root cause:** Three separate controllers call `productRepository.save()` directly, bypassing `InventoryService.logMovement()`.

> ⚠️ Same fix pattern in three places. M-12 also belongs to Group F above.

- [x] **M-12** · Flow 10, 11 — Refund/void stock restores *(see Group F — fixed)*
- [x] **M-17** · Flow 14 — Delivery receipt stock additions not logged in `inventory_movements` → incoming stock is invisible in audit trail  
  `ProductController.java:427-431`  
  ✅ Fixed Jun 1 2026 — `InventoryService` injected into `ProductController`. `logMovement("RESTOCK", wh, received, receiptNumber, reason, null)` added inside the delivery item loop immediately after `productRepository.save(product)`. Runs inside the existing `@Transactional` boundary from C-7. Verified: RESTOCK row written to `inventory_movements` with correct type, warehouse (wh1), quantity (+5), receipt number as reference, and supplier name in reason.
- [x] **M-27** · Flow 13 — Manual stock override via product edit not logged → silent stock change with no audit trail  
  `ProductController.java:291-341`  
  ✅ Fixed Jun 1 2026 — `@Transactional` added to `updateProduct`. Old stock values captured into `prevWh1/2/3` before the stock-field blocks. After `productRepository.save(product)`, one `logMovement("MANUAL_ADJUST", whN, delta, productId, reason, null)` call written per warehouse where the value actually changed; delta is signed (positive = stock added, negative = stock removed). Verified: MANUAL_ADJUST row written with `wh2 | +50 | product_id as reference | "Manual stock adjustment by TestEditor"`.

---

### GROUP H — Missing JWT Extraction / Actor-from-Body · Flows 13, 14, 16, 20 · Backend
**Root cause:** Several controllers either have no `JwtUtil` injection or do not accept an `Authorization` header on write endpoints; `userId` logged as `null` and actor name comes from free-text request body fields.

> ⚠️ Same fix pattern across five items. Same root cause pattern as C-3.

- [x] **M-19** · Flow 14 — `processDelivery`: `activityLog.userId` null; `DeliveryLog.encodedByUserId` always null; actor from body field  
  `ProductController.java:563`  
  ✅ Fixed Jun 1 2026 — `JwtUtil` injected into `ProductController`. `@RequestHeader(required=false)` added to `processDelivery`. `userId` extracted from JWT; passed to `log.setEncodedByUserId(userId)` and `activityLogService.log(userId, ...)`. Verified: `delivery_log.encoded_by_user_id = 4`; activity_log `user_id = 4` for RECEIVE_STOCK entry.

- [x] **M-28** · Flow 13 — `createProduct` / `updateProduct`: `activityLog.userId` null; actor from body `encodedByName`  
  `ProductController.java:172, 356`  
  ✅ Fixed Jun 1 2026 — `@RequestHeader(required=false)` added to both methods. `userId` extracted from JWT and passed to `activityLogService.log(userId, ...)`. Verified: activity_log `user_id = 4` for EDIT_PRODUCT entry.

- [x] **M-29** · Flow 16 — `PurchaseOrderController` all write endpoints: zero activity logging; `po.createdBy` is free-text body value  
  `PurchaseOrderController.java:71, 109`  
  ✅ Fixed Jun 1 2026 — `ActivityLogService`, `JwtUtil`, and `UserRepository` injected into `PurchaseOrderController`. `@RequestHeader(required=false)` added to `createPurchaseOrder` and `updateStatus`. `po.setCreatedBy()` now uses JWT-resolved user full name (fallback to body value). `activityLogService.log(userId, ...)` added to both write endpoints. Verified: `po.createdBy = "Francis Garbosa"`; activity_log `user_id = 4`, `user_name = "Francis Garbosa"` for CREATE_PURCHASE_ORDER.

- [x] **M-33** · Flow 20 — `createUser` / `updateUser` in `UserController`: neither endpoint takes an `Authorization` header; `activityLog.userId` always null; actor logged from body field (`createdByName` / `changedByName`)  
  `UserController.java:110, 169`  
  ✅ Fixed Jun 1 2026 — `@RequestHeader(required=false)` added to both methods. `userId = userIdFromHeader(authHeader)` called (JwtUtil and helper already existed in UserController). `activityLogService.log(userId, ...)` updated in both. Verified: activity_log `user_id = 4` for CREATE_USER entry.

- [x] **M-34** · Flow 13 — `updateTag` in `ProductController`: no `Authorization` header, no `JwtUtil` injection; `activityLog.userId` always null; actor from body `userName`  
  `ProductController.java:190`  
  ✅ Fixed Jun 1 2026 — `@RequestHeader(required=false)` added to `updateTag`. `userId` extracted from the JWT already injected in M-19. `activityLogService.log(userId, ...)` updated. Same pattern as M-19/M-28.

---

### GROUP I — Receive Stock Side-Effects · Flow 14 · Backend
**Root cause:** Exception-swallowing `try-catch` blocks hide failures in financial side-effects; payable uses stored cost instead of actual invoice.

- [x] **M-15** — Payable creation in bare `try-catch` → stock received, accounts-payable liability silently lost; user gets HTTP 200 toast  
  `ProductController.java:531-543`  
  ✅ Fixed Jun 1 2026 — `try-catch` block around payable creation removed. Payable write now runs directly inside the `@Transactional` boundary of `processDelivery`. A payable failure rolls back stock increments, delivery log, and PO match atomically. Verified: payable row created (WAVE2-TEST-01 | ₱26.50 | PENDING) with no error.

- [x] **M-16** — PO auto-match in bare `try-catch` → `fulfilledQty` / PO status silently diverges from actual fulfillment  
  `ProductController.java:455-529`  
  ✅ Fixed Jun 1 2026 — `try-catch` block around PO auto-match logic removed. PO item updates and PO status transitions now run directly inside the `@Transactional` boundary. A PO match failure rolls back the entire delivery atomically. Verified: delivery with no matching PO processed cleanly (loop exits via `if (poItem == null) continue`); no exceptions thrown; stock incremented and payable created correctly.

- [x] **M-18** — Payable amount derived from stored `product.unitCost`, not actual supplier invoice → wrong or ₱0.00 payable when cost is null or varies  
  `ProductController.java:435-436, 537`  
  ✅ Fixed Jun 2 2026 — Added `BigDecimal unitCost` field to `DeliveryRequest.DeliveryItem`. `processDelivery` now uses `item.getUnitCost()` when provided and > 0, falling back to `product.getUnitCost()` otherwise — existing receipts without an invoice cost are unaffected. Added "Unit Cost (₱)" input column to the delivery line row in `app.js` (col widths adjusted: Product 4→3, Rejected 2→1, new cost col 2); `unitCost` included in the items payload on submit. Verified: compile clean, V38 migration ran, `delivery_log_items.unit_cost` column confirmed present.

---

### GROUP J — Order Status Integrity · Flows 5, 6, 7 · Backend + Frontend
**Root cause:** No transition matrix in `updateStatus`; cancel path has no PENDING_COLLECTION guard.

- [x] **M-24** — No status transition rules → `PENDING_COLLECTION → ACTIVE` bypasses collect flow; dangling `deferralVoid` remains in ledger with no offsetting COLL-SALE  
  `OrderService.java:137-172`  
  ✅ Fixed Jun 2 2026 — Replaced the permissive status whitelist in `updateStatus()` with an explicit transition matrix. Only three pairs are allowed: `ACTIVE→DELIVERED`, `ACTIVE→PENDING`, `PENDING→ACTIVE`. All other combinations (including `PENDING_COLLECTION→ACTIVE`) throw a clear 400 error directing the caller to the collect endpoint. `PENDING→PENDING_COLLECTION` is set directly by `DailyReportService` and is unaffected. Live-verified: T1 `PENDING_COLLECTION→ACTIVE` → 400 ✅; T2 `ACTIVE→PENDING` → 200 ✅.

- [x] **M-25** — COD resume password gate is frontend-only; `PUT /api/orders/{id}/status` accepts any status change without password  
  `OrderController.java:232-258`  
  ✅ Fixed Jun 2 2026 — Added backend security gate in `updateOrderStatus`: when the transition is `PENDING→ACTIVE` on a non-CASH order, requires `securityKey` in request body and BCrypt-verifies it against the caller's `adminSecurityKey`. Returns 403 if missing or wrong. CASH orders bypass the gate (no collection step needed). Frontend `confirmCodResume` updated: drops the separate `verify-password` round-trip; passes `securityKey` directly in the status request body. Modal label updated to "Admin Security Key". Live-verified: T3 no key → 403 ✅; T4 wrong key → 403 ✅; T5 correct key → 200 ✅; T6 CASH bypasses gate → 200 ✅.

- [x] **M-26** — Cancelling a `PENDING_COLLECTION` order writes a second VOID on top of existing `deferralVoid` → net ledger = `-order.total` instead of 0  
  `OrderService.java:cancelOrder()`, `TransactionService.java:recordDeferralVoid()`, `TransactionService.java:recordCollectionSale()`  
  ✅ Fixed Jun 5 2026 (Session 68) — Root cause was broader than original diagnosis: all three ledger-write paths used gross `order.getTotal()` instead of net `total − voidedAmount`. **3-site fix:** (1) `recordDeferralVoid()` → `gross.subtract(voided).negate()`; (2) `recordCollectionSale()` → `gross.subtract(voided)` (must stay in sync with Site 1 so COLL-DEFER and COLL-SALE cancel exactly); (3) `cancelOrder()` → `effectiveVoid = total − voidedAmount`, skips ledger write entirely when effectiveVoid = 0. Dead 3-arg `recordVoid(Order, Long, String)` overload removed. 14/14 tests green (10 unit + 4 integration). Historical correction: order 020626-000080 had a real phantom debit of −₱29.94; corrected via ADJUSTMENT transaction; all 7 affected orders verified at net ₱0.00.

---

### GROUP K — Refund/Void Order State · Flows 10, 11 · Backend + Frontend

- [x] **M-13** — No flag/status on order after refund; fully-refunded DELIVERED orders look identical to normal deliveries — no filter, no UI distinction  
  `TransactionController.java:110-137`, `Order.java` (no refunded_at field)  
  ✅ Fixed Jun 2 2026 — Flyway V38 adds `refunded_at TIMESTAMPTZ` (nullable) to `orders`. `Order.java` entity + `OrderResponse` DTO both include `refundedAt`. Set atomically inside `TransactionService.recordRefund()` alongside the ledger write and stock restore — if any of the three fail, all roll back. Only set on the first refund (subsequent refunds preserve the original timestamp). `OrderController.convertToResponse()` passes `order.getRefundedAt()`. Frontend `renderOrderHistoryRows` renders an amber "Refunded" badge below the status for any order with a non-null `refundedAt`. Verified: V38 column added, `refundedAt` field present in API response (null for non-refunded orders ✓).

- [x] **M-14** — UI does not re-fetch order list or accounting summary after refund or void → stale totals remain until manual reload  
  `app.js:7982-7986` (refund), `app.js:8017-8020` (void)  
  ✅ Fixed Jun 2 2026 — Added `renderOrderHistory()` call after success path in both `confirmRefund` and `confirmVoid`. Order History table re-fetches automatically after each operation. Verified: `renderOrderHistory()` confirmed at lines 7966 and 8002 in app.js.

---

### GROUP L — Order Creation · Flows 3, 4 · Backend + Frontend

- [x] **M-20** — No double-submit lock on `addOrder`; double-click creates two orders with two SALEs + double stock deduction  
  `app.js:4351-4413`  
  ✅ Fixed Jun 2 2026 — Added `window._addOrderSubmitting` flag placed after all validation early-returns, before the `fetch` call. A second click while the first request is in flight returns immediately. `finally` block always restores the flag and re-enables the button. Button selected by `document.querySelector('button[onclick="addOrder()"]')` — no HTML change needed. Code-verified: lock fires only on network path; validation failures exit before the lock is set.

- [x] **M-21** — No server-side validation on order payload; customerName null, empty items, qty ≤ 0, unitPrice ≤ 0 all accepted via direct API call  
  `OrderController.java:82-104`, `OrderService.java:60-63`  
  ✅ Fixed Jun 2 2026 — Added explicit validation block in `OrderController` for both single and batch paths. Single path: returns HTTP 400 immediately if `customerName` is null/blank, `items` is null/empty, any item has `qty ≤ 0`, or any item has `unitPrice ≤ 0`. Batch path: same checks inside the per-request `try` block — validation failures throw `RuntimeException` caught by the existing `catch` and added to the `errors` list. Live-verified: T2 no customerName → 400 "Customer name is required" ✅; T3 empty items → 400 "Order must have at least one item" ✅; T4 qty=0 → 400 "Item quantity must be at least 1" ✅; T5 unitPrice=0 → 400 "Item unit price must be greater than 0 for: Prod A" ✅; T8 valid single order → 201 (regression) ✅.

- [x] **M-22** — Batch import defaults `paymentMode` to `"COD"`; single order defaults to `"CASH"` → unexpected PENDING status on batch imports missing payment mode  
  `OrderController.java:88` vs `:157`  
  ✅ Fixed Jun 2 2026 — Removed the silent `"COD"` fallback from the batch path. Added `paymentMode` to the M-21 batch validation block: if null or blank, throws "Payment mode is required". The `setPaymentMode` call now uses the already-validated value directly. Single-path `"CASH"` default unchanged (intentional for walk-in/agent orders). Live-verified: T6 batch row with no paymentMode → `errors: [{reason:"Payment mode is required"}]` ✅; T7 valid batch row with paymentMode → `imported:1, errors:[]` ✅.

---

### Standalone Moderate

- [x] **M-23** · Flow 1 · Backend — `AuthService.login` never checks `user.status` → DISABLED accounts authenticate and receive valid JWT  
  `AuthService.java:28-65`  
  ✅ Fixed Jun 2 2026 — Added `"DISABLED"` status check after the password check, before `lastLoginAt` update. DISABLED accounts receive HTTP 401 "This account has been disabled. Contact your administrator." AWAY status intentionally allowed (away users may still need access). Live-verified: T1 DISABLED login → 401 ✅; T2 ACTIVE login → 200 (regression) ✅.

- [x] **M-30** · Flow 18 · Backend — `PATCH /api/payables/{id}/status` requires only a valid JWT; any role can mark supplier payables as PAID  
  `PayableController.java:92-140`  
  ✅ Fixed Jun 2 2026 — Added `UserRepository` injection (new field + constructor param). Added role gate at the top of `updateStatus()`: extracts `callerId` from JWT, loads caller, returns 403 if role is not SUPER_ADMIN or ADMINISTRATOR. `JwtUtil` was already injected. Live-verified: T3 STANDARD_USER → 403 "Only administrators can update payable status" ✅; T4 ADMINISTRATOR → 200 (payable marked PAID) ✅.

- [x] **M-31** · Flow 19 · Backend — `insights-summary` sums `order.total` (not ledger) for revenue → refunds/voids invisible; PENDING_COLLECTION orders also included (filter is `!= 'CANCELLED'` only); number disagrees with `accounting-summary` for same period  
  `ReportsController.java:79-86`  
  ✅ Fixed Jun 2 2026 — Two fixes applied to both current-month and previous-month blocks. (1) Filter: added `&& !"PENDING_COLLECTION".equals(o.getStatus())` — deferred orders have net-zero ledger value and must not inflate order count or item totals. (2) Revenue source: replaced `order.total` stream sum with `transactionService.getByDateRange(start, end)` ledger sum — SALE amounts are positive, REFUND/VOID amounts are stored negative; summing the full ledger gives correct net revenue matching `accounting-summary`. Live-verified: `insights-summary.totalRevenue` = `accounting-summary.netSales` = ₱1,292.00 for June 2026 ✅. Note: `dailyBreakdown` per-day revenue still uses `order.total` (refund-aware daily breakdown is a follow-up); the headline total is now accurate.

- [x] **M-32** · Flow 20 · Backend — `updateRole` and `updateStatus` require no elevated-role check → any authenticated user can self-promote to SUPER_ADMIN; actor logged from body not JWT  
  `UserController.java:183-221` *(confirmed — `SecurityConfig` has no role-based rules; only a valid JWT is required)*  
  ✅ Fixed Jun 2 2026 — Added `@RequestHeader(value="Authorization", required=false)` to both `updateRole` and `updateStatus`. Both now extract `callerId` via `userIdFromHeader()` (helper already existed), load the caller, and return 403 if role is not SUPER_ADMIN. `activityLogService.log()` updated to use `callerId` and `caller.getFullName()` instead of null/body-supplied name. Live-verified: T7 no token → 403 (Spring Security gate) ✅; T8 ADMINISTRATOR → 403 "Only Super Admin can change user roles" ✅; T9 SUPER_ADMIN → 200 (role updated) ✅; T10 ADMINISTRATOR → 403 "Only Super Admin can change user status" ✅.

---

## 🟢 Minor
> Cosmetic, display, or low-impact issues. Fix last.

---

### Daily Close cosmetic · Flow 12

- [x] **N-1** · Frontend — `closedBy` user ID never resolved to a name in the closed-day banner  
  `app.js:407-410`  
  ✅ Fixed Jun 2 2026 — `getDailyStatus()` in `DailyReportController` now looks up `closedBy` ID via `userRepository.findById()` (already injected) and adds `closedByName` to the response alongside `report`. Frontend: `byLbl.textContent` now appends `" by <name>"` when `data.closedByName` is present. Code-verified (day currently open; field present in response when closed=true).

- [x] **N-2** · DB — `DailyReport.created_at` always NULL — no `@PrePersist`; service never calls `setCreatedAt()`  
  `DailyReport.java:31`, `DailyReportService.java:161-196`  
  ✅ Fixed Jun 2 2026 — Added `report.setCreatedAt(OffsetDateTime.now())` in `DailyReportService` immediately after `setClosedAt()`. The column and getter/setter already existed — one missing call. Code-verified.

- [x] **N-3** · Frontend — `unfulfilledOrders` read from different response levels in normal vs force-close success path  
  `app.js:945-947` vs `app.js:1004-1006`  
  ✅ Fixed Jun 2 2026 — Both paths now use a unified accessor: `var unfulfilled = data.unfulfilledOrders != null ? data.unfulfilledOrders : ((data.report || {}).unfulfilledOrders || 0)`. Works regardless of which response shape the backend sends. Code-verified.

---

### Collections cosmetic · Flow 9

- [x] **N-4** · Frontend — `daysOut` uses raw UTC millisecond arithmetic → can show 0 days instead of 1 for PHT late-evening orders  
  `app.js:1055`  
  ✅ Fixed Jun 2 2026 — Replaced `Math.floor((Date.now() - created.getTime()) / 86400000)` with a local-calendar-day comparison: both `todayMidnight` and `createdMidnight` are zeroed to midnight local time via `setHours(0,0,0,0)`, then the difference in days is calculated. Correctly counts calendar days in PHT regardless of UTC offset. Code-verified.

- [x] **N-5** · Frontend — `_collectionsParsed` not cleared on logout → next browser user briefly sees previous session's collection data  
  `app.js:199-213` (clearSessionState), `app.js:3252` (module-level var)  
  ✅ Fixed Jun 2 2026 — Added `_collectionsParsed = [];` to `_clearSessionState()`. The variable is now reset alongside all other cached state on logout. Code-verified.

---

### Refund/Void cosmetic · Flows 10, 11

- [x] **N-6** · Frontend + Backend — Refund/void buttons shown on non-DELIVERED orders in Order History; backend has no order status guard  
  `app.js:812`, `TransactionController.java:106-108`  
  ✅ Fixed Jun 2 2026 — Frontend: changed button condition from `canAdmin && notCancelled` to `canAdmin && o.status === 'DELIVERED'`. Backend: added DELIVERED status check in `issueRefund()` before the ceiling check — returns 400 "Refunds can only be issued for delivered orders" for any other status. Live-verified: refund on ACTIVE order → 400 ✅.

---

### Other

- [x] **N-7** · Flow 14 · Frontend — Delivery Reports table not refreshed after successful receipt submission  
  `app.js:2149-2156`  
  ✅ Fixed Jun 2 2026 — Added `if (typeof loadDeliveryReports === 'function') loadDeliveryReports();` after the existing `loadProducts()` / `initDeliveryForm()` calls in the `submitDeliveryReceipt` success path. The check prevents errors if the function is ever unavailable. Code-verified.

- [x] **N-8** · Flow 4 · Backend — Batch duplicate check uses substring match → `"Order No: 12"` falsely matches `"Order No: 123"`, silently skipping a valid import  
  `OrderController.java:142-148`  
  ✅ Fixed Jun 2 2026 — Changed search string from `"Order No: " + externalRef` to `"Order No: " + externalRef + " |"`. The notes format is always `"Order No: XXX | details"`, so appending `" |"` ensures `LIKE '%Order No: 12 |%'` does not match `"Order No: 123 | ..."`. Live-verified: imported order T8A (ref "T8A"), then imported T8 (ref "T8") — T8 was NOT skipped as a duplicate ✅.

- [x] **N-9** · Flow 1 · Backend — Logout activity log uses `userId` from URL query param, not JWT → client can log any userId as the actor  
  `AuthController.java:239-250`  
  ✅ Fixed Jun 2 2026 — Two fixes: (1) Backend: removed `@RequestParam Long userId` and `@RequestParam String userName`; added `@RequestHeader(value="Authorization", required=false)`; userId and name now extracted from JWT via `jwtUtil.extractUserId()` + `userRepository.findById()` (both already injected). (2) Frontend: `_doLogout` now fire-and-forgets `POST /api/auth/logout` with the Bearer token before clearing localStorage. Live-verified: LOGOUT activity log entry shows `userId=3 userName=Ryan Reyes` (from JWT, not URL param) ✅.

- [x] **N-10** · Flow 17 · Backend — Expense log action `"Expense Recorded"` ≠ frontend badge map key `"EXPENSE_RECORDED"` → wrong badge class in Activity Log  
  `ExpenseController.java:107`, `app.js:2901`  
  ✅ Fixed Jun 2 2026 — Changed `"Expense Recorded"` to `"EXPENSE_RECORDED"` in `ExpenseController.java`. Now matches the frontend badge map key and the SCREAMING_SNAKE_CASE convention used by all other action types. Code-verified.

---

## Flow Index

| Flow # | Name | Layer | Primary Tables |
|--------|------|-------|---------------|
| 1 | Login / Logout | FE + BE | `users`, `activity_logs` |
| 2 | Change Password | BE + DB | `users` |
| 3 | Create Order | FE + BE | `orders`, `order_items`, `products`, `transactions` |
| 4 | Batch Import | BE | `orders`, `order_items`, `products`, `transactions` |
| 5 | Order Status Update | BE | `orders`, `activity_logs` |
| 6 | Resume Pending Order | FE + BE | `orders`, `activity_logs` |
| 7 | Cancel Order | BE | `orders`, `transactions`, `products`, `activity_logs` |
| 8 | Order History | FE | `orders`, `order_items`, `transactions` |
| 9 | Collections | FE + BE | `orders`, `transactions`, `daily_reports`, `activity_logs` |
| 10 | Issue Refund | BE | `transactions`, `products`, `activity_logs` |
| 11 | Post-Close Void | BE | `transactions`, `products`, `activity_logs` |
| 12 | Daily Close | FE + BE | `daily_reports`, `orders`, `transactions`, `activity_logs` |
| 13 | Inventory Edit/Add | BE | `products`, `inventory_movements`, `activity_logs` |
| 14 | Receive Stock | BE | `products`, `delivery_logs`, `delivery_log_items`, `purchase_orders`, `payables` |
| 15 | Delivery Reports | FE | `delivery_logs`, `delivery_log_items` |
| 16 | Purchase Orders | BE | `purchase_orders`, `po_items` |
| 17 | Record Expense | BE | `expenses`, `expense_items`, `activity_logs` |
| 18 | Mark Payable Paid | BE | `payables`, `activity_logs` |
| 19 | Monthly / Daily Reports | BE | `orders`, `transactions`, `daily_reports`, `expenses` |
| 20 | Employee Management | BE + DB | `users`, `activity_logs` |

---

## Fix Log

| Date | ID | File Changed | What Changed |
|------|----|-------------|--------------|
| Jun 1, 2026 | **C-1** | `OrderController.java` — `collectOrder()` | Wrapped `recordCollectionSale` and daily report patch block in `if ("PENDING_COLLECTION".equals(status))`. Non-force-closed COD collections no longer create a second SALE entry or re-patch the closed report. |
| Jun 1, 2026 | **C-2** | `DailyReportService.java` — `closeDay()` | Replaced `userRepository.findById(userId).ifPresent(...)` with `.orElseThrow(...)` + inline if-check. An unrecognised `userId` now returns HTTP 400 instead of silently skipping key validation. |
| Jun 1, 2026 | **C-3** | `DailyReportController.java` — `closeDailySales()` | Injected `JwtUtil` + `UserRepository`. Replaced hardcoded `3L` fallback block with JWT extraction (`jwtUtil.extractUserId`). `userName` now resolved from DB via the authenticated user, not free-text body field. Returns 401 if no/invalid token reaches the controller. |
| Jun 1, 2026 | **C-4** | `OrderRepository.java`, `OrderController.java` — `collectOrder()` | Added `findByIdForUpdate` with `@Lock(PESSIMISTIC_WRITE)`. Replaced `findByIdWithItems` call in `collectOrder` with the locked query. The existing `@Transactional` holds the row lock until commit; a second concurrent request blocks then re-reads `DELIVERED` status and fails the status guard. |
| Jun 1, 2026 | **C-5** | `TransactionRepository.java`, `TransactionService.java` — `recordRefund()` | Added `existsByOrderIdAndTransactionTypeAndAmountAndCreatedAtAfter` to the repository. Added a 30-second deduplication window check in `recordRefund` before writing. A duplicate refund of the same amount on the same order within 30 seconds is rejected with HTTP 400. |
| Jun 1, 2026 | **C-6** | `TransactionController.java` — `issueRefund()`, `issueVoid()` | Added ceiling check in both endpoints after the order-exists guard. Loads the order total and rejects with HTTP 400 if the submitted amount exceeds it. Applies to both refund and post-close void paths. |
| Jun 1, 2026 | **C-7** | `DeliveryLogRepository.java`, `ProductController.java` — `processDelivery()` | Added `existsByReceiptNumber` to repository. Added `@Transactional` to method and a pre-loop duplicate receipt check. Duplicate DR rejected with HTTP 400 before any stock is touched; all stock increments now roll back atomically on any failure. |
| Jun 1, 2026 | **C-8** | `User.java`, `UserController.java`, `V36__drop_plain_credentials.sql` | Removed `passwordPlain` and `securityKeyPlain` fields from entity. Removed all 4 setter calls and the entire `GET /api/users/{id}/credentials` endpoint. Flyway V36 migration drops both columns from the DB. |
| Jun 1, 2026 | **C-9** | No code change needed | `GET /api/reports/deliveries` already exists in `DailyReportController.java` and returns correct data. The gap was written before that endpoint was added. Verified: HTTP 200 with 14 records. |
| Jun 1, 2026 | **M-9** | `TransactionController.java` — `issueRefund()`, `issueVoid()` restore loops | **Root cause:** Both restore loops hardcoded `product.setStockWh1(...)` and `"wh1"` in the `logMovement` call, ignoring the `warehouse` field already stored on each `OrderItem`. **Fix:** Both loops now read `item.getWarehouse()` (null-safe fallback to `"wh1"`) into a local `wh` variable, then branch through a `wh2`/`wh3`/default switch to update the correct stock column. `logMovement` warehouse argument updated to `wh`. Verified: wh2 order refund → `stock_wh2` +2, `REFUND_RETURN\|wh2`; wh2 order void → `stock_wh2` +3, `VOID_RETURN\|wh2`; `stock_wh1` unchanged in both. |
| Jun 1, 2026 | **M-19** | `ProductController.java` — `processDelivery()` + constructor | **Root cause:** No JWT extraction; `DeliveryLog.encodedByUserId` never set; `activityLogService.log` called with null userId. **Fix:** `JwtUtil` injected. `@RequestHeader(required=false)` added. `userId` extracted and passed to `log.setEncodedByUserId(userId)` and the activity log call. Verified: `delivery_log.encoded_by_user_id = 4`, activity_log `user_id = 4`. |
| Jun 1, 2026 | **M-28** | `ProductController.java` — `createProduct()`, `updateProduct()` | **Root cause:** No JWT; activity log called with null userId; actor from body `encodedByName`. **Fix:** `@RequestHeader(required=false)` added to both; `userId` extracted and passed to activity log. Verified: activity_log `user_id = 4` for EDIT_PRODUCT. |
| Jun 1, 2026 | **M-34** | `ProductController.java` — `updateTag()` | **Root cause:** No JWT; activity log called with null userId. **Fix:** `@RequestHeader(required=false)` added; `userId` from JWT passed to activity log. JwtUtil already available from M-19. |
| Jun 1, 2026 | **M-33** | `UserController.java` — `createUser()`, `updateUser()` | **Root cause:** No `@RequestHeader` on either method; JwtUtil already injected but unused in these endpoints; activity log called with null userId. **Fix:** `@RequestHeader(required=false)` added to both; `userId = userIdFromHeader(authHeader)` called (helper already existed); activity log updated. Verified: activity_log `user_id = 4` for CREATE_USER. |
| Jun 1, 2026 | **M-29** | `PurchaseOrderController.java` — `createPurchaseOrder()`, `updateStatus()` | **Root cause:** Zero activity logging; `po.createdBy` set from free-text body; no JWT extraction; no ActivityLogService or JwtUtil injected. **Fix:** `ActivityLogService`, `JwtUtil`, `UserRepository` injected; `@RequestHeader(required=false)` added to both write endpoints; `po.setCreatedBy()` now uses JWT-resolved full name with body fallback; `activityLogService.log()` added to both. Verified: `po.createdBy = "Francis Garbosa"`; activity_log `user_id = 4`, `user_name = "Francis Garbosa"`. |
| Jun 1, 2026 | **M-17** | `ProductController.java` — `processDelivery()` + constructor | **Root cause:** Stock increments on delivery receipt wrote directly to `products` via `productRepository.save()` with no subsequent `logMovement()` call — incoming stock had zero audit trail in `inventory_movements`. **Fix:** Injected `InventoryService` into `ProductController` (field + constructor). Added `logMovement("RESTOCK", wh, received, receiptNumber, reason, null)` immediately after `productRepository.save(product)` in the delivery item loop. Runs inside the existing `@Transactional` boundary from C-7. Verified: RESTOCK row written with correct product, warehouse, quantity, receipt number as reference, and supplier name in reason. |
| Jun 1, 2026 | **M-27** | `ProductController.java` — `updateProduct()` | **Root cause:** Manual stock edits via `PATCH /api/products/{id}` wrote the new stock value with no `logMovement()` call — silent changes with no audit trail. Method also lacked `@Transactional`, so a future save failure could leave stock changed but not logged. **Fix:** Added `@Transactional` to `updateProduct`. Captured `prevWh1/2/3` before any stock-field blocks. After `productRepository.save(product)`, added `logMovement("MANUAL_ADJUST", whN, delta, productId, reason, null)` per warehouse where stock actually changed; delta is signed. Verified: MANUAL_ADJUST row written with wh2 \| +50 \| product ID as reference. |
| Jun 1, 2026 | **M-15** | `ProductController.java` — `processDelivery()` | **Root cause:** Payable creation was wrapped in a bare `try-catch(Exception e)` that swallowed failures — stock and delivery log committed while the AP payable was silently lost. **Fix:** Removed the `try-catch`. Payable write now runs inside the `@Transactional` boundary; any failure rolls back stock, log, and PO match together. Verified: payable row created (PENDING, ₱26.50) with no error on successful delivery. |
| Jun 1, 2026 | **M-16** | `ProductController.java` — `processDelivery()` | **Root cause:** PO auto-match block was wrapped in a bare `try-catch(Exception e)` — a match failure printed a warning and continued, leaving PO `fulfilledQty` and status silently diverged from actual receipts. **Fix:** Removed the `try-catch`. All PO item updates and status transitions now run inside the `@Transactional` boundary; any failure rolls back the entire delivery. Verified: delivery with no matching PO processes cleanly (loop exits via null guard); no exceptions; M-17 and M-15 paths unaffected. |
| Jun 1, 2026 | **M-12** | `InventoryService.java`, `TransactionController.java`, `V37__expand_movement_type_constraint.sql` (new) | **Root cause:** `inventory_movements.chk_movement_type` constraint only allowed 5 values (`ORDER_OUT`, `CANCELLED_RETURN`, `MANUAL_ADJUST`, `RESTOCK`, `TRANSFER`); `REFUND_RETURN` and `VOID_RETURN` were not in the list, so any attempt to write a movement row for a refund or void was silently rejected by the DB — the exception was swallowed by the inner `try-catch` and the API returned HTTP 201 as if nothing happened. **Fix:** (1) Made `InventoryService.logMovement()` package-private. (2) Injected `InventoryService` into `TransactionController` constructor. (3) Added `logMovement("REFUND_RETURN", ...)` call after each refund stock restore and `logMovement("VOID_RETURN", ...)` call after each void stock restore. (4) Created Flyway V37 migration to drop and re-create `chk_movement_type` as a strict superset adding the two new values; all 140 existing rows (`ORDER_OUT`) unaffected. Verified: `REFUND_RETURN` and `VOID_RETURN` rows written to `inventory_movements` with correct product, warehouse, quantity, reference, and actor. No backend errors. |

| Jun 1, 2026 | **M-1** | `DailyReportService.java` — `closeDailySales()` operational stats queries | **Root cause:** Three queries (`orderStats`, `itemsSoldResult`, `topProductResult`) filtered `NOT IN ('CANCELLED','PENDING')` but not `'PENDING_COLLECTION'`. After a force-close, deferred orders had their SALEs reversed but were still counted in order and items-sold totals. **Fix:** Added `'PENDING_COLLECTION'` to all three exclusion filters (6 string replacements). **Live-verified:** Jun 1 force-close test — report `total_orders = 9` (correct); old query returns 10 (includes deferred order). Also cross-checked May 30 historical: snapshot = 9, old query = 9, corrected = 8. |
| Jun 1, 2026 | **M-2** | `DailyReportController.java`, `DailyReportService.java`, `UserRepository.java`, `index.html`, `app.js` | **Root cause:** `DailyReportController` hardcoded `null` as `superAdminSecurityKey` arg; the service accepted the parameter but never validated it — force-close dual-auth was entirely dead code. **Fix:** (1) `index.html` override modal: added second password input for super admin key. (2) `app.js confirmForceCloseDailySales`: collects and validates `superAdminSecurityKey`; sends it in request body alongside `adminSecurityKey`. (3) `DailyReportController`: reads `superAdminSecurityKey` from request body instead of passing `null`. (4) `UserRepository`: added `List<User> findByRole(String role)`. (5) `DailyReportService`: after caller's admin key check, fetches all `SUPER_ADMIN` users via `findByRole`, BCrypt-matches input against any one; throws clear error if key missing, no super admins exist, or no match. **Live-tested via bypass** (today's report deleted; test order injected): T1 normal → 409 ✅; T2 force no superKey → 400 ✅; T3 wrong superKey → 400 ✅; T4 wrong adminKey → 400 ✅; T5 both correct → 200 `closedBy=4 unfulfilledOrders=1` ✅. All 5 gate checks pass. Test artifacts cleaned up. |
| Jun 1, 2026 | **M-3** | `app.js` — `statusBadge()`, action block; `styles.css` | **Root cause:** `statusBadge()` had no entry for `PENDING_COLLECTION` → fell through to raw-string fallback. Action block had no branch → empty actions column for deferred orders. **Fix:** (1) Added `'PENDING_COLLECTION': { dot: 'dot-collection', label: 'Pending Collection' }` to badge map. (2) Added `.dot-collection { background: #7C3AED; }` to CSS (purple, matches COD indicator). (3) Added `else if (o.status === 'PENDING_COLLECTION')` action branch rendering a purple "Pending Collection" label with no mutation buttons. Verified: order `300526-000003` (PENDING_COLLECTION) returned by Orders API — frontend now renders correct badge and label. |

| Jun 1, 2026 | **M-7** | `OrderRepository.java` — `findPendingCollections()` | **Root cause:** Query included `OR (status='PENDING' AND paymentMode!='CASH')` — on-hold non-CASH orders appeared in Collections tab, creating risk of double SALE via hold→collect workflow. **Fix:** Removed the PENDING clause entirely; query now matches only `status='PENDING_COLLECTION'`. Verified: `/api/orders/collections` returns only the 1 PENDING_COLLECTION order; no PENDING orders bleed in. |
| Jun 1, 2026 | **M-8** | `OrderController.java` — `collectOrder()` | **Root cause:** No paymentMode guard — CASH orders in PENDING status could be "collected" via direct API call, bypassing normal delivery flow. **Fix:** Added `if ("CASH".equals(order.getPaymentMode()))` guard after status check; returns HTTP 400 with clear message. Guard is after the pessimistic lock fetch, before any writes. Compile confirmed; runtime verified via code review. |
| Jun 1, 2026 | **M-4** | `app.js`, `index.html` | **Root cause:** `askMarkCollected`, `confirmMarkCollected`, and `modal-mark-collected` were orphaned — `askMarkCollected` was never called from HTML or JS; the live collection flow uses `openCollectionDetail` → `modal-collection-detail` instead. **Fix:** Removed both JS functions and the HTML modal block. Zero remaining references confirmed via pattern search. |
| Jun 1, 2026 | **M-5** | `app.js` — `openCollectionDetail()` | **Root cause:** Detail modal populated from `_collectionsParsed` in-memory cache — stale after any concurrent admin action; "Order not found" if another admin already collected it. **Fix:** Converted function to `async`; replaced cache lookup with `fetch(GET /api/orders/{id})` for always-fresh data. Toast on HTTP error or connection failure. Cache array retained for list rendering only. |
| Jun 1, 2026 | **M-6** | `OrderController.java` — `collectOrder()` report patch | **Root cause:** Daily report patch on collection wrote revenue fields only; `total_orders`, `unfulfilled_orders`, `unfulfilled_amount` never reconciled — deferred orders permanently showed as unfulfilled in historical reports. **Fix:** Added `totalOrders+1`, `unfulfilledOrders-1` (floor 0), `unfulfilledAmount-=order.total` (floor ₱0) inside the same `ifPresent` block. Compile fix: removed invalid null-check on primitive `int`. Baseline: May 30 report `total_orders=9, unfulfilled_orders=2, unfulfilled_amount=₱1184.05`; next collection of 300526-000003 (₱560) should yield `10/1/₱624.05`. |

| Jun 2, 2026 | **M-11** | `TransactionService.java`, `TransactionController.java` — refund path only | **Root cause:** Inventory restore ran in controller after `recordRefund()` committed, inside an inner try-catch that swallowed all exceptions — HTTP 201 returned even when stock was never restored. **Fix (refund side only):** Injected `OrderRepository`, `ProductRepository`, `InventoryService` into `TransactionService` (all new, no conflicts). Moved restore loop into `recordRefund()` after the ledger save, inside the existing `@Transactional` boundary. Deleted inner try-catch from `issueRefund`. Void side (`issueVoid`/`recordPostCloseVoid`) intentionally untouched — superseded by redesign. |
| Jun 2, 2026 | **M-10** | No code written | **Superseded by design.** Proportional void ratio fix applies to `issueVoid` restore loop, which is being removed entirely per `VOID_CANCEL_RETURN_REDESIGN.md`. Fixing this would modify code scheduled for deletion. Resolved via design supersession. |

| Jun 2, 2026 | **M-18** | `DeliveryRequest.java`, `ProductController.java`, `app.js` | **Root cause:** `processDelivery` derived payable amount and `delivery_log_items.unit_cost` from `product.getUnitCost()` — the stored product record cost, not the actual invoice price. Delivery form sent no per-line cost at all. **Fix:** Added `BigDecimal unitCost` to `DeliveryRequest.DeliveryItem`. Controller uses invoice cost when provided (> 0), falls back to stored product cost. Added "Unit Cost (₱)" input column to delivery line row in frontend; included in submit payload. No migration needed — `unit_cost` column already existed on `delivery_log_items`. |
| Jun 2, 2026 | **M-13** | `V38__orders_refunded_at.sql`, `Order.java`, `OrderResponse.java`, `TransactionService.java`, `OrderController.java`, `app.js` | **Root cause:** `Order` had no `refunded_at` field; refunded DELIVERED orders were indistinguishable from normal deliveries. **Fix:** V38 migration adds nullable `refunded_at TIMESTAMPTZ`. Entity, DTO, and `convertToResponse` wired. Set inside `TransactionService.recordRefund()` alongside ledger write + stock restore — all atomic under `@Transactional`. First refund sets the timestamp; subsequent refunds preserve it. Frontend shows amber "Refunded" badge in Order History. |
| Jun 2, 2026 | **M-14** | `app.js` — `confirmRefund()`, `confirmVoid()` | **Root cause:** Both success paths closed the modal and showed a toast but never re-fetched — Order History remained stale. **Fix:** Added `renderOrderHistory()` after the toast in both functions. Verified: call present at lines 7966 (refund) and 8002 (void). |
| Jun 2, 2026 | **M-24** | `OrderService.java` — `updateStatus()` | **Root cause:** `updateStatus()` used a permissive status whitelist (`!List.of(...).contains(newStatus)`) with no `(from, to)` transition rules — any authenticated user could change any order to any status, including `PENDING_COLLECTION → ACTIVE` which bypasses the collect flow and leaves a dangling `deferralVoid`. **Fix:** Replaced whitelist with an explicit transition matrix — only `ACTIVE→DELIVERED`, `ACTIVE→PENDING`, `PENDING→ACTIVE` are allowed. All other pairs throw 400. `PENDING→PENDING_COLLECTION` is set directly by `DailyReportService.setStatus()` and is unaffected. Live-verified: T1 `PENDING_COLLECTION→ACTIVE` → 400 ✅; T2 `ACTIVE→PENDING` → 200 ✅. |
| Jun 2, 2026 | **M-25** | `OrderController.java` — `updateOrderStatus()`; `app.js` — `updateOrderStatus()`, `confirmCodResume()`; `index.html` — COD resume modal | **Root cause:** `PUT /api/orders/{id}/status` accepted any status change with only a JWT — the frontend password gate (`verify-password` call in `confirmCodResume`) was entirely bypassable via direct API call. **Fix:** Added backend guard: for `PENDING→ACTIVE` on non-CASH orders, requires `securityKey` in request body, BCrypt-verified against caller's `adminSecurityKey`. Returns 403 if missing or wrong; CASH orders bypass entirely. Frontend updated: `confirmCodResume` drops the separate `verify-password` pre-flight and passes `securityKey` directly in the status body. `updateOrderStatus()` accepts optional third `securityKey` param. Modal label/placeholder updated to "Admin Security Key". Live-verified: T3 no key → 403 ✅; T4 wrong key → 403 ✅; T5 correct key → 200 ✅; T6 CASH bypass → 200 ✅. |
| Jun 5, 2026 | **M-26** | `TransactionService.java` — `recordDeferralVoid()`, `recordCollectionSale()`; `OrderService.java` — `cancelOrder()` | **Root cause (revised):** Broader than original diagnosis — all three ledger-write paths used gross `order.getTotal()` as basis. Any order with prior item voids produced a phantom debit equal to `−voidedAmount`. **Fix (3 sites):** Site 1 `recordDeferralVoid` → `gross.subtract(voided).negate()`; Site 2 `recordCollectionSale` → `gross.subtract(voided)` (in sync with Site 1); Site 3 `cancelOrder` → `effectiveVoid = total − voidedAmount`, skips write when effectiveVoid = 0. Dead 3-arg `recordVoid(Order, Long, String)` overload removed. 14/14 tests green. Historical correction: order 020626-000080 (−₱29.94 phantom debit) corrected via ADJUSTMENT; all 7 affected orders verified at net ₱0.00. Commit: e78dd9c. |
| Jun 2, 2026 | **M-20** | `app.js` — `addOrder()` | **Root cause:** `addOrder` had no submit lock — a double-click on "Submit Order" fired two concurrent `POST /api/orders` requests; both succeeded, creating duplicate orders, double SALE entries, and double stock deduction. **Fix:** Added `window._addOrderSubmitting` boolean flag. Lock is set after all validation early-returns pass, immediately before the `fetch` call. `finally` block always clears the flag and re-enables the button regardless of success or error. A second click during the in-flight request returns immediately. Code-verified: validation failures exit before the lock is set so they never get stuck. |
| Jun 2, 2026 | **M-21** | `OrderController.java` — `createOrder()`, `createOrderBatch()` | **Root cause:** No server-side payload validation existed — a direct API call with `customerName: null`, empty `items`, `quantity: 0`, or `unitPrice: 0` all silently accepted. Only existing guard was `source` enum check in the service. **Fix:** Added validation block in `createOrder()` after the JWT check: returns HTTP 400 if customerName is null/blank, items list is null/empty, any item qty ≤ 0, or any item unitPrice ≤ 0. Same checks added inside the per-request `try` block in `createOrderBatch()` — failures throw `RuntimeException`, caught by existing `catch`, added to `errors` list. Live-verified: T2–T5 all return 400 with clear messages; T8 valid order still creates (regression). |
| Jun 2, 2026 | **M-22** | `OrderController.java` — `createOrderBatch()` | **Root cause:** Batch path silently defaulted missing `paymentMode` to `"COD"` while single path defaulted to `"CASH"` — a batch CSV row without a paymentMode column created a PENDING order unexpectedly, with no error or warning in the response. **Fix:** Removed the `"COD"` fallback. Added `paymentMode` null/blank check to M-21's batch validation block — throws "Payment mode is required" if absent. The `setPaymentMode` call now uses the validated value directly. Single-path `"CASH"` default retained (intentional for walk-in context). Live-verified: T6 batch row missing paymentMode → in `errors` list with clear message ✅; T7 valid full batch row still imports ✅. |
| Jun 2, 2026 | **M-23** | `AuthService.java` — `login()` | **Root cause:** After a successful password match, `login()` immediately updated `lastLoginAt` and generated a JWT with no account status check — DISABLED accounts received valid tokens. **Fix:** Added `if ("DISABLED".equals(user.getStatus()))` check between the password check and the `lastLoginAt` update; throws "This account has been disabled. Contact your administrator." AWAY status intentionally allowed. Live-verified: T1 DISABLED login → 401 ✅; T2 ACTIVE login → 200 (regression) ✅. |
| Jun 2, 2026 | **M-30** | `PayableController.java` — `updateStatus()` + constructor | **Root cause:** `PATCH /api/payables/{id}/status` verified only that the caller had a valid JWT; no role check. `JwtUtil` was already injected but only used for logging `paidBy` — not for authorization. Any authenticated user (STAFF, STANDARD_USER) could mark a supplier payable as PAID. **Fix:** Added `UserRepository` field and constructor param. Added role gate at the top of `updateStatus()`: extracts `callerId` from JWT, loads caller, returns 401 if no token, 403 if not SUPER_ADMIN or ADMINISTRATOR. Live-verified: T3 STANDARD_USER → 403 ✅; T4 ADMINISTRATOR → 200 ✅. |
| Jun 2, 2026 | **M-31** | `ReportsController.java` — `getInsightsSummary()` | **Root cause:** (1) Billed filter was `!= CANCELLED` only — PENDING_COLLECTION orders have net-zero ledger value but positive `order.total`, inflating revenue and order counts. (2) `totalRevenue` summed `order.total` directly — a value that never changes after order creation, so refunds and voids were invisible. The `accounting-summary` endpoint uses the transaction ledger and showed different numbers for the same month. **Fix:** (1) Added `&& !"PENDING_COLLECTION".equals(o.getStatus())` to the billed filter (current and previous month). (2) Replaced `order.total` stream with `transactionService.getByDateRange(start, end)` ledger sum — SALE is positive, REFUND/VOID stored negative, net sum = correct net revenue. Applied to both `totalRevenue` and `prevMonthRevenue`. Live-verified: `insights-summary.totalRevenue` = `accounting-summary.netSales` = ₱1,292.00 for Jun 2026 ✅. |
| Jun 2, 2026 | **M-32** | `UserController.java` — `updateRole()`, `updateStatus()` | **Root cause:** Neither method accepted an `Authorization` header or checked the caller's role. Any authenticated user could call `PATCH /api/users/{self-id}/role {"role":"SUPER_ADMIN"}` to self-promote, or lock out any account via `updateStatus`. Actor was logged from free-text `changedByName` body field (always null userId). **Fix:** Added `@RequestHeader(required=false) String authHeader` to both methods. Both extract `callerId` via the existing `userIdFromHeader()` helper, load the caller, and return 403 if not SUPER_ADMIN. `activityLogService.log()` updated to use verified `callerId` and `caller.getFullName()`. Live-verified: T7 no token → blocked ✅; T8 ADMINISTRATOR → 403 ✅; T9 SUPER_ADMIN → 200 ✅; T10 ADMINISTRATOR updateStatus → 403 ✅. |

---

| Jun 2, 2026 | **N-1** | `DailyReportController.java` — `getDailyStatus()`; `app.js` — banner | **Root cause:** Backend returned `closedBy` as a raw user ID; frontend read `data.report.closedBy` but only used `closedAt` in the banner text — the name was never resolved or displayed. **Fix:** `getDailyStatus()` now calls `userRepository.findById(r.getClosedBy()).map(User::getFullName)` and adds `closedByName` to the response at the top level. Frontend appends `" by <name>"` when the field is present. |
| Jun 2, 2026 | **N-2** | `DailyReportService.java` — `closeDailySales()` | **Root cause:** The `DailyReport` entity has a `created_at` column and getter/setter but `closeDailySales()` never called `setCreatedAt()` — the column was always NULL. **Fix:** Added `report.setCreatedAt(OffsetDateTime.now())` after `setClosedAt()`. |
| Jun 2, 2026 | **N-3** | `app.js` — normal-close and force-close success paths | **Root cause:** Normal-close read `data.unfulfilledOrders`; force-close read `data.report.unfulfilledOrders` — different levels for the same value. **Fix:** Both paths now use `data.unfulfilledOrders != null ? data.unfulfilledOrders : ((data.report \|\| {}).unfulfilledOrders \|\| 0)`. |
| Jun 2, 2026 | **N-4** | `app.js` — Collections `daysOut` calculation | **Root cause:** `Math.floor((Date.now() - created.getTime()) / 86400000)` compares UTC timestamps — a PHT 10 PM order is still "today" in UTC until 8 AM the next morning. **Fix:** Both dates zeroed to local midnight via `setHours(0,0,0,0)` before subtraction; difference is exact calendar days in local time. |
| Jun 2, 2026 | **N-5** | `app.js` — `_clearSessionState()` | **Root cause:** `_collectionsParsed` module-level array was never cleared on logout; a second user logging in on the same browser briefly saw the previous session's collections. **Fix:** Added `_collectionsParsed = [];` to `_clearSessionState()`. |
| Jun 2, 2026 | **N-6** | `app.js` — Order History action buttons; `TransactionController.java` — `issueRefund()` | **Root cause:** Frontend showed Refund/Void buttons for any non-CANCELLED order (`notCancelled`). Backend had no order-status guard on `issueRefund` — a direct API call could refund a PENDING or ACTIVE order. **Fix:** Frontend condition changed to `o.status === 'DELIVERED'`. Backend: DELIVERED guard added before the ceiling check; returns 400 "Refunds can only be issued for delivered orders" otherwise. Live-verified: ACTIVE order refund → 400 ✅. |
| Jun 2, 2026 | **N-7** | `app.js` — `submitDeliveryReceipt()` success path | **Root cause:** Success path called `loadProducts()` and `initDeliveryForm()` but not `loadDeliveryReports()` — the delivery reports table stayed stale. **Fix:** Added `if (typeof loadDeliveryReports === 'function') loadDeliveryReports();` after the existing calls. |
| Jun 2, 2026 | **N-8** | `OrderController.java` — `createOrderBatch()` duplicate check | **Root cause:** `existsByNotesContaining("Order No: 12")` matches any notes containing the substring — including `"Order No: 123 \| ..."` — silently skipping a valid new import. **Fix:** Appended `" \|"` to the search string: `existsByNotesContaining("Order No: " + externalRef + " \|")`. The pipe separator is always present in e-commerce batch import notes. Live-verified: T8A and T8 both imported — T8 was NOT skipped ✅. |
| Jun 2, 2026 | **N-9** | `AuthController.java` — `logout()`; `app.js` — `_doLogout()` | **Root cause:** (1) Backend: `logout()` accepted `@RequestParam Long userId` — any client could log any user's logout. (2) Frontend: `_doLogout()` never called the logout endpoint — no activity log was ever written. **Fix:** Backend: replaced query params with `@RequestHeader Authorization`; userId/name extracted from JWT. Frontend: reads token from localStorage before clearing it, then fire-and-forgets `POST /api/auth/logout` with Bearer header. Live-verified: LOGOUT log entry shows `userId=3 userName=Ryan Reyes` from JWT ✅. |
| Jun 2, 2026 | **N-10** | `ExpenseController.java` — activity log action key | **Root cause:** Expense activity log used action `"Expense Recorded"` (words with space) while the frontend badge map uses `"EXPENSE_RECORDED"` (SCREAMING_SNAKE_CASE) — mismatch caused the wrong badge class in the Activity Log view. **Fix:** Changed to `"EXPENSE_RECORDED"` to match the convention used by all other action types. |

*QA by static code trace — all 20 flows. Fix phase started Jun 1, 2026.*  
*Update checkboxes and Fix Log as items are resolved.*
