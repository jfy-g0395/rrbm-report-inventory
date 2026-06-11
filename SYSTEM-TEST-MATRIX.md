# RRBM Daily — System Test Matrix

> **Purpose:** End-to-end map of every frontend view → API endpoint → service → database table,
> with per-workflow expectations (entry point, actions, DB changes, API response, UI update).
> **No tests are written here** — this is the planning/coverage matrix that tests will be derived from.
>
> Generated from source inspection of `rrbm_frontend/rrbm-frontend/{index.html,js/app.js}` and
> `rrbm-backend/src/main/java/rrbm_backend/*`. Date: 2026-06-11.

---

## 1. Architecture Layers

| Layer | Where | Notes |
|-------|-------|-------|
| **Frontend** | Single-page app: `index.html` (4.4k lines) + `js/app.js` (12.5k lines) | 21 views switched via `navigateTo(view)`; raw `fetch(API_BASE + '/api/...')` calls with `Authorization: Bearer <jwt>` |
| **API** | `*Controller.java` (`@RestController`, base `/api/...`) | JWT auth via `JwtAuthFilter` + `SecurityConfig`; errors normalized by `GlobalExceptionHandler` |
| **Services** | `*Service.java` | Business logic + `@Transactional` boundaries |
| **Data** | `*Repository.java` → `@Entity`/`@Table` | Spring Data JPA over 30 tables |

### 1.1 Database tables (30)

`users` · `agents` · `agent_commissions` · `orders` · `order_items` · `order_id_counter` ·
`transactions` · `products` · `product_set_components` · `inventory_movements` · `daily_reports` ·
`activity_log` · `delivery_log` · `delivery_log_items` · `expenses` · `expense_items` ·
`expense_categories` · `payables` · `purchase_orders` · `po_items` · `po_year_counter` ·
`suppliers` · `supplier_product_mapping` · `commission_periods` · `commission_entries` ·
`commission_adjustments` · `master_keys` · `notification_emails` · `settings` · `import_commit_log`

### 1.2 Cross-cutting services (touched by many flows)

| Service | Responsibility | Writes to |
|---------|----------------|-----------|
| `ActivityLogService` | Audit trail (`log()`, daily close) | `activity_log` |
| `TransactionService` | Accounting ledger (sale/void/refund/adjustment/collection) | `transactions` |
| `InventoryService` | Stock deduction/restoration + movement audit | `products`, `inventory_movements` |
| `CommissionService` | Per-order commission entry creation/backfill | `commission_entries`, reads `commission_periods` |
| `MasterKeyService` | Validate/rotate master keys (daily close, void, cancel) | `master_keys` |
| `LoginAttemptService` | Brute-force lockout (in-memory, per identifier) | — |
| `JwtUtil` / `AuthService` | Token issue + identity extraction | reads `users` |
| `OrderIdGenerator` | Sequential order IDs (`DDMMYY-NNNNNN`) | `order_id_counter` |
| `PurchaseOrderService` | PO number generation (`PO-YYYY-NNNN`) | `po_year_counter` |

---

## 2. Master Matrix — View → Endpoint → Controller → Service → Tables

| # | Frontend View (`data-view`) | Render entry (`navigateTo`) | Primary Endpoints | Controller | Services | Tables (R/W) |
|---|------------------------------|-----------------------------|-------------------|------------|----------|--------------|
| 0 | **Login** (pre-app gate) | login form submit | `POST /api/auth/login`, `/logout`, `/verify-security-key` | `AuthController` | `AuthService`, `LoginAttemptService`, `JwtUtil`, `ActivityLogService` | `users` (R) |
| 1 | **Dashboard** `dash` | `renderDashboard`, `renderTopProductsToday`, `loadProductAnalytics` | `GET /api/dashboard/{stats,top-products-today,channel-summary,product-analytics,cashflow}` | `DashboardController` | — (repo aggregation) | `orders`,`order_items`,`products`,`daily_reports`,`expenses`,`payables`,`transactions`,`commission_periods/entries/adjustments` (R) |
| 2 | **New Order** `new` | `initOrderForm`/`loadProducts`, `renderOrders` | `GET /api/products/all`, `POST /api/orders`, `GET /api/agents` | `OrderController`,`ProductController`,`AgentController` | `OrderService`,`TransactionService`,`InventoryService`,`CommissionService`,`ActivityLogService` | **W:** `orders`,`order_items`,`order_id_counter`,`transactions`,`products`,`inventory_movements`,`commission_entries`,`activity_log` · **R:** `agents`,`users` |
| 3 | **Order List** `list` | `renderOrderList` | `GET /api/orders/today`, `PUT /api/orders/{id}/status`, `POST /api/orders/{id}/cancel`, `/void`, `/return` | `OrderController` | `OrderService`,`TransactionService`,`InventoryService`,`MasterKeyService`,`ActivityLogService` | **W:** `orders`,`order_items`,`transactions`,`products`,`inventory_movements`,`activity_log` |
| 4 | **Inventory** `inv` | `renderInventory` | `GET /api/products/all`, `PATCH /api/products/{id}`, `/{id}/tag`, `GET /categories`,`/sub-categories` | `ProductController` | `InventoryService`,`MasterKeyService`,`ActivityLogService` | **W:** `products`,`product_set_components`,`inventory_movements`,`activity_log` |
| 5 | **Receive Stock** `delivery` | `initDeliveryForm` | `GET /api/purchase-orders`, `POST /api/products/delivery` | `ProductController`,`PurchaseOrderController` | `InventoryService` | **W:** `delivery_log`,`delivery_log_items`,`products`,`inventory_movements`,`payables` · **R:** `purchase_orders`,`po_items` |
| 6 | **Rejected Items** `rejected-items` | `initRejectedItemsView` | `GET /api/reports/rejected-items` | `DailyReportController` | — | `delivery_log_items`,`delivery_log` (R) |
| 7 | **Purchase Orders** `purchase-orders` | `loadPurchaseOrders` | `GET/POST /api/purchase-orders`, `PATCH /{id}/status` | `PurchaseOrderController` | `PurchaseOrderService`,`ActivityLogService` | **W:** `purchase_orders`,`po_items`,`po_year_counter`,`activity_log` · **R:** `supplier_product_mapping` |
| 8 | **Monthly Report** `rep` | `initReportsView` | `GET /api/reports/{insights-summary,accounting-summary,source-breakdown,top-agents,top-dates,pizza-summary,non-pizza-summary,daily-order-summary,hot-selling,delivery-fees,expense-breakdown,ecommerce-breakdown}` | `ReportsController` | `TransactionService` | `orders`,`order_items`,`transactions`,`daily_reports`,`users` (R) |
| 9 | **Daily Reports** `daily-reports` | `loadDailyReports` | `GET /api/reports/daily-reports-list`, `/daily/{date}`, `POST /api/reports/close-daily`, `GET /daily-status` | `DailyReportController` | `DailyReportService`,`MasterKeyService`,`ActivityLogService`,`InventoryService` | **W:** `daily_reports`,`activity_log` · **R:** `orders`,`transactions`,`inventory_movements`,`products` |
| 10 | **Order History** `order-history` | `renderOrderHistory` | `GET /api/orders/history`, `/search` | `OrderController` | `OrderService` | `orders`,`order_items` (R) |
| 11 | **Collections** `collections` | `loadCollections` | `GET /api/orders/collections`, `PATCH /api/orders/{id}/collect`, `POST /batch-mark-collected` | `OrderController` | `TransactionService`,`CommissionService`,`ActivityLogService` | **W:** `orders`,`transactions`,`commission_entries`,`daily_reports`,`activity_log` |
| 12 | **Delivery Reports** `delivery-rep` | `renderDeliveryReports` | `GET /api/delivery-reports`, `/{id}`, `PATCH /{id}/cancel` | `DeliveryReportController` | `MasterKeyService` | **W (cancel):** `delivery_log`,`products`,`inventory_movements`,`payables`,`po_items` · **R:** `delivery_log_items` |
| 13 | **Activity Log** `activity-log` | `renderActivityLog` | `GET /api/activity-log/today`, `/{date}` | `ActivityLogController` | `ActivityLogService` | `activity_log` (R) |
| 14 | **Agent Registry** `agents` | `loadAgents` | `GET/POST /api/agents`, `PUT /{id}`, `PATCH /{id}/status`, `GET /{id}/performance`, `/{id}/orders` | `AgentController` | `ActivityLogService` | **W:** `agents`,`activity_log` · **R:** `agent_commissions`,`commission_entries/periods/adjustments`,`orders` |
| 15 | **Import** `import` | `loadImportHistory` | `POST /api/import/{authorize,validate,upload/sales,upload/expenses,upload/combined,commit,close}`, `GET /template/*`, `/history*` | `ImportController` | `OrderService`,`DailyReportService`,`CommissionService`,`TransactionService`,`InventoryService`,`ActivityLogService` | **W:** `orders`,`order_items`,`expenses`,`expense_items`,`transactions`,`products`,`inventory_movements`,`commission_entries`,`daily_reports`,`import_commit_log`,`activity_log` · **R:** `agents`,`expense_categories` |
| 16 | **Transaction Ledger** `transactions` | `loadTransactions` | `GET /api/transactions/{ledger,ledger/report,accounting-summary,order/{id},date-range}`, `POST /adjustment` | `TransactionController` | `TransactionService` | **W:** `transactions` · **R:** `orders` |
| 17 | **Employee List** `emp` | `renderUsers` | `GET/POST /api/users`, `PUT /{id}`, `PATCH /{id}/{role,status,security-key,permissions,change-password}`, `DELETE /{id}` | `UserController` | `ActivityLogService` | **W:** `users`,`activity_log` |
| 18 | **Expenses** `expenses` | `initExpensesView` | `GET /api/expenses`,`/range`,`/summary`, `GET /api/expense-categories`, `POST /api/expenses`, `POST /{id}/void`, `GET /report/*`,`/export` | `ExpenseController`,`ExpenseCategoryController` | `ActivityLogService` | **W:** `expenses`,`expense_items`,`activity_log` · **R:** `expense_categories`,`settings` |
| 19 | **Payables** `payables` | `loadPayables` | `GET /api/payables`,`/{id}`,`/summary`, `PATCH /{id}/status`, `DELETE /{id}` | `PayableController` | `MasterKeyService`,`ActivityLogService` | **W:** `payables`,`activity_log` · **R:** `delivery_log_items` |
| 20 | **Suppliers** `suppliers` | `loadSuppliers` | `GET/POST /api/suppliers`, `PATCH/DELETE /{id}`, `GET/POST /{supplierId}/mappings`, `PATCH/DELETE /mappings/{id}` | `SupplierController` | `ActivityLogService` | **W:** `suppliers`,`supplier_product_mapping`,`activity_log` · **R:** `products` |
| 21 | **Settings** `set` *(Super Admin only)* | `loadSettings`,`loadMasterKeys`,`loadNotificationEmails` | `GET/POST /api/settings`, `GET/POST/DELETE /api/auth/master-keys`, `GET/POST/DELETE /api/settings/notification-emails` | `SettingsController`,`AuthController`,`NotificationEmailController` | `MasterKeyService`,`ActivityLogService` | **W:** `settings`,`master_keys`,`notification_emails`,`activity_log` |

**Access control gates (in `navigateTo`):** `set` → Super Admin only; `emp` → managers+; all views filtered by per-user `allowedPages` (`canAccessPage`).

---

## 3. Workflow Specifications

Each workflow: **Entry point → Expected actions → Expected DB changes → Expected API response → Expected UI updates.**

### W-0 · Authentication / Login
- **Entry point:** Login form (pre-app); `POST /api/auth/login {identifier, password}`.
- **Expected actions:** `LoginAttemptService.isBlocked()` check → `AuthService.login()` verifies credentials (`passwordEncoder.matches`) → on success `JwtUtil` issues token + `LoginAttemptService.recordSuccess()`; on failure `recordFailure()`.
- **Expected DB changes:** None (read-only on `users`). In-memory attempt counter only.
- **Expected API response:** `200 {token, user:{id,fullName,role,allowedPages,...}}`; `401` invalid credentials; `429`/locked message after N failures (`secondsUntilUnlock`).
- **Expected UI updates:** Token stored; app shell renders; nav filtered by role + `allowedPages`; redirect to Dashboard.

### W-1 · Dashboard load
- **Entry point:** `navigateTo('dash')` → `renderDashboard` + `renderTopProductsToday` + `loadProductAnalytics`.
- **Expected actions:** Parallel GETs to `dashboard/stats`, `top-products-today`, `channel-summary`, `product-analytics`, `cashflow`. Pure aggregation; period param (`daily`/etc.) drives ranges.
- **Expected DB changes:** None (read-only).
- **Expected API response:** `200` JSON aggregates (totals, KPI cards, chart series).
- **Expected UI updates:** KPI cards, charts (re-themed on theme toggle), top-products table populate; empty/zero states when no data.

### W-2 · Create Order *(core transactional flow)*
- **Entry point:** New Order view → `addOrder()` → `POST /api/orders` (`CreateOrderRequest`).
- **Expected actions:**
  1. `OrderController` validates payload (name, ≥1 item, qty>0, unitPrice>0, productId exists). Agent linking if `agentId` (reject INACTIVE/missing). Computes per-item `opAmount = opPerUnit × qty`.
  2. `OrderService.createOrder` (`@Transactional`): validate `source`, generate order ID (`OrderIdGenerator` → `order_id_counter`), set creator, `calculateTotals()`, COD ⇒ status `PENDING`.
  3. `orderRepository.save` (items cascade) → `ActivityLogService.log(CREATE_ORDER)` → `TransactionService.recordSale` → `InventoryService.deductStockForOrder`.
  4. Best-effort `CommissionService.createEntriesForOrder` (only when `agentId` set; idempotent via `existsByOrderId`; assigns to covering OPEN period, else earliest OPEN).
- **Expected DB changes:**
  - `orders` +1, `order_items` +N, `order_id_counter` incremented.
  - `transactions` +1 SALE (`SALE-{id}`).
  - `products` stock decremented per warehouse; `inventory_movements` +N (`SALE`/deduction).
  - `commission_entries` +N when agent-linked & items have `opAmount`.
  - `activity_log` +1 (`CREATE_ORDER`).
  - **Atomicity:** any failure rolls back order + sale + stock together; commission entry is best-effort (outside rollback).
- **Expected API response:** `201 OrderResponse{id, total, status, items...}`; `400 {message}` on validation/source/agent/stock failure; `401` no token.
- **Expected UI updates:** Toast success; order appears in today's list; form resets; stock figures reflect deduction on next inventory load.

### W-3 · Order status change / cancel / void / return
- **Entry point:** Order List actions → `PUT /api/orders/{id}/status`, `POST /api/orders/{id}/cancel`, `/void`, `/return`, `/replacement`, `/cancel-for-replacement`.
- **Expected actions:**
  - **Status:** `OrderService.updateStatus` enforces allowed transitions (ACTIVE↔PENDING↔PENDING_COLLECTION→DELIVERED; cannot mutate CANCELLED).
  - **Cancel:** requires master/security key; `cancelOrder` → restore stock, reverse ledger.
  - **Void item:** `voidOrderItems` + `TransactionService.recordItemVoid` + `InventoryService.restoreStockForVoidedItem` (sellable vs rejected disposition).
  - **Return:** `processReturn` + `recordReturnRefund` + `processReturnForItem`.
- **Expected DB changes:** `orders`/`order_items` status fields; `transactions` +VOID/REFUND/ADJUSTMENT (net-basis, see commit `e78dd9c`); `products` stock restored; `inventory_movements` +; `activity_log` +.
- **Expected API response:** `200` updated order; `400` invalid transition / missing key; `403` bad key.
- **Expected UI updates:** Row status badge updates; ledger + dashboard reflect reversal; stock restored.

### W-4 · Inventory edit / product tag
- **Entry point:** Inventory view → `PATCH /api/products/{id}`, `PATCH /api/products/{id}/tag`, `POST /api/products`.
- **Expected actions:** Field updates; tag/category mutation; set-component edits (`product_set_components`); master/security key may gate destructive edits.
- **Expected DB changes:** `products` updated; `product_set_components` for set products; `inventory_movements` for manual adjustments; `activity_log` +.
- **Expected API response:** `200` updated product; `400/403` on validation/key.
- **Expected UI updates:** Inventory table row refresh; low-stock highlighting recomputed.

### W-5 · Receive Stock (delivery / stock-in)
- **Entry point:** Receive Stock view → `POST /api/products/delivery` (`DeliveryRequest`); PO dropdown from `GET /api/purchase-orders`.
- **Expected actions:** Validate DR number format + duplicate (`existsByReceiptNumber`). Build `DeliveryLog`; per item add `received` to chosen warehouse, save product, `InventoryService.logMovement(RESTOCK)`, build `DeliveryLogItem` (qty/received/rejected/unitCost). Accrues `totalCost`; links PO if `poNumber` supplied; creates `payables` for the supplier invoice.
- **Expected DB changes:** `delivery_log` +1, `delivery_log_items` +N, `products` stock incremented, `inventory_movements` +N (`RESTOCK`), `payables` +1 (supplier liability), `po_items`/`purchase_orders` reconciled when DR linked to a PO.
- **Expected API response:** `200` delivery summary; `400` on bad/duplicate DR or empty items.
- **Expected UI updates:** Success toast; stock levels rise on inventory; new entry in Delivery Reports; rejected items surface in Rejected Items view; payable appears in Payables.

### W-6 · Rejected Items view
- **Entry point:** `GET /api/reports/rejected-items`.
- **Expected DB changes:** None (read of `delivery_log_items` where `rejectedQty > 0`).
- **Expected API response/UI:** `200` list; table of rejected qty by product/DR; empty state when none.

### W-7 · Purchase Orders
- **Entry point:** PO view → `POST /api/purchase-orders`, `PATCH /api/purchase-orders/{id}/status`.
- **Expected actions:** `PurchaseOrderService.generatePoNumber` (`PO-YYYY-NNNN` via `po_year_counter`); persist PO + items; supplier-product mapping lookup for pricing; status lifecycle (DRAFT→SENT→RECEIVED/CANCELLED).
- **Expected DB changes:** `purchase_orders` +1/update, `po_items` +N, `po_year_counter` incremented, `activity_log` +.
- **Expected API response:** `201`/`200` PO with generated number; `400` validation.
- **Expected UI updates:** PO list refresh; status badge; PO available in Receive Stock dropdown.

### W-8 · Monthly Report
- **Entry point:** `initReportsView` → batch `GET /api/reports/*`.
- **Expected DB changes:** None (read aggregation over `orders`,`transactions`,`daily_reports`).
- **Expected API response/UI:** `200` per section; charts/tables for source breakdown, top agents/dates, pizza vs non-pizza, hot-selling, delivery fees, expense breakdown, e-commerce breakdown.

### W-9 · Close Daily Sales *(critical snapshot flow)*
- **Entry point:** Daily Reports → `POST /api/reports/close-daily {masterKey, forceClose?, adminSecurityKey?, superAdminSecurityKey?}`.
- **Expected actions:** Validate master key (`MasterKeyService`) + JWT identity. `DailyReportService.closeDailySales(today)`: if open/active orders exist and not `forceClose` → throw `OpenOrdersException` (409). On close: snapshot gross/net/revenue, order counts, unfulfilled count+amount; `ActivityLogService.closeLogsForDate`; inventory/movement reconciliation.
- **Expected DB changes:** `daily_reports` +1 (snapshot row, idempotent per date), `activity_log` rows marked closed for the date.
- **Expected API response:** `200 {message, report, unfulfilledOrders?, unfulfilledAmount?}`; `409 {error:ACTIVE_ORDERS, count, amount}`; `403` bad master key; `401` no token; `400` already closed.
- **Expected UI updates:** Success → report appears in Daily Reports list; force-close modal shown on 409; dashboard "closed" status flips (`GET /daily-status`).

### W-10 · Order History / search
- **Entry point:** `GET /api/orders/history?start&end`, `/search?name`.
- **Expected DB changes:** None.
- **Expected UI updates:** Filtered/exportable table; default range = last 30d → yesterday.

### W-11 · Collections (deferred-payment collect) *(critical ledger flow)*
- **Entry point:** Collections view → `PATCH /api/orders/{id}/collect {securityKey}` or `POST /api/orders/batch-mark-collected`.
- **Expected actions:** Verify caller's **personal admin security key** (`passwordEncoder.matches` against `adminSecurityKey`). Pessimistic lock (`findByIdForUpdateWithItems`). Require status `PENDING_COLLECTION` or `PENDING`. Set `DELIVERED`, `collectedAt`, `collectedBy`.
  - **Only if was `PENDING_COLLECTION`** (force-closed path): `TransactionService.recordCollectionSale` (COLL-SALE restores revenue at original date), `CommissionService.createEntriesForOrder` (idempotent), and **patch the closed `daily_reports` snapshot** (add gross/net/revenue, +1 fulfilled, −1 unfulfilled, reduce unfulfilledAmount).
  - **Direct COD (was `PENDING`)**: no new SALE (original SALE-{id} still live) — avoids double counting.
- **Expected DB changes:** `orders` (status/collectedAt/collectedBy); conditionally `transactions` +COLL-SALE, `commission_entries` +N, `daily_reports` snapshot patched; `activity_log` +1 (`ORDER_COLLECT`).
- **Expected API response:** `200` updated order; `400` not collectable / no key; `403` invalid/absent admin security key; `401` no token.
- **Expected UI updates:** Order leaves Collections list; revenue restored on dashboard/daily report; toast success.

### W-12 · Delivery Reports / cancel a delivery
- **Entry point:** `GET /api/delivery-reports`; `PATCH /api/delivery-reports/{id}/cancel` (master-key gated).
- **Expected actions:** List received deliveries; cancel reverses stock-in: subtract received qty, log reversal movement, void/adjust linked payable + PO reconciliation.
- **Expected DB changes (cancel):** `delivery_log` status, `products` stock reduced back, `inventory_movements` + reversal, `payables` voided, `po_items` reverted.
- **Expected API response:** `200`; `403` bad key; `400` already cancelled.
- **Expected UI updates:** Status badge → cancelled; stock corrected; payable removed.

### W-13 · Activity Log
- **Entry point:** `GET /api/activity-log/today`, `/{date}`.
- **Expected DB changes:** None (read).
- **Expected UI updates:** Chronological audit table; date filter.

### W-14 · Agent Registry
- **Entry point:** Agents view → `POST /api/agents`, `PUT /{id}`, `PATCH /{id}/status`, `GET /{id}/performance`, `/{id}/orders`.
- **Expected actions:** Create/update agent; status toggle (ACTIVE/INACTIVE — INACTIVE blocks order linking); performance aggregates from commission entries/periods + orders.
- **Expected DB changes:** `agents` +1/update, `activity_log` +. Reads `commission_entries/periods/adjustments`, `agent_commissions`, `orders`.
- **Expected API response:** `200/201` agent; performance JSON; `400` validation.
- **Expected UI updates:** Agent list/cards; performance panel (orders, commission totals); status badge.

### W-15 · Import (CSV sales/expenses) *(multi-step, master-key gated)*
- **Entry point:** Import view. Flow: `POST /authorize` → `POST /upload/sales|expenses|combined` (parse+stage) → `POST /validate` → `POST /commit` → `POST /close`.
- **Expected actions:** Authorize via master key. Upload parses CSV → preview rows. Validate checks products/agents/categories/dates. Commit (`@Transactional`): per-row `OrderService.createOrderAtDate` (backdated IDs + ledger date), expense creation, `InventoryService.deductStockForOrder`, `CommissionService.createEntriesForOrder` (late-import → earliest OPEN period), `DailyReportService.closeForImportDate`. Writes `import_commit_log` for idempotency/audit. Close finalizes batch.
- **Expected DB changes:** `orders`+`order_items`, `expenses`+`expense_items`, `transactions` (dated), `products` stock, `inventory_movements`, `commission_entries`, `daily_reports` (closed for import dates), `import_commit_log`, `activity_log`.
- **Expected API response:** Per step — `200` with staged preview / validation errors / commit summary (imported, failed, errors[]); `403` bad master key.
- **Expected UI updates:** Wizard advances; validation error highlights; commit summary; new batch in `GET /history/batch` + `/history`.

### W-16 · Transaction Ledger / adjustment
- **Entry point:** `GET /api/transactions/ledger`, `/ledger/report`, `/accounting-summary`, `/order/{id}`; `POST /api/transactions/adjustment`.
- **Expected actions:** Read filtered ledger; manual `recordAdjustment` posts a balancing entry tied to an order.
- **Expected DB changes:** `transactions` +1 on adjustment (else read-only).
- **Expected API response:** `200` ledger/summary; `201/200` adjustment; `400` validation.
- **Expected UI updates:** Ledger table + running totals; new adjustment row; dashboard/report figures reflect it.

### W-17 · Employee management
- **Entry point:** Employee List → `POST /api/users`, `PUT /{id}`, `PATCH /{id}/{role,status,security-key,permissions,change-password}`, `DELETE /{id}`.
- **Expected actions:** CRUD users; role/status changes (manager+ gate); per-user `allowedPages` permissions; security-key assignment (hashed); password change (hashed).
- **Expected DB changes:** `users` +1/update/soft-or-hard delete; `activity_log` +.
- **Expected API response:** `200/201` user (no password hash leaked); `403` insufficient role; `400` validation/duplicate.
- **Expected UI updates:** User table refresh; role/status badges; permission checkboxes persist; deleted user removed.

### W-18 · Expenses
- **Entry point:** Expenses view → `POST /api/expenses`, `POST /api/expenses/{id}/void`; reads `GET /api/expenses`,`/range`,`/summary`,`/report/{daily,weekly,monthly}`,`/export`, categories from `GET /api/expense-categories`.
- **Expected actions:** Create expense (header + line items), category linkage, settings-driven defaults; void marks expense voided (no hard delete).
- **Expected DB changes:** `expenses` +1/void, `expense_items` +N, `activity_log` +; reads `expense_categories`, `settings`.
- **Expected API response:** `200/201` expense; report aggregates; CSV export; `400` validation.
- **Expected UI updates:** Expense list + totals; void greys row; reports/dashboard expense figures update.

### W-19 · Payables
- **Entry point:** Payables view → `GET /api/payables`,`/summary`; `PATCH /api/payables/{id}/status`; `DELETE /api/payables/{id}`.
- **Expected actions:** List supplier liabilities (sourced from deliveries); status change (UNPAID→PAID, master-key gated); delete.
- **Expected DB changes:** `payables` status/delete; `activity_log` +; reads `delivery_log_items`.
- **Expected API response:** `200`; `403` bad key; `400`.
- **Expected UI updates:** Payable row status; outstanding-balance summary; dashboard cashflow reflects paid.

### W-20 · Suppliers + product mappings
- **Entry point:** Suppliers view → `POST/PATCH/DELETE /api/suppliers/{id}`; `GET/POST /api/suppliers/{supplierId}/mappings`; `PATCH/DELETE /mappings/{mappingId}`.
- **Expected actions:** CRUD suppliers; map supplier↔product with unique constraint `uq_supplier_product` (supplier_id, product_id) + supplier-specific cost.
- **Expected DB changes:** `suppliers` +1/update/delete, `supplier_product_mapping` +/update/delete (unique violation → 400), `activity_log` +; reads `products`.
- **Expected API response:** `200/201`; `400` duplicate mapping/validation.
- **Expected UI updates:** Supplier list; mapping table; mappings feed PO pricing.

### W-21 · Settings (Super Admin only)
- **Entry point:** Settings view → `GET/POST /api/settings`; master keys `GET/POST/DELETE /api/auth/master-keys`; notification emails `GET/POST/DELETE /api/settings/notification-emails`.
- **Expected actions:** Update system settings; rotate/add/remove master keys (hashed, `MasterKeyService`); manage low-stock notification recipients (feeds `LowStockEmailService`).
- **Expected DB changes:** `settings` upsert, `master_keys` +/deactivate, `notification_emails` +/delete, `activity_log` +.
- **Expected API response:** `200/201`; `403` non-Super-Admin; `400` validation.
- **Expected UI updates:** Settings persist; master-key list (active only); email recipient list; nav hides Settings for non-Super-Admins.

---

## 4. High-Risk Integration Points (priority test targets)

| Risk | Workflows | Why it matters |
|------|-----------|----------------|
| **Transactional atomicity** | W-2, W-3, W-15 | Order save + ledger + stock must roll back together; commission is best-effort outside the tx |
| **Ledger double-counting** | W-11 (collect), W-9 (close), W-3 (void/return) | COLL-SALE vs live SALE branching; net-basis reversal (commit `e78dd9c`) |
| **Snapshot reconciliation** | W-9 ↔ W-11 | Collecting a force-closed order must patch the already-closed `daily_reports` row |
| **Idempotency** | W-2/W-11 commission, W-15 import | `existsByOrderId` guard; `import_commit_log`; duplicate DR (`existsByReceiptNumber`) |
| **Concurrency** | W-11 | Pessimistic lock `findByIdForUpdateWithItems` on collect |
| **Key-gated actions** | W-3, W-5/W-12, W-9, W-11, W-19, W-21 | Master key vs per-user admin security key vs super-admin key — distinct verification paths |
| **Backdating** | W-15 | `createOrderAtDate` sets order-ID prefix + `createdAt` + dated ledger entry + late-import commission period assignment |
| **Authorization** | W-17, W-21, all views | Role + `allowedPages` gating must be enforced server-side, not just in `navigateTo` |

---

## 5. Coverage Checklist (for test derivation — not yet written)

- [ ] **Unit (services):** `OrderService`, `TransactionService`, `InventoryService`, `CommissionService`, `DailyReportService`, `MasterKeyService`, `LoginAttemptService`, `AuthService`, `OrderIdGenerator`, `PurchaseOrderService`.
- [ ] **Integration (controller + DB):** one happy-path + auth-failure + validation-failure per endpoint in §2 (~120 endpoints).
- [ ] **Transactional rollback:** force failure mid-`createOrder`/`commit` and assert no partial rows.
- [ ] **E2E (frontend → API → DB):** the 22 workflows in §3, asserting all five facets (entry/action/DB/response/UI).
- [ ] **Security:** JWT required, role/`allowedPages` enforcement, key-verification matrix (§4), brute-force lockout.

---

## 6. Coverage Gap Analysis (matrix vs. actual tests)

**Method:** cross-referenced §2/§3 against the 36 backend test files in
`rrbm-backend/src/test/java/rrbm_backend/` (verbs/paths extracted from `MockMvc` calls and
service-method references). **Frontend test suite: none exists** — no `*.spec.js`, `*.test.js`,
Jest/Playwright/Cypress config anywhere. So every UI assertion in §3 (the "Expected UI updates"
column) is currently **unverified by automation**.

**What *is* covered:** Agents (CRUD + status + performance), Commission periods (create/list/get +
close/release/adjustments/pay lifecycle), Expenses (create/void/summary/reports/export + categories),
Import (authorize/upload-sales/validate/commit/history), Users (create/update/get + role gate),
Order **create** (`POST /api/orders`) and **collect/batch-collect** (via `ImportU6Test`),
`GET /api/transactions/ledger[/report]`, `GET /api/dashboard/cashflow`. Plus service-level unit tests:
`CancelOrderM26Test` (cancel guard — mocked deps), `PhantomDebitIntegrationTest`,
`LoginAttemptServiceTest`, `BcryptVerifyTest`, `GlobalExceptionHandlerTest`.

> ⚠️ Caveat: most "covered" controller tests assert happy-path + a few edge cases. The **401-no-token**
> and **403-wrong-key** branches are largely unexercised even on tested endpoints (only
> `/api/agents/{id}/performance` has an explicit "without JWT" test).

### 6.1 Untested routes

Grouped by controller. **✗ = no test references the route at all.** (~95 of ~120 endpoints untested.)

| Controller | Untested routes |
|------------|-----------------|
| **AuthController** | ✗ `POST /login` · ✗ `/logout` · ✗ `/verify-password` · ✗ `/master-key` · ✗ `GET/POST /master-keys` · ✗ `DELETE /master-keys/{id}` · ✗ `/verify-security-key` · ✗ `/verify-superadmin-key` — **entire controller untested** |
| **OrderController** | ✗ `POST /batch` · ✗ `GET /today` · ✗ `GET /` · ✗ `GET /{id}` · ✗ `PUT /{id}/status` · ✗ `POST /{id}/cancel`¹ · ✗ `GET /history` · ✗ `GET /search` · ✗ `GET /collections` · ✗ `POST /{id}/void` · ✗ `POST /{id}/return` · ✗ `POST /{id}/replacement` · ✗ `POST /{id}/cancel-for-replacement`¹ |
| **DailyReportController** | ✗ `POST /close-daily` · ✗ `/daily-status` · ✗ `/daily/{date}` · ✗ `/range` · ✗ `/activity-log/today` · ✗ `/activity-log/{date}` · ✗ `/deliveries[/{date}]` · ✗ `/rejected-items` — **entire controller untested** |
| **ReportsController** | ✗ all 13: `insights-summary`, `accounting-summary`, `source-breakdown`, `top-agents`, `top-dates`, `pizza-summary`, `non-pizza-summary`, `daily-order-summary`, `hot-selling`, `delivery-fees`, `expense-breakdown`, `ecommerce-breakdown`, `daily-reports-list` — **entire controller untested** |
| **ProductController** | ✗ `GET /` · ✗ `/search` · ✗ `/categories` · ✗ `/sub-categories` · ✗ `/all` · ✗ `POST /` · ✗ `PATCH /{id}/tag` · ✗ `PATCH /{id}` · ✗ `GET /{id}/suppliers` · ✗ `POST /delivery` — **entire controller untested** |
| **SupplierController** | ✗ all 9 (CRUD + `/{supplierId}/mappings` CRUD) — **entire controller untested** |
| **PurchaseOrderController** | ✗ `GET /` · ✗ `GET /{id}` · ✗ `POST /` · ✗ `PATCH /{id}/status` — **entire controller untested** |
| **PayableController** | ✗ `GET /` · ✗ `GET /{id}` · ✗ `/summary` · ✗ `PATCH /{id}/status` · ✗ `DELETE /{id}` — **entire controller untested** |
| **DeliveryReportController** | ✗ `GET /` · ✗ `GET /{id}` · ✗ `PATCH /{id}/cancel` — **entire controller untested** |
| **SettingsController** | ✗ `GET /` · ✗ `POST /` — **entire controller untested** |
| **NotificationEmailController** | ✗ `GET /` · ✗ `POST /` · ✗ `DELETE /{id}` — **entire controller untested** |
| **ActivityLogController** | ✗ `/today` · ✗ `/{date}` · ✗ `GET /` — **entire controller untested** (writes exercised indirectly via other tests, but read endpoints never hit) |
| **DashboardController** | ✗ `/stats` · ✗ `/top-products-today` · ✗ `/channel-summary` · ✗ `/product-analytics` (only `/cashflow` tested) |
| **TransactionController** | ✗ `POST /adjustment` · ✗ `/order/{id}` · ✗ `/date-range` · ✗ `/accounting-summary` (only `/ledger` + `/ledger/report` tested) |
| **CommissionController** | ✗ `GET /periods/{id}/agents/{agentId}/statement/export` (statement export CSV) — lifecycle otherwise covered |

¹ `cancel` / `cancel-for-replacement` have **service-level unit tests** (`CancelOrderM26Test`) with mocked
`transactionService`/`inventoryService`, but **no controller/endpoint test** — the HTTP layer, key gating, and real DB writes are unexercised.

### 6.2 Untested database writes

Writes the matrix says happen, with no test that asserts the row actually changes:

| Table | Write path | Status |
|-------|-----------|--------|
| `master_keys` | add / rotate / deactivate (`MasterKeyService`) | ✗ no test — `validateMasterKey` never called in any test |
| `notification_emails` | add / delete | ✗ untested |
| `settings` | upsert (`POST /api/settings`) | ✗ untested |
| `suppliers`, `supplier_product_mapping` | CRUD + unique-constraint (`uq_supplier_product`) | ✗ untested |
| `purchase_orders`, `po_items`, `po_year_counter` | PO create + `generatePoNumber` | ✗ untested |
| `delivery_log`, `delivery_log_items` | stock-in (`POST /api/products/delivery`) | ✗ untested |
| `products` (stock **increment**) | delivery restock + inventory PATCH | ✗ untested (only **decrement** via `POST /api/orders` is exercised) |
| `inventory_movements` (`RESTOCK`) | delivery | ✗ untested (only deduction movement via order create is exercised) |
| `payables` | created on delivery; `PATCH status`; delete | ✗ untested |
| `daily_reports` (close snapshot) | `closeDailySales` | ✗ untested — **highest-risk gap** (W-9 snapshot logic + force-close 409 path) |
| `daily_reports` (collect patch) | revenue restore on `PENDING_COLLECTION` collect | ⚠ partial — collect is hit in `ImportU6Test` but the snapshot-patch field math (gross/net/unfulfilled reconciliation) is not asserted |
| `transactions` — `recordAdjustment` | `POST /api/transactions/adjustment` | ✗ untested |
| `transactions` — `recordReturnRefund` | `POST /api/orders/{id}/return` | ✗ untested |
| `transactions` — `recordVoid` | `POST /api/orders/{id}/void` + cancel | ⚠ verified only as a **mock interaction** (`verify(...).recordVoid`), never a real persisted row |
| `product_set_components` | set-product edits | ✗ untested |
| `commission_entries` (on order create) | `createEntriesForOrder` | ⚠ indirect — exercised via import/agent tests but the best-effort-outside-tx behavior (order persists even if commission fails) is not asserted |
| **Transactional rollback** | partial-failure in `createOrder` / import `commit` | ✗ no test forces a mid-transaction failure to prove atomicity |

### 6.3 Untested forms

No frontend tests exist, so **every form is unverified at the UI layer**. Below = whether the **backing endpoint** has *any* server-side test:

| Form (view) | Backing endpoint | Server-side test? |
|-------------|------------------|-------------------|
| Login (`W-0`) | `POST /api/auth/login` | ✗ none |
| New Order (`new`) | `POST /api/orders` | ✓ happy-path only (no FE validation, agent-link reject, or stock-fail test) |
| Receive Stock (`delivery`) | `POST /api/products/delivery` | ✗ none |
| Purchase Order (`purchase-orders`) | `POST /api/purchase-orders` | ✗ none |
| Supplier + mapping (`suppliers`) | `POST /api/suppliers`, `/mappings` | ✗ none |
| Expense (`expenses`) | `POST /api/expenses` | ✓ covered |
| Agent (`agents`) | `POST/PUT /api/agents` | ✓ covered |
| Employee (`emp`) | `POST/PUT /api/users` | ✓ covered (incl. role gate) |
| Settings / Master Key / Notification Email (`set`) | `POST /api/settings`, `/auth/master-keys`, `/notification-emails` | ✗ none |
| Close-Daily modal (master key) | `POST /api/reports/close-daily` | ✗ none |
| Collect modal (admin security key) | `PATCH /api/orders/{id}/collect` | ⚠ happy-path via `ImportU6`; wrong-key 403 path untested |
| Void / Return / Cancel modals (security/master key) | `POST /api/orders/{id}/{void,return,cancel}` | ✗ none at endpoint (cancel guard only, mocked) |
| Payable status / delete (master key) | `PATCH/DELETE /api/payables/{id}` | ✗ none |
| Import wizard (master key + CSV) | `POST /api/import/*` | ✓ covered (authorize→upload→validate→commit) |

### 6.4 Untested auth paths

| Auth mechanism | Where enforced | Test status |
|----------------|----------------|-------------|
| **Login + JWT issuance** | `POST /api/auth/login` → `AuthService` + `JwtUtil` | ✗ endpoint untested (tests *generate* JWTs via `JwtUtil` to authenticate, but never exercise login itself) |
| **Brute-force lockout** | `LoginAttemptService` | ✓ unit-tested (`LoginAttemptServiceTest`) — ✗ but never via the login endpoint (integration) |
| **Missing/invalid token (401)** | `JwtAuthFilter` on all `/api/**` | ⚠ only `/api/agents/{id}/performance` tests the no-JWT path; all other ~119 endpoints' 401 branch untested |
| **Expired/malformed token** | `JwtUtil.extractUserId` | ✗ untested |
| **Master-key gate** | close-daily, void, cancel, payable status, delivery cancel, import | ✗ `MasterKeyService.validateMasterKey` never invoked in any test; the 403-bad-master-key branch is untested everywhere |
| **Per-user admin security key** | collect, cancel (`passwordEncoder.matches(adminSecurityKey)`) | ⚠ key is *set up* in `ImportU6`/agent tests; the **invalid-key 403** and **no-key-set 403** branches are untested |
| **Super-admin key** | `verify-superadmin-key`, force-close override, Settings access | ✗ untested |
| **`verify-password` / `verify-security-key`** | step-up confirmation endpoints | ✗ untested |
| **Role gating** | manager-only (`emp`), super-admin-only (`set`) | ⚠ `UserCreateUpdateGateTest` covers the user create/update role gate; Settings/Employee *view* gates and other role checks untested |
| **`allowedPages` per-user authorization** | server-side page access | ✗ untested — enforcement is currently **only client-side** in `navigateTo` (see §4); no server test confirms the API rejects an out-of-scope page's endpoints |

### 6.5 Top remediation priorities

1. **AuthController** — 0% coverage on the entire auth surface (login, master keys, key verification). Highest blast radius.
2. **`close-daily` (W-9)** — untested snapshot + force-close 409 + master-key gate; financially critical.
3. **Master-key / security-key gates** — the 403 branches are untested across *every* gated route.
4. **Stock-in chain (W-5)** — delivery → `products`/`inventory_movements`/`payables`/PO reconciliation entirely untested.
5. **Ledger reversals** — `recordAdjustment`, `recordReturnRefund`, and real-DB `recordVoid` writes unverified.
6. **401 baseline** — one parametrized "no token ⇒ 401" test across all controllers closes the largest single gap cheaply.
7. **Frontend** — no harness at all; the 22 workflows' UI assertions are 100% manual.
