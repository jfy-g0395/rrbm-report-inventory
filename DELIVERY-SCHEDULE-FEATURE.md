# DELIVERY-SCHEDULE-FEATURE

Implementation tracker for two features under a new **Delivery Schedule** tab:
**A) Scheduled inter-warehouse stock moves (request → admin approve → complete)** and
**B) Deferred order delivery (order records nothing until delivered).**

## How to use this doc
- Work **one session at a time** (each is scoped to stay usage-efficient).
- At the **start** of a session: read "Locked decisions", "Reuse map", and the target session block.
- At the **end** of a session: tick its checkboxes, fill in the "State after this session" note, and confirm the "Next session" pointer. Do NOT start the next session in the same run.
- Every DB/verify step uses the **ephemeral-clone** workflow (clone prod via `pg_dump` into an isolated compose project; never test on live). Deploy = `docker compose up -d --build` (Flyway auto-runs) + static frontend cache-bust.

---

## Locked decisions (from planning Q&A)
1. Deferred delivery is chosen **at order creation**; the dead `order_type=DELIVERY` option is **removed** and replaced by this flow.
2. Stock-move quantities change **only on "Complete" (arrival)**, not on approval.
3. Approvers = **SUPER_ADMIN, ADMINISTRATOR, and a new assignable `DELIVERY_MANAGEMENT` role**.
4. A stock-move request holds **multiple products** (per-line from→to warehouse).
5. **Cancellation/lifecycle (no gaps):** a `SCHEDULED_DELIVERY` order records nothing until it resolves to exactly one terminal state — `DELIVERED` or `CANCELLED`. Actions: **Mark Delivered** (record on the day), **Reschedule** (move date, repeatable indefinitely), **Deliver/record now** (un-schedule but keep + record today), **Cancel order** (drop, nothing recorded). No path leaves an order alive-but-unrecorded. A passed date never auto-acts (flagged "overdue").
6. **WH3 = "Balagtas" everywhere** — remove the "Santan" label from code (but NOT the real street address `116 Santan St.`, nor immutable migration history).

## Reuse map (grounding — file:line)
- Warehouses: `products.stock_wh1/2/3`; `Product.getTotalStock()`. WH3 label = "Balagtas".
- `InventoryService`: `addWhStock`, `deductWhStock`, `requireValidWarehouse`, `logMovement`; `inventory_movements` already allows `movement_type='TRANSFER'` (no movement-type migration needed).
- `OrderService.createOrder` (L180) deducts stock + `TransactionService.recordSale` + commission at creation. `createOrderAtDate` has an `affectStock` flag + effective date. `batchMarkAsCollected` (L1179) = the template for "fulfill later, record on the real day".
- Daily-report status filter: `status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')` in `DailyReportService.populateSnapshot`/`closeDailySales`.
- `orders.chk_status` CHECK constraint (widen like V88 did for `chk_source`).
- Roles: `UserController.VALID_ROLES` + `ROLE_DEFAULT_PAGES` + `ALL_PAGES` (L24–43) → `allowedPages`; frontend `PAGE_PERMISSIONS` (app.js:188), role badge (app.js:212), `viewToPageKey` (app.js:379), `canAccessPage` (app.js:411).
- Frontend: `navigateTo` (app.js:5505); list load/render (`loadDailyReports`/`renderDailyReportsList` ~10295); modal `openModal/closeModal` (app.js:325); **product type-ahead `renderProductDropdown` (app.js:5964)** already shows `WH1:x · WH2:y · Balagtas:z` from `appState.cachedProducts` — reuse for the move modal; security-key gate `POST /api/auth/verify-security-key` (app.js:2925); order form type select (index.html:592); employee role selects (index.html ~3741/3902/4049); "Santan" label to fix: `ProductController.java:361/365`.
- Next Flyway version: **V91** (V90 was used by the separate Reject-Management role fix / `chk_role` widening).

---

## STATUS OVERVIEW
- [ ] **Session 0** — Foundations: `DELIVERY_MANAGEMENT` role + Balagtas rename + empty Delivery Schedule tab (code-only, no migration)
- [ ] **Session 1** — Stock Move backend (migration V90 + entities/service/controller)
- [ ] **Session 2** — Stock Move frontend (request modal + approval actions) → **Phase A shippable**
- [ ] **Session 3** — Deferred Delivery backend (migration V91 + defer/fulfill/reschedule/cancel + report exclusion)
- [ ] **Session 4** — Deferred Delivery frontend (order-form option + tab management) → **Phase B shippable**
- [ ] **Session 5** — Full E2E verification + deploy + commit

---

## Session 0 — Foundations (small, code-only, no DB migration)
**Goal:** add the new role, finish the Balagtas rename, and stand up the empty tab so later sessions have a home.
- [ ] Backend `UserController` (L24–43): add `"DELIVERY_MANAGEMENT"` to `VALID_ROLES`; add its `ROLE_DEFAULT_PAGES` entry (`dashboard, orders, delivery-schedule, inventory, delivery-reports`); add `"delivery-schedule"` to `ALL_PAGES`.
- [ ] Frontend `PAGE_PERMISSIONS` (app.js:188): add `delivery-schedule` to admin/accounting+ where appropriate; add a `DELIVERY_MANAGEMENT` entry. Add role badge label (app.js:212) + `viewToPageKey` (app.js:379).
- [ ] Add `DELIVERY_MANAGEMENT` option to the **3 employee role `<select>`s** (index.html ~3741/3902/4049) + `onRoleSelectChange` handling if needed.
- [ ] Balagtas: `ProductController.java:365` `"StockSantan:"` → `"StockBalagtas:"`; comment L361 → Balagtas. Confirm no other functional "Santan" (leave `company_address` + migration history).
- [ ] Nav item "Delivery Schedule" (index.html) + `view-delivery-schedule` section (two empty cards: "Stock Moves", "Order Deliveries") + `loadDeliverySchedule()` stub + `navigateTo` hook.
- [ ] Cache-bust `app.js?v=u6 → u7`.
**Verify (clone):** new role assignable + user gets the tab; tab renders empty; edit-product activity log shows "StockBalagtas".
**Done when:** builds, tab visible to admins + Delivery Management, role saves.
**State after this session:** _(fill in)_
**Next session:** Session 1.

## Session 1 — Stock Move backend
**Goal:** full API for inter-warehouse moves; stock changes only on complete.
- [ ] Migration **V91__stock_transfers.sql**: `stock_transfers` (status PENDING/APPROVED/COMPLETED/REJECTED/CANCELLED, scheduled_date, requested_by(+name), approved_by(+name), approved_at, completed_at, reject_reason, notes, change_log, created_at) + `stock_transfer_items` (transfer_id FK, product_id, from_warehouse, to_warehouse, quantity>0).
- [ ] `StockTransfer.java`, `StockTransferItem.java`, `StockTransferRepository.java`, `StockTransferService.java`, `StockTransferController.java` (`/api/stock-transfers`).
- [ ] Endpoints: `POST /` (create), `GET /?status=`, `GET /{id}`, `POST /{id}/approve`, `/reschedule`, `/reject`, `/complete`, `/cancel`.
- [ ] `complete`: `@Transactional`, guard status==APPROVED, per line re-check source stock, `deductWhStock(from)`+`addWhStock(to)`+save+`logMovement('TRANSFER',…)`×2 → COMPLETED. Approver-gated (role set).
**Verify (clone):** create→approve→complete moves exact qty, two TRANSFER rows/line; short-stock at complete errors; reject/reschedule/cancel work; non-approver 403; live reports untouched.
**Done when:** all endpoints pass clone checks.
**State after this session:** _(fill in)_
**Next session:** Session 2.

## Session 2 — Stock Move frontend (→ Phase A ships)
**Goal:** usable request + approval UI.
- [ ] Request modal: multi-line; product type-ahead via `renderProductDropdown` (shows per-WH stock); from/to selects (defaults at top, editable per line); qty; submit `POST /api/stock-transfers`.
- [ ] "Stock Moves" card: list requests by status; admin actions Approve/Reschedule/Reject/Complete behind the security-key modal (reuse app.js:2915 pattern); show live per-WH stock per line.
- [ ] Empty/error/loading states; toasts.
**Verify (clone):** end-to-end via UI (or asset+API checks): request as normal user, approve+complete as admin, see quantities update.
**Done when:** Phase A works end-to-end on the clone.
**State after this session:** _(fill in)_
**Next session:** Session 3.

## Session 3 — Deferred Delivery backend
**Goal:** an order can be created deferred and fulfilled later, recording only on delivery day; safe cancel/reschedule.
- [ ] Migration **V92__order_scheduled_delivery.sql**: add `orders.scheduled_delivery_date DATE`, `orders.delivery_change_log TEXT`, `orders.delivered_at TIMESTAMPTZ` (if absent); **widen `chk_status`** to include `'SCHEDULED_DELIVERY'`.
- [ ] `OrderService.createOrder`: if `scheduledDeliveryDate` present → save as `SCHEDULED_DELIVERY`, **skip** stock/sale/commission.
- [ ] `fulfillScheduledDelivery(orderId,userId)` (model on `batchMarkAsCollected`): deduct stock + `recordSale` dated today + commission + cash-if-CASH + status `DELIVERED` + `delivered_at`.
- [ ] `POST /{id}/reschedule-delivery` (update date, append change_log, repeatable) + `POST /{id}/fulfill-delivery` (covers "Mark Delivered" and "Deliver now").
- [ ] `cancelOrder`: for `SCHEDULED_DELIVERY`, skip stock restore (nothing deducted).
- [ ] Add `'SCHEDULED_DELIVERY'` to every daily-report/dashboard `status NOT IN (...)` filter.
**Verify (clone):** create scheduled → 0 stock/sale, absent from daily report; reschedule ×2; fulfill → recorded today + DELIVERED; cancel → clean; deliver-now → recorded today; never appears in totals pre-resolution.
**Done when:** cancellation matrix + report-exclusion all pass.
**State after this session:** _(fill in)_
**Next session:** Session 4.

## Session 4 — Deferred Delivery frontend (→ Phase B ships)
**Goal:** create + manage scheduled deliveries.
- [ ] Order form (index.html:592–597 and Add-Records :2265): remove `<option value="DELIVERY">`; add "Schedule for delivery" checkbox → reveals date picker; submit includes `scheduledDeliveryDate`.
- [ ] "Order Deliveries" card: list SCHEDULED_DELIVERY orders (id, customer, total, date, reschedule count, **overdue flag**); actions Mark Delivered / Reschedule / Deliver now / Cancel.
- [ ] Oversell hint: per-product "scheduled/committed qty"; warn (non-blocking) at fulfillment if short.
**Verify (clone):** create via form (no stock/sale), reschedule, fulfill, cancel, deliver-now — all reflected correctly.
**Done when:** Phase B works end-to-end on the clone.
**State after this session:** _(fill in)_
**Next session:** Session 5.

## Session 5 — Integration, verification & deploy
**Goal:** ship both features to prod safely.
- [ ] Full clone E2E: both features + cancellation matrix + role gating + regression (normal orders still deduct/record).
- [ ] Deploy: `docker compose up -d --build` (V91/V92 auto-apply), redeploy frontend with cache-bust; confirm Flyway at V92 and app healthy.
- [ ] Commit + push (frontend + backend); update this checklist to all-done.
**Done when:** live prod healthy, both features verified, `main` in sync.
**State after this session:** _(fill in)_

---

## Design reference (carry-over detail)
- **Move direction:** per-line `from_warehouse`/`to_warehouse` (flexible: pull from WH1 or WH2 → Balagtas, or reverse, in one request). UI pre-fills defaults; each line shows product's per-WH stock to prevent input errors.
- **Deferred-delivery lifecycle:** `SCHEDULED_DELIVERY` is inert (excluded from inventory + sales + reports). Terminal states only: `DELIVERED` (recorded on the real day) or `CANCELLED` (never recorded). Reschedule is repeatable and distinct from cancel. Missed date → stays scheduled + "overdue", never auto-acts.
- **Migrations are additive/reversible** (new tables, new nullable columns, widened CHECK). Back up prod before each deploy.
- **Balagtas rule:** all new labels/movement reasons say "Balagtas"; never "Santan". Do not alter `company_address` or applied migrations.
