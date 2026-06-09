# RRBM Codebase Analysis

> Generated: June 7, 2026
> Analyzed by full-stack code review of backend (Spring Boot 3.5 / Java 21) + frontend (vanilla HTML/JS/CSS)

---

## Rules for Fixing

Before any change is made to this codebase, the following rules **must** be followed:

- Fix only the specific bug described.
- Do not refactor.
- Do not rename variables.
- Do not rename functions.
- Do not change architecture.
- Do not change APIs.
- Do not modify unrelated files.
- Do not optimize anything.
- Make the smallest possible change.

Before showing the fix:
1. Explain the root cause.
2. Explain why your change is safe.
3. List possible side effects.
4. Show exactly which lines changed.

If you are less than 90% confident about a fix, ask questions instead of guessing.

---

## System Overview

| Attribute | Value |
|-----------|-------|
| Stack | Spring Boot 3.5 (Java 21) + PostgreSQL 16 + Vanilla HTML/JS/CSS |
| Deployment | Docker Compose (3 containers: db, backend, frontend) |
| State | Mature system — 60+ Flyway migrations, 28 entities, 20+ controllers |
| QA Status | All 53 gaps (9 Critical, 34 Moderate, 10 Minor) closed as of Jun 5, 2026 |
| Auth | Stateless JWT (HS256, 8h expiry) + bcrypt password hashing + master key system |

---

## System Workflows & Schemas

### Flow 1 — Login / Authentication
**Tables:** `users`, `activity_logs`  
**Description:** JWT-based auth with 6 roles (SUPER_ADMIN, ADMIN, ADMINISTRATOR, ACCOUNTING, STAFF, STANDARD_USER). bcrypt password hashing. Admin security keys for privileged actions. Master key system (up to 3 concurrent bcrypt-hashed keys).

### Flow 2 — Create Order
**Tables:** `orders`, `order_items`, `products`, `transactions`, `activity_logs`  
**Description:** Custom order ID format `DDMMYY-NNNNNN` with global never-reset counter. Sources: WALK_IN, AGENT, RESELLER, DISTRIBUTOR, ECOMMERCE, FACEBOOK_PAGE. Payment modes: CASH, GCASH, PAYMAYA, BANK_TRANSFER, BANK_DEPOSIT, ONLINE, COD. Records SALE transaction, deducts stock from correct warehouse. Agent commission O.P. rate tracked per item.

### Flow 3 — Order Lifecycle (Status Management)
**Tables:** `orders`, `transactions`, `activity_logs`  
**Description:** Strict transition matrix: `ACTIVE→DELIVERED`, `ACTIVE→PENDING`, `PENDING→ACTIVE` only. COD resume requires admin security key. PENDING_COLLECTION set only by force-close.

### Flow 4 — Cancel Order
**Tables:** `orders`, `transactions`, `products`, `inventory_movements`  
**Description:** Two types:
- **Standard cancel** — security key required. Stock restore (sellable/rejected disposition). VOID ledger entry (net basis: `total − voidedAmount`).
- **Cancel-for-replacement** — master key required. `cancellationType = REPLACEMENT`. Two-way order linking. Disposition-based stock handling.

### Flow 5 — Item-Level Void
**Tables:** `order_items`, `orders`, `inventory_movements`, `transactions`  
**Description:** 
- **Tier 1 (Partial):** Security key, reduces item quantities, order stays active.
- **Tier 2 (Full void):** Master key, zeroes all quantities, order → CANCELLED + `cancellation_type = VOIDED`. Disposition per item (SELLABLE/REJECTED). Same-day only (before daily close).

### Flow 6 — Return & Refund
**Tables:** `orders`, `transactions`, `inventory_movements`  
**Description:** Post-sale return. Per-item sellable/rejected disposition with validation (`sellable + rejected = totalReturned`). Optional refund (atomic with stock). No day-close restriction. Movement types: RETURN_SELLABLE, RETURN_REJECTED.

### Flow 7 — Daily Close
**Tables:** `daily_reports`, `orders`, `transactions`, `activity_logs`  
**Description:** Snapshots daily business. Normal close (blocks if open orders). Force-close dual-auth (admin key + super admin key). Moves uncollected non-CASH orders to PENDING_COLLECTION, reverses their SALE transactions (COLL-DEFER).

### Flow 8 — Collections
**Tables:** `orders`, `transactions`, `daily_reports`  
**Description:** PENDING_COLLECTION orders awaiting payment. Collect endpoint (security key) writes COLL-SALE and patches daily report (reconciling unfulfilled_orders, unfulfilled_amount). Pessimistic lock prevents double-collect.

### Flow 9 — Receive Stock (Delivery Receipt)
**Tables:** `delivery_logs`, `delivery_log_items`, `products`, `purchase_orders`, `payables`, `inventory_movements`  
**Description:** DR-based stock intake. 3-attempt PO matching chain. Creates PENDING payable. RESTOCK movement records. Duplicate DR check (existByReceiptNumber).

### Flow 10 — Expenses
**Tables:** `expenses`, `expense_items`, `expense_categories`  
**Description:** Hierarchical categories (primary/sub). Quick-entry presets. Void audit trail. CSV/PDF/XLSX export. Weekly/monthly reports. Backdating support.

### Flow 11 — Commissions
**Tables:** `agents`, `commission_entries`, `commission_periods`, `agent_commissions`, `commission_adjustments`  
**Description:** Agent registry (AGENT-YYYY-NNNN codes). O.P. rate per order item. Period-based (YYYY-MM-A/B/C). Bonus/deduction adjustments. Release workflow with master key. CSV/PDF export.

### Flow 12 — Inventory
**Tables:** `products`, `inventory_movements`, `supplier_product_mappings`  
**Description:** 3-warehouse tracking (wh1, wh2, wh3/balagtas). SET product decomposition into components. Low-stock thresholds by selling tag (HOT=5000, SELLING=2000, SLOW=1000). Manual adjustment with MANUAL_ADJUST movement.

### Flow 13 — Reports & Dashboard
**Tables:** All tables  
**Description:** Dashboard (daily/weekly/monthly stats). Monthly Reports (insights, accounting, ecommerce tabs). Pizza box quota tracking. 7-day sales trend. Category distribution. Top products. Payment breakdown. Month-over-month comparison.

---

## Database Schema (28 Tables)

- `orders` — Custom ID, customer, source, payment, status, voided_amount, cancellation_type, order linking
- `order_items` — Product, qty, unit_price, warehouse, voided_quantity, commission fields
- `products` — SKU, code, name, category, subCategory, tags, 3-warehouse stock, SET support
- `transactions` — Immutable ledger: transactionCode (unique), type, amount, referenceType, effectiveDate
- `daily_reports` — Closed-day snapshots: totals, channel breakdowns, gross/net sales
- `users` — Email, passwordHash, role, allowedPages, passwordHistory, adminSecurityKey
- `master_keys` — bcrypt-hashed, max 3 active, label, audit trail
- `inventory_movements` — Immutable: type, warehouse, qty, reference
- `delivery_logs` / `delivery_log_items` — Stock intake via delivery receipt
- `purchase_orders` / `po_items` — PO with fulfillment tracking, DR matching
- `payables` — Supplier debts: PENDING/PAID/CANCELLED with audit
- `expenses` / `expense_items` — Categorized, void audit trail
- `expense_categories` — Hierarchical (parent_id), system-defined + custom
- `agents` — Agent registry with status
- `commission_periods` / `commission_entries` / `commission_adjustments` / `agent_commissions` — Full commission engine
- `suppliers` / `supplier_product_mappings` — Supplier catalog with preferred pricing
- `activity_logs` — System-wide audit trail
- `settings` — Key-value configuration
- `notification_emails` — Low-stock alert recipients
- `order_id_counter` — Global order ID sequence
- `po_year_counter` — Yearly PO number sequence
- `product_set_components` — SET product decomposition

---

## Critical Issues

### C-1. CORS wildcard origin in production (`SecurityConfig.java`)
**Severity:** Critical  
**Description:** `SecurityConfig` uses `allowedOrigins("*")` — any domain can call the API. Deployment 1C is blocked because the production domain is not confirmed.  
**File:** `rrbm-backend/src/main/java/rrbm_backend/SecurityConfig.java`

### C-2. Fallback JWT secret in application.properties
**Severity:** Critical  
**Description:** `rrbm.jwt.secret=${RRBM_JWT_SECRET:rrbm-secret-key-minimum-256-bits-for-hs256-algorithm-security}`. If the env var is not set in production, it falls back to a known string in the codebase. Anyone who has access to the source can forge valid JWTs.  
**File:** `rrbm-backend/src/main/resources/application.properties:38`

### C-3. No pagination on any list endpoint
**Severity:** Critical  
**Description:** All list endpoints return ALL records with zero pagination:
- `GET /api/orders` — returns entire orders table
- `GET /api/orders/history` — date range but no page/limit
- `GET /api/activity-logs/today` / `/range` — no limit
- `GET /api/daily-reports` — no limit
- `GET /api/dashboard/stats` — no limit on product lists

At scale, these will cause OOM errors and request timeouts.  
**Files:**
- `OrderController.java` — `getAllOrders()`, `getOrderHistory()`
- `ActivityLogController.java` — `getTodayLogs()`, `getLogsByDateRange()`
- `DailyReportController.java` — `getDailyReports()`
- `DashboardController.java` — various stats endpoints

### C-4. `display:none!important` on delivery fee row
**Severity:** Critical  
**Description:** `index.html:627` uses `style="display:none!important;"` on the delivery fee display wrapper. The `!important` flag overrides any JavaScript-driven `style.display` changes, meaning the delivery fee line item never becomes visible even when delivery fee > 0.  
**File:** `rrbm_frontend/rrbm-frontend/index.html:627`

### C-5. Double-submit protection is client-side only
**Severity:** Critical  
**Description:** M-20 added `window._addOrderSubmitting` flag to prevent UI double-clicks, but there is no server-side idempotency key. Network retries or direct API calls bypass the client flag and create duplicate orders (each with its own SALE transaction and stock deduction).  
**File:** `rrbm_frontend/rrbm-frontend/js/app.js` — `addOrder()` function

### C-6. No rate limiting on auth endpoints
**Severity:** Critical  
**Description:** `POST /api/auth/login` has no rate limiting or brute-force protection. Attackers can spray passwords indefinitely without throttle.  
**File:** `AuthController.java` — `login()` endpoint

---

## Moderate Issues

### M-1. Test coverage is near-zero
**Severity:** Moderate  
**Description:** Only 2 test files found (`CancelOrderM26Test.java`, `PhantomDebitIntegrationTest.java`) for a codebase with 100+ Java files and 20+ controllers. Zero frontend tests. No CI pipeline enforces test pass.  
**Affects:** Whole project

### M-2. Orphaned endpoint — `GET /api/orders` returns full table
**Severity:** Moderate  
**Description:** `OrderController.getAllOrders()` returns every order ever created with no filters, pagination, or limits. It is not wired to any frontend view — it's dead code that exposes the entire order history via direct API call.  
**File:** `OrderController.java`

### M-3. Orphaned endpoint — `GET /api/orders/search`
**Severity:** Moderate  
**Description:** Cross-all-time customer name search with no result limit. Not wired to any frontend view. Dead code that exposes data without restriction.  
**File:** `OrderController.java`

### M-4. JWT stored in localStorage
**Severity:** Moderate  
**Description:** `localStorage.getItem('rrbm_token')` is used throughout the frontend. Tokens in localStorage are accessible to any JavaScript executing in the same origin (XSS vulnerability). HttpOnly cookies would be more secure.  
**File:** `rrbm_frontend/rrbm-frontend/js/app.js` — all `authHeaders()` calls

### M-5. No request size limits configured
**Severity:** Moderate  
**Description:** No `spring.servlet.multipart.max-file-size` or `spring.servlet.multipart.max-request-size` in application.properties. Large batch CSV imports or malicious payloads can overwhelm the server.  
**File:** `rrbm-backend/src/main/resources/application.properties`

### M-6. Low stock email alerts — incomplete integration
**Severity:** Moderate  
**Description:** Backend has `LowStockEmailService` with `checkAndAlertLowStock()`, DB has `notification_emails` table, Settings UI manages email list CRUD. However, verifying that the async email dispatch triggers correctly in production requires live testing with actual SMTP credentials.  
**Files:** `InventoryService.java`, `LowStockEmailService.java`, Settings UI

### M-7. `GET /api/activity-logs` returns all — no pagination
**Severity:** Moderate  
**Description:** Activity logs grow unbounded over time. No pagination means increasingly slow responses and large payloads.  
**File:** `ActivityLogController.java`

---

## Minor Issues

### N-1. Inline `onclick=` attributes throughout HTML
**Severity:** Minor  
**Description:** `rrbm_frontend/rrbm-frontend/index.html` uses inline `onclick=` handlers on all interactive elements instead of modern event delegation.

### N-2. No loading/skeleton states
**Severity:** Minor  
**Description:** Most table renders show static text ("Loading…") with no spinners, skeleton loaders, or visual progress indicators.

### N-3. Fixed pixel CSS values
**Severity:** Minor  
**Description:** `css/styles.css` uses many hardcoded pixel values for spacing and sizing — not responsive-friendly for different screen sizes.

### N-4. No 404 error page
**Severity:** Minor  
**Description:** Nginx serves `index.html` for all routes but there is no proper 404 page or error handling for unknown paths.

### N-5. No `encodeURIComponent` in URL construction
**Severity:** Minor  
**Description:** Several `fetch` calls build URLs with string concatenation (`url + '?start=' + start`) that will break on special characters in search/filter values.

### N-6. Frontend data cache has no TTL
**Severity:** Minor  
**Description:** `appState.allOrders`, `appState.orderHistoryAll`, `appState.inventoryAllProducts` are cached client-side indefinitely with no stale-while-revalidate or TTL strategy. Users see stale data until manual refresh.

### N-7. Activity log badge action key mismatch (already fixed)
**Severity:** Minor  
**Description:** Fixed in N-10 (Jun 2, 2026) — `ExpenseController.java` now uses `"EXPENSE_RECORDED"` matching the frontend badge map.

---

## Fix History (from QA-GAPS.md)

All 53 gaps identified in QA phase are closed:

| Severity | Fixed | Remaining |
|----------|------:|----------:|
| Critical | 9 | 0 |
| Moderate | 34 | 0 |
| Minor | 10 | 0 |

Key fixes included:
- **C-1/C-2/C-4:** Pessimistic lock on collectOrder, user lookup from JWT not body, dedup window on refund
- **C-5/C-6:** 30-second refund dedup, amount ceiling checks
- **C-7:** `@Transactional` on processDelivery + duplicate receipt check
- **C-8:** Removed plaintext credential columns
- **M-26 (Phantom Debit):** 3-site net-basis fix — all ledger writes use `total − voidedAmount`
- **M-24:** Strict status transition matrix
- **M-25:** Backend COD resume password gate
- **M-30/M-32:** Role elevation guards on payable/employee endpoints
- **M-23:** DISABLED account login reject
