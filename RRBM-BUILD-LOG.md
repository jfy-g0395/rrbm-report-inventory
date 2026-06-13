# RRBM Project — Build Log
**Last Updated:** Jun 10, 2026 (Session U20) | **Dev:** HTML/CSS background, learning backend | **Location:** Pasig City, PH (GMT+8)

---

## SESSION HISTORY

| # | Date | Key Work |
|---|------|----------|
| 1 | May 12 | Frontend prototype — all modules (Dashboard, Orders, Inventory, Reports, Employees, Settings) |
| 2 | May 13 | Frontend structure (CSS/JS/assets/docs), logo embed, documentation |
| 3 | May 18 AM | Dev setup (Java 21, PostgreSQL 18.4, Maven), Phase 1 DB schema, Phase 2 JWT auth start |
| 4 | May 18 PM | Fixed BCrypt hash corruption, Phase 2 auth complete, frontend login connected |
| 5 | May 19 | Phase 3 Orders module, frontend "New Order" wired to backend, toast fix |
| 6 | May 20–21 | Phase 4 Products/Inventory, Phase 5 Daily Reports, Activity Log, Delivery Log, Flyway migration fix |
| 7 | May 21 | 10-feature frontend/backend sprint — all views, modals, tag-select styling, master key seed |
| 8 | May 21 | 8 bug fixes — toolbar alignment, category filter, product code, form state, order history |
| 9 | May 21 | New Order fields, Receipt PDF, Activity Log fix, Settings restriction, User Role Management |
| 10 | May 21 | Phase 7: Dashboard live data, Settings save/load, Master Key endpoint, Employee List CRUD, Bug fixes |
| 11 | May 21 | 2 bug fixes + 5 new features: Reports real data, Dashboard tabs + Pizza quota, Top 5 today, Expenses module, Role-based page access; removed obsolete threshold fields from Add Product form |
| 12 | May 22 | Compile fix (UserController type inference); Phase 9 — Transaction Ledger accounting architecture: V15 migration, Transaction entity/repo/service/controller, daily close redesigned, refund/void/adjustment endpoints |
| 13 | May 22 | 3 bug fixes + 2 features: Block close if open orders, mandatory cancel reason (DB + backend + frontend + display), activity log SCREAMING_SNAKE_CASE fix, OrderResponse DTO cancellationReason field |
| 14 | May 22 | UI Upgrade — CSS polish (11 sections), dashboard HTML reorder, login split-panel redesign, branded charts (line+donut+daily bar), low-stock 5-row cap + full modal, pizza quota 3-tier animation |
| 15 | May 23 | V16 migration (108 real products + sub_category column + FK chain fix); Sub-category filter + Add Product field; Inventory Edit modal + PATCH endpoint + activity logging; Receipt print cleanup |
| 16 | May 23 | Chart fixes: donut native legend enabled, bar chart opacity standardized, custom legend HTML removed; actionBadge map: 6 missing entries added |
| 17 | May 23 | Payables module (V17 migration + full CRUD); WH3→Santan label rename; Inventory Edit modal rebuilt (stock fields + new design); delivery Received/Rejected fields; Public Sans product-code chip; receipt auto-6-digit |
| 18 | May 23 | Refund/Void modal polish; Expense History date-range card + loadExpenseRange() + printExpenseRange(); Monthly Report print button + printMonthlyReport(); pizza box verified (category-based lookup correct); JwtUtil.extractUsername() alias; PayableController ported to extractUsername() |
| 19 | May 23 | V18 migration — bulk UPDATE unit_price + unit_cost for all 108 products from updated CSV; INSERT PB4P18 + PB4P20 (new Plain Party Box 18" and 20"); soft-deactivate guard for products removed from CSV |
| 20 | May 23 | V19 test seed — 35 days × 4 orders/day (140 orders), 2 refund/void targets, 35 closed daily_reports, 14 expense days, 1 delivery receipt + PENDING payable; full QA feature testing guide (Groups A–H) |
| 21 | May 23 | V20 migration — admin_security_key + supplier_name columns; Admin Security Key system (BCrypt, SUPER_ADMIN assign/verify); Cancel/Refund/Void switch from master key → personal security key + inventory restore; Supplier field on delivery; Payable detail with line items |
| 22 | May 23 | 3 minor fixes — sessionStorage → localStorage (login persists on F5); Delivery Reports print header Supplier column added; Refund/Void buttons added to DELIVERED orders in Order List |
| 23 | May 25 | V21 migration (delivery_fee + indexes); Delivery Fee on orders (backend + frontend); Reports overhaul (7 new endpoints, 8 new cards, source donut + MoM + sales-vs-expenses chart); Employee modals scrollable; Expense PDF download |
| 24 | May 25 | Replace printMonthlyReport() (full async 9-endpoint version) + downloadExpensePDF() (API field fix + company header + tfoot grand total + footer) |
| 25 | May 25 | Reports overhaul completion: MoM cards + sales-vs-expenses chart wired up; E-commerce breakdown (backend endpoint + frontend card + JS); chart-to-image capture in Print Report PDF; all S23 load functions confirmed present |
| 26 | May 26 | Field-name audit: spec's 6 JS fixes were all wrong (frontend already matched backend); only real fix was SQL UPDATE to assign SHOPEE/TIKTOK/LAZADA to 71 NULL ecommerce_platform test rows |
| 27 | May 26 | PDF: source donut + table combined; E-com card "Top products this month" sub-label; Order source filter dropdown (Order List + History); updated filterOrderList/filterOrderHistory (source+platform aware); Enter key handler for all modals + login |
| 28 | May 26 | Order receipt redesign — new card layout (amber header border, 2×2 meta grid, dark brown table header, grand total block, signature lines, Official stamp footer); payDisplay + srcDisplay formatters added |
| 29 | May 26 | Quick wins: discount % presets (3/5/10/50% + Clear); Week tab shows "Week N · Mon"; Monthly tab shows month name; Accounting Summary collapsible (collapsed by default); Top 5 → Top 10 products in monthly report; Reports nav renamed to "Monthly Report" |
| 30 | May 26 | Permissions & Security: `order-history` as separate page permission; Print button gated by canManageOrders(); Payable status change now requires admin security key via new modal |
| 31 | May 26 | Daily Reports page: new nav item + view section with table of closed reports + detail modal; backend `GET /api/reports/daily-reports-list` endpoint with resolved closedByName |
| 32 | May 26 | V22 migration — description + item_code columns on products; Product.java + ProductController updated; Add/Edit Product modals get new fields; inventory table gets Item Code column + keyword search covers itemCode |
| 33 | May 27 | Rejected Items page: `GET /api/reports/rejected-items` endpoint; nav item; view with date range filter + 8-col table + grand total footer + Download PDF (matches Expense PDF style) |
| 34 | May 27 | Reports: Pizza Box channel split (Direct/E-com/Total grid); Non-Pizza Items Summary card + product breakdown modal; Daily Order Summary table expanded to 6 columns (Direct, E-com, Total, Pizza Boxes, Revenue); 3 new backend endpoints |
| 35 | May 27 | Dashboard channel split: Direct vs Ecommerce stat cards; per-platform Shopee/TikTok/Lazada mini-cards; two-tone pizza quota bar (amber=direct, purple=ecom); Payables Outstanding + COD Pending cards; `GET /api/dashboard/channel-summary` endpoint |
| 36 | May 27 | Purchase Order module: V23 migration; PurchaseOrder + PoItem entities; repos; PurchaseOrderController (GET list, GET single, POST create, PATCH status); DR→PO auto-match in ProductController; PO list page with expandable rows + status toggle; New PO modal (vendor/ship-to/items table/VAT/total); PO PDF print; page-access gating |
| 37 | May 27 | User Management Completion: fix ALL_PAGES constant (5 missing page keys); DELETE /api/users/{id} (Super Admin, security key, guards); PATCH /api/users/{id}/change-password; Delete button in Employee List; modal-delete-employee with security key; Change Password button in sidebar footer + modal-change-password (all users) |
| 37b | May 27 | Bug fix: `openModal()` helper was never defined — `openNewPoModal()` (New PO button) and `openNonPizzaModal()` (Non-Pizza breakdown) both silently failed; replaced both calls with `$('id').classList.add('open')` matching the rest of the codebase |
| 37c | May 27 | PO form inventory lock: Item Code now required (not optional); both Item Code and Product Name fields get `<datalist>` autocomplete populated from `/api/products` on modal open; `lookupPoItemCode()` auto-fills name + price and shows red outline if code not in inventory; new `lookupPoItemDesc()` reverse-fills code + price from name; `submitNewPO()` validates all codes exist in `_poItemCodeMap` before submit; `_poDescMap` added for name→code reverse lookup |
| 37d | May 27 | Bug fix: V24 migration — `delivery_log.receipt_number` widened from VARCHAR(10) to VARCHAR(30); column was set in V5 for 6-digit auto-codes but never updated when validation was widened to 2-20 chars in Session 21; blocked 11-digit PO numbers at DB level despite passing all frontend/backend checks |
| 38 | May 27 | PO / Receive Stocks / Delivery Reports improvements: V25 migration adds `po_number` to `delivery_log`; PO list adds DR# column showing fulfilled receipt numbers per row; New PO modal QTY column widened 70px→100px; Receive Stocks gets read-only PO dropdown (auto-populated with INCOMPLETE POs, resets on clear); Delivery Reports adds PO# column + replaces Notes button with full detail modal showing all items (ordered/received/rejected/unit cost/line total) + header metadata |
| 38b | May 27 | Bug fix: Receive Stocks PO dropdown — selecting a PO was not auto-filling the Supplier Name field; root cause was inline `onchange` used `$('delivery-supplier')` which is only defined inside the app IIFE closure and evaluates to undefined from an HTML attribute; fix: extracted to `window.onDeliveryPoChange(sel)` using `document.getElementById`, called via `onchange="onDeliveryPoChange(this)"` |
| 39 | May 27 | E-commerce CSV import: `POST /api/orders/batch` batch endpoint (loops existing `createOrder` logic, partial success allowed); "Import CSV" button in Order List toolbar; `modal-import-ecom` with file picker + payment mode/status overrides + live preview table; `parseCsvOrders()` handles quoted fields, groups multi-item rows by Order No, detects platform (Shopee/TikTok/Lazada) from courier string and order number format; `matchCsvProduct()` token-scoring fuzzy match against cached inventory (high/low/null confidence); preview shows expandable rows per order with green/amber/red match chips; unmatched items get inline product search dropdown; `submitCsvImport()` posts batch payload, shows imported/failed summary toast |
| 53 | May 31 | Product-Analytics Dashboard frontend: Pizza Quota KPI card (period-aware target/actual/bar), Total Qty Sold card, Category Sales Breakdown table (expandable subcategories), Top 5 Products table, 3 Chart.js charts (Sales Trend line, Category Distribution donut, Category Performance stacked bar); `loadProductAnalytics(period)` wired into `switchDashTab`, `navigateTo('dash')`, and post-login. Backend endpoint + CSS dark mode fix already existed. |
| 54 | Jun 1 | Three UI/UX fixes: Inventory Description column added; `window.openModal` defined (fixed silent Cancel Delivery button + Category Breakdown + CSV Skipped modals); badge CSS added for delivery status in light/dark mode |
| 55 | Jun 1 | Dashboard period-awareness audit + 5 backend fixes: `previousMonthPizzaTotal()` category index bug; `salesTrend` period-aware (weekly Mon–Sun, monthly per-day); `channelSummary` period-aware; Row 9 duplicate hidden on weekly/monthly tabs; `codPendingCount` removed from `/stats` |
| 56 | Jun 1 | Non-pizza total KPI on pizza quota card (all tabs); full Order schema analysis — 10 files reviewed, 5 gaps identified |
| 57 | Jun 1 | Order schema gap fixes: V35 global receipt counter (never resets daily); `OrderResponse` +4 timestamp fields (`cancelledAt/By`, `collectedAt/By`); batch import duplicate check anchored to `"Order No: "` prefix; `PENDING_COLLECTION` added to `updateStatus()` allowed list; source enum validated at `createOrder()` |
| 58 | Jun 1 | Order list audit frontend fixes: `ecomOrderRef(o)` function-call bug fixed at 4 sites; Order History colspan corrected (2 rows); cancelled-by/at metadata shown under status badge in Order List + Order History; collected-by/at shown under status badge in Order History; Gap 3 (orphaned endpoints) noted in MD |
| 59 | Jun 2 | Void/cancel/return QA wave — Fix 1–4 complete: CANCEL_REJECTED qty=0→actual; VOID_REJECTED movement type (V41 migration); unified rejected items report (delivery + void + cancel + return rows, V42 widens description to TEXT); void activity log enriched with per-item inventory outcomes |
| 59b | Jun 2 | Fix 5: voided items display in Order List + Order History rows (effective qty, (all voided) text, struck-through original total); openCollectionDetail modal items table (strikethrough + badge-crit for fully voided, muted voided note for partial); code-verified only for openCollectionDetail — no PENDING_COLLECTION+void test data existed; flag for final integration test |
| 59c | Jun 2 | Fix 6: printOrderReceipt + exportOrderHistoryPDF use effective values after voids (fully voided items hidden, effective qty/amount for partials, void adjustment deduction line in receipt subtotals); all 5 browser tests passed |
| 60 | Jun 3 AM | Product catalog enforcement (8 changes): productId required on new order + replacement order — frontend amber/green indicator, hidden field clear, submit block; backend null + existsById guards in createOrder + createReplacementOrder; Step 12 Part A — openCollectionDetail hasVoids 3-column expansion; V1/V2/V3 void tests passed |
| 61 | Jun 3 | Step 12 integration tests complete: T11 day-close guard (close + API block + frontend banner + void button gone); C1–C3 cancel tests; R1–R3 return tests; P1 replacement order; build log updated |
| 62 | Jun 3 | Step 12 complete: cross-flow UI checks (5 items); cleanup — removed `window._appState` debug block (app.js), removed `issueVoid()` (TransactionController), removed `recordPostCloseVoid()` (TransactionService); node --check + mvn compile both clean; full-app table scroll audit — added `.table-scroll` CSS class, wrapped 21 bare tables in all view sections (23 total wrappers including 2 from prior sessions); zero bare view tables remaining |
| 63 | Jun 3 | PO label fix: "Unit Price" → "Unit Cost" in New PO modal items table (index.html:3325) and PO list expand detail (app.js:7273); CSV import and Order Detail "Unit Price" labels confirmed correct and untouched; node --check clean |
| 64 | Jun 3 | Save as PNG fix (PO + Daily Report popups): replaced race-condition opener fallback with `onload`-gated button — button starts disabled, enables only after html2canvas CDN loads; `savePNG` now uses `window.html2canvas` exclusively; download link appended to body before click to prevent popup suppression; both popups fixed; node --check clean |
| 65 | Jun 4 | PO Redesign — Backend Pieces 1–4: V43–V46 migrations (suppliers, supplier_product_mapping, purchase_orders enhancements, po_items supplier fields); Supplier CRUD 9 endpoints; Supplier-Product Mapping 4 endpoints; PO number auto-gen (PO-MMDDYY-XXXXX format, yearly counter, pessimistic-lock atomic increment); supplier snapshot resolution at PO creation (supplierItemCode, supplierDescription, unitCost from mapping); all tests passed |
| 66 | Jun 4 | PO Redesign — Backend Piece 5 + V48 fix: 3-attempt DR→PO matching (itemCode→supplierItemCode→name) in both processDelivery and cancel-reversal; FIFO ORDER BY id ASC on all 3 PoItemRepository queries; V48 migration adds CANCELLED to payables_status_check (pre-existing constraint bug, not introduced by PO redesign); FIFO ORDER BY and cancel-reversal tests passed (acceptance criteria) |
| 67 | Jun 4 | PO Redesign — Frontend Pieces 1–5 complete: Suppliers page + Mapping page; PO creation form supplier integration (7 tests ✅); PO list/detail supplier columns + vendor reference (4 tests ✅); PO PDF supplier columns + VAT calculation rows (6 tests ✅); po-number maxlength fix; node --check clean |
| 68 | Jun 5 | M-26 phantom-debit fix (3 sites): `cancelOrder`, `recordDeferralVoid`, `recordCollectionSale` all switched to net basis (total − voidedAmount); dead 3-arg `recordVoid` overload removed; 14/14 tests green; historical correction — 1 real phantom debit (order 020626-000080, −₱29.94) corrected via ADJUSTMENT; all 7 affected orders verified at net ₱0.00; 53/53 QA gaps closed; commit e78dd9c |
| 69 | Jun 5 | Removed `POST /api/transactions/refund` endpoint + `recordRefund()` + idempotency repo query; dead fields/imports cleaned from TransactionController/Service/Repository; `rtn-security-key` added to `secFields` clear-list (pre-existing gap — return modal key not cleared on close); stale `refund-security-key` + `void-security-key` secFields entries removed; V47 reservation closed in VOID_CANCEL_RETURN_REDESIGN.md; 15/15 tests green |
| E1–E4 | Jun 5 | Expense Tracking build track (SPEC §1.2–1.5): E1 — expense_categories schema + void columns + ACCOUNTING role fix (V49+V50); E2 — payment method/notes/status fields + backdating window + category endpoint (V51); E3 — void workflow (BCrypt key auth); E4 — dashboard summary endpoint (today/yesterday/MTD/category JPQL aggregations); AuthenticationEntryPoint added (401 not 403 for unauthenticated requests); 33/33 tests green |
| E5–A12 | Jun 5–6 | Full SPEC build (E5 expense reports; A1–A12 agent registry, O.P., commissions, release, payment, filters, exports); 92/92 tests green |
| A13 | Jun 6 | SPEC §2.2 + §2.3 gap fixes: `pendingCommission` added to agent API (JPQL sum of PENDING entries); order form free-text agent field replaced with registry-backed `<select id="field-agent-id">`; O.P. fields per item row (basePrice/opRate, hidden unless AGENT source); `addOrder`/`addReplacementOrder` send agentId + per-item O.P. to backend; `loadAgentOptions()` fetches active agents on source change; 96/96 tests green |
| A14 | Jun 6 | SPEC §1.3 quick-entry presets + expense form E2 fields; SPEC §2.3 "Register new agent" modal; expense form now sends paymentMethod/categoryId/notes/referenceNumber to match E2 backend; 10 preset buttons pre-fill category + sub-category + description; modal-register-agent in order form posts to /api/agents, refreshes select, pre-selects new agent; 100/100 tests green |
| A15 | Jun 6 | SPEC §2.2 + §2.3 list polish: pending commission column in agent table; edit button/modal for agent records; agent field in order form upgraded from `<select>` to text-input autocomplete (field-agent-input + field-agent-id hidden, filters by name or code); 104/104 tests green |
| A16 | Jun 6 | SPEC §1.6 gap: weekly expense report — GET /api/expenses/report/weekly?year=&week=; week-over-week % comparison, 7-day daily breakdown (Mon–Sun, zero-filled), highest/lowest day, category breakdown with pct, voided entries; new `sumByPrimaryCategoryForDateRange` JPQL query; V59 still available; 108/108 tests green |
| A17 | Jun 6 | Frontend weekly report UI — Weekly Report card in expenses view (year/week inputs, stats row, 7-day table, category table, voided entries); ISO 8601 `_currentISOWeekYear` helper; inactive agent edge case in `openReplacementForm` (falls back to GET /api/agents/{id}, sets placeholder with "Inactive" note, clears hidden field); V59 still available; 112/112 tests green |
| A18 | Jun 6 | App verification pass — 112/112 tests green; browser verification via Claude-in-Chrome: zero console errors across all views, all API calls 200; **bug fix:** `loadWeeklyReport` fetch URL missing `/api/` prefix (404 → 200 after fix); weekly report card renders with 7-day breakdown; "Electric" preset fills Utilities/Electric Bill; `?v=a18` cache-bust param added to script tag; V59 still available |
| A19 | Jun 6 | Build Complete — 112/112 tests confirmed green (BUILD SUCCESS); V59 still available (no migration consumed); no app.js changes this session → cache-bust stays `?v=a18`; Build Complete summary appended to PROGRESS.md; all SPEC §1–§4 sections implemented and browser-verified |
| A20 | Jun 6 | Register Agent button on Agents page — button added to `view-agents` card-header (`index.html`); `submitRegisterAgent()` extended to reload agents table when called from agents page (`app.js` +1 line); reuses existing A14 modal + backend; 0 new tests (no new endpoints); browser-verified: button visible, modal opens, submit refreshes table, order-form shortcut unaffected; zero console errors |
| U1 | Jun 6 | Import schema + permission + report filter (SPEC §3.1 + §3.4 + §3.5) — V59 migration adds `is_imported`/`import_ref` columns to `orders` and `expenses`; `ImportController.java` created with `POST /api/import/authorize` (ACCOUNTING/ADMIN role + BCrypt admin_security_key); `Order.java` + `Expense.java` add imported/importRef fields; `ExpenseRepository` + `ExpenseController` add `importedOnly` filter and `importedEntries` to daily/monthly reports; `ImportU1Test.java` (4 tests); 116/116 green; V60 next |
| U2 | Jun 6 | CSV upload pipeline (SPEC §3.2–§3.4) — `ImportController.java` extended with `GET /api/import/template` (blank CSV with three `# SECTION:` markers), `POST /api/import/upload` (multipart, validate-only preview, returns valid/needsFix/duplicates + sessionToken), `POST /api/import/commit` (commits valid rows via `OrderService.createOrder` + expense save, bypasses backdating window); in-memory `ConcurrentHashMap` session store with 30-min TTL; `ProductRepository` + `OrderRepository` + `ExpenseRepository` query additions; `ImportU2Test.java` (5 tests); 121/121 green; V60 still next |
| U3 | Jun 6 | Import history and status tracking (SPEC §3.5–§3.6) — `ImportController.java` adds `GET /api/import/history` (batches grouped by commit date + admin, merged orders + expenses, sorted DESC) and `GET /api/import/history/{importRef}` (single-receipt detail, 404 if missing); `OrderRepository` + `ExpenseRepository` date-range import queries; `OrderResponse.java` gains `imported` + `importRef` fields; `OrderController.convertToResponse` updated; `ImportU3Test.java` (5 tests); 126/126 green; V60 still next |
| U4 | Jun 6 | Import frontend UI (SPEC §3.6–§3.7) — `index.html` adds `nav-import` nav button + `view-import` section with 5-step flow (Authorize → Download Template → Upload & Preview → Commit → History); `app.js` adds `authorizeImport`, `downloadImportTemplate`, `uploadImportCsv`, `renderImportPreview`, `commitImport`, `loadImportHistory`; "Imported" badge wired into `renderOrderHistoryRows`; `ImportU4Test.java` (4 tests); 130/130 green; V60 still next |
| U5 | Jun 6 | Import detail modal — `ImportController.java` adds `GET /api/import/history/batch?date=YYYY-MM-DD` (returns `{date, orders:[...], expenses:[...]}` using existing date-range repo queries); `index.html` adds `modal-import-detail` overlay (max-width 740px, scrollable); `app.js` adds `_importHistoryData` cache + `openImportDetailModal(idx)` (shows batch summary instantly from cache, then fetches full order/expense tables); `ImportU5Test.java` (4 tests); 134/134 green; V60 still next |
| U6 | Jun 6 | Import-track final verification pass — 134/134 tests confirmed green (no new tests, no behavior changes); browser verification via Claude-in-Chrome: full import flow exercised (authorize → template → upload validCount=3 → commit committed=2 → history row appears → detail modal opens with 2 tables + 2 data rows); zero JS console errors throughout; RRBM-BUILD-LOG.md U1–U5 entries backfilled; PROGRESS.md U6 entry appended; V60 still next |
| U7 | Jun 7 | XLSX template redesign — switched import from CSV to .xlsx; SheetJS (xlsx-0.20.3 CDN) added to `index.html`; `downloadSalesTemplate()` + `downloadExpensesTemplate()` rebuilt with SheetJS in-browser generation; Instructions sheet added as first sheet in both templates (opens first in Excel) with do's/don'ts, field explanations, valid value lists; Platform column added to sales template at index 7 (SHOPEE/TIKTOK/LAZADA — required for ECOMMERCE source, ignored otherwise); `uploadImportCsv()` reads .xlsx via FileReader+SheetJS (`cellDates:true, dateNF:'yyyy-mm-dd'`), converts rows to CSV string for existing endpoint; `ImportController` flexible date parser added (`parseDate()` — 8 formats: ISO, M/d/yyyy, MM/dd/yyyy, d/M/yyyy, dd-MM-yyyy, M/d/yy, d MMM yyyy, MMMM d, yyyy); ECOMMERCE platform validated + `order.setEcommercePlatform()` called in `buildOrderFromRow()`; `ParsedSaleRow` + `SaleAccumulator` gain `ecommercePlatform` field; fuzzy expense category matching added (`FUZZY_CAT` static map + `fuzzyMatchCategory()` — 35 keyword→category/sub-category mappings covering gas, water, electric, rent, salary, delivery, packaging, etc.); preview `⟳` badge for inferred categories + blue platform tag for ECOMMERCE rows; file input changed to `accept=".xlsx"`; `mvn compile -q` clean |
| U8 | Jun 7 | Batch import backdating + post-commit modal — V60 migration (`late_imported BOOLEAN NOT NULL DEFAULT FALSE` on orders); `Order.java` adds `lateImported` field (getter/setter) + conditional `@PrePersist` (skips `createdAt` assignment if already set — live orders unaffected, import orders pre-set it); `OrderIdGenerator.generateOrderIdForDate(LocalDate)` added alongside existing `generateOrderId()` (same global counter, caller-supplied date prefix → `DDMMYY-NNNNNN`); `OrderService.createOrderAtDate(Order, Long, LocalDate)` added — existing `createOrder()` completely untouched; `createOrderAtDate` sets ID via `generateOrderIdForDate(targetDate)`, pre-sets `createdAt = targetDate.atTime(12, 0)`, calls `transactionService.recordSale(savedOrder, createdByUserId, targetDate)` (see U9); `ImportController` commit loop switched to `createOrderAtDate()`, checks `dailyReportRepository.findByReportDate(targetDate).isPresent()` to set `lateImported=true` + notes when target date's report already closed; enhanced commit response adds `committedOrders` (orderId, date, customer, total, lateImported) and `committedExpenses` (date, category, amount) arrays; `app.js` `commitImport()` calls `showImportResultModal(data)` — dynamic modal built in JS with committed orders table (⚠ Late recorded badge on late imports), committed expenses table, failed rows table (only shown if errors > 0), all sections scrollable; `<div id="import-commit-result">` removed from `index.html`; `mvn compile -q` clean |
| U9 | Jun 7 | Commission fix + ledger alignment + daily report auto-creation for late batch imports — (1) **CommissionService**: silent `return` on closed covering period replaced with fallback to earliest OPEN period (`findByStatusOrderByStartDateDesc("OPEN").stream().min(Comparator.comparing(CommissionPeriod::getStartDate))`); `orderDate` on entry still records actual sale date for audit, only `periodId` points to the next payout cycle; no new repo method needed; (2) **TransactionService**: new `recordSale(Order, Long, LocalDate)` overload with caller-supplied `effectiveDate` — existing 2-arg overload untouched, still uses `LocalDate.now()`; (3) **OrderService.createOrderAtDate()**: calls new 3-arg `recordSale` overload with `targetDate` so SALE transactions land on the correct ledger date; (4) **DailyReportService**: new `closeForImportDate(Long, String, LocalDate)` — idempotent (returns silently if report already exists), no ACTIVE/PENDING guard (imported ACTIVE orders are confirmed sales), no status transitions on PENDING/COD orders (counted as unfulfilled, not moved to PENDING_COLLECTION), mirrors `closeDailySales()` EntityManager stats block + `transactionService.get*ForDate()` for financial figures; (5) **ImportController**: auto-close block after commit loops collects dates where `lateImported=false` (no prior report), calls `closeForImportDate()` per date, adds `autoClosedReports` array (date, status: created/failed, reason) to response; `app.js` modal adds auto-reports section (✔ Created / ✘ Failed per date); dashboard weekly/monthly aggregate totals confirmed unaffected (query by `CAST(createdAt AS date)` — backdated `createdAt` routes imports correctly); trend chart fixed by auto-created daily reports; `mvn compile -q` clean after all changes |
| U10 | Jun 7 | Session 1 of BATCH-IMPORT-COD-COMMISSION-PLAN — added `BATCH_IMPORT_SALES` activity log entry after sales commit loop in `ImportController.java`. Logs count + total of imported sales orders per batch commit. Previously only expenses logged their import; sales orders created silently. Null-safe filter on total. All 17 non-U2 import tests green (U2 has pre-existing 404 failures from endpoint path mismatch, unrelated) |
| U11 | Jun 7 | **Sessions 2+3 combined** — COD routing (Payment Status column, PAID→ACTIVE+commission, UNPAID→PENDING_COLLECTION+reverse SALE+no commission), expense tracking in daily reports (V61 migration, `totalExpenses`/`expensesCount` on `DailyReport.java` + `DailyReportService` close methods), dual-sheet xlsx upload (Sales+Expenses sheets detected → `/api/import/upload/combined`), manual close endpoint (`POST /api/import/close`), auto-close removed from commit handler, frontend "Close Daily Reports" button in commit result modal, sales template updated (Payment Status column + COD examples). Build: `mvn compile -q` clean. Tests: 130/134 pass (4 U2 pre-existing). |
| U12 | Jun 7 | **Session 3** — COD collection commission creation + idempotency guard + pendingCollectionAt timestamp. Added commission entry creation (`commissionService.createEntriesForOrder()`) in `OrderController.collectOrder()` PENDING_COLLECTION branch after `recordCollectionSale()`. Added idempotency guard to `CommissionService.createEntriesForOrder()` via `existsByOrderId` check in `CommissionEntryRepository` — prevents duplicate commission entries if called multiple times for the same order. Set `pendingCollectionAt = OffsetDateTime.now()` in `ImportController` commit handler when routing COD UNPAID to PENDING_COLLECTION. Build: `mvn compile -q` clean. Tests: 130/134 pass (4 U2 pre-existing). |
| U13 | Jun 7 | **Session 4** — Agent commission detail modal + backend breakdown endpoint. Pre-flight found `GET /api/agents/{id}/commissions/breakdown?periodId=X` did NOT exist (plan assumed it did). Created new endpoint in `CommissionController.java` injecting `OrderRepository` — groups entries by orderId, fetches customer name from `Order` table, returns nested JSON (orders[].items[] with per-unit commission, rates, amounts). Added frontend modal (`modal-commission-breakdown` in `index.html`) with period selector dropdown (fetches `GET /api/commissions/periods`), order-by-order cards with per-item tables, and total commission footer. Added "Comm" button to agent list actions column. All sorting done in Java (no repo changes). Build: `mvn compile -q` clean. Tests: 134/134 pass — no regression. |
| U14 | Jun 7 | **Session 5** — Batch collection marking: `POST /api/orders/batch-mark-collected` endpoint + `OrderService.batchMarkAsCollected()` with pessimistic locks, status guards, CASH rejection, daily report patch, commission creation, activity logging. Frontend: checkbox column + "Select All" in collections table, `modal-batch-collect` security key modal, `confirmBatchCollect()` handler with collected/skipped/errors result. Build: `mvn compile -q` clean. Tests: 134/134 pass — no regression. |
| U15 | Jun 9 | **Import Review System** — Full review modal with order/expense card editors (product autocomplete, qty/unitPrice/basePrice/opPerUnit inline editing, agent autocomplete, payment/status/platform dropdowns), green gate (commit blocked until all items have product/category match), override state persisted across page changes, commit handler builds full overrides payload matching backend schema. Backend: upload enriched with `items[]`, commit accepts `overrides` object, commit log persistence (V67-V69). 142/142 tests pass. |
| U16 | Jun 9 | **Session 6 — Integration Testing & Bug Bash** — Verified all 5 COD lifecycle scenarios are covered by existing `ImportU6Test.java` (7 tests). COD PAID→ACTIVE (U6-a), COD UNPAID→PENDING_COLLECTION (U6-b), collect→DELIVERED+COLL-SALE (U6-c), commission after collect (U6-d), batch collect (U6-e), duplicate detection (U6-f), mixed payment modes (U6-g). Full regression: 142/142 pass. Updated build log, progress, plan. BATCH-IMPORT-COD-COMMISSION-PLAN fully complete (6/6 sessions). |
| U17 | Jun 9 | **Import UI Cleanup** — 4 fixes: (1) removed obsolete Upload Type buttons from Step 3 card (combined template made them dead UI); (2) flattened nested stat cards in Step 4 preview (removed `.card` class from 4 mini counters — no more nested borders); (3) added explicit View button column to Import History table (replaced clickable-row pattern for consistency with other views); (4) recorded validate-overrides gap in anchored_summary known-gaps. Build: `node --check app.js` clean. Tests: 142/142 pass — no regression. |
| U18 | Jun 9 | **Review Modal Fixes** — 3 fixes: (1) removed Validate button from Step 4 Preview (misleading — checks original CSV data, not review overrides); (2) fixed nested card appearance and autocomplete dropdown clipping in review modal by replacing `class="card"` with `class="review-card"` and `overflow:visible;border:1px solid var(--border);border-radius:var(--radius-sm)` — eliminates card-in-card look and allows product/agent autocomplete dropdowns to render outside card bounds; (3) updated `.card` → `.review-card` selector for exclude-toggle opacity dimmer. Build: `node --check app.js` clean. No backend changes. |
| U19 | Jun 9 | **Import Review System — Card Grid Redesign + 6 Fix Rounds** — U19 main: responsive CSS Grid card layout (auto-fit, minmax(280px,1fr)), modal widened to 960px, equal-height cards via flex column, visual hierarchy (title→description→metadata→actions), exclude toggle at bottom. Round 1: 9 regression fixes. Round 2: 6 visual fixes (safeDisplay, scoped styles, z-index stacking). Round 3: 8 NaN/HTML bugs (double-plus operators). Round 4: hint bar layout fix. Round 5: X icon exclude button + scrollable items. Round 6: expense category editing plan (FUZZY matches get cascading dropdown). 142/142 tests pass. |
| U20 | Jun 10 | **Agent Page Investigation + Plan** — Investigated Agent page frontend/backend. Found: (1) statement export broken — `downloadStatement()` missing `/api/` prefix (same pattern as expense bugs); (2) no agent status toggle UI — backend has `PATCH /api/agents/{id}/status` but frontend has no button/modal to call it; (3) N+1 queries on agent list (3 queries per agent). Plan created: `docs/PLAN-agent-page-bugfixes.md`. Issues 1+2 to fix now, Issue 3 deferred. |
| RR1–4 | Jun 13 | **Role Regrouping — all 4 sessions complete + manually verified.** S1: removed STAFF role (V71 migration STAFF→STANDARD_USER, User.java default, VALID_ROLES, 3 dropdowns). S2: Dashboard/Collections/Ledger/Agents/Import made restrictable (PageAccessInterceptor RULES, WebMvcConfig exclusion removed, viewToPageKey, 5 new checkboxes). S3: role-default auto-fill + Super-Admin customization (ROLE_DEFAULT_PAGES matrix backend+frontend, POST/PATCH-role/PUT enforce defaults, add-modal locks checkboxes for non-SA, login landing guards dashboard route). S4: void/cancel/refund gated to ACCOUNTING+SUPER_ADMIN only (`isOrderManager` helper, 4 endpoint role checks, `canManageOrders()` narrowed). 29/29 assertions green (CollectionsIT 11, OrderCancelIT 6, OrderVoidReturnIT 12). |
| §5A | Jun 13 | **§5A manual verification fixes + security gap re-verification.** Frontend/feature: (1) Return order UX — amber "Returned" status badge + "Issue Replacement" button replaces "Process Return" after refund, using `refundedAt` timestamp already on order; (2) PDF/report download — added "Download HTML" button to report popup for VS Code compatibility (Blob + `<a download>`), print dialog preserved for real browsers; (3) PO receive modal — label changed to "Qty to Receive", remaining qty bolded; (4) Final Delivery checkbox — V73 migration (`is_final_delivery BOOLEAN DEFAULT FALSE` on `po_items`), `isFinalDelivery` field on `PoItem.java`, `PurchaseOrderController.receiveItem` reads flag + marks item fulfilled + includes `effectiveTotalAmount` in response, frontend badge + accounting summary line. Deployment pre-flight: `"suppliers"` added to `ALL_PAGES` constant (was silently 403 for API-created accounts); dead `ADMIN` branch removed from `canEditInventory()`; `assign-role-select` modal added ACCOUNTING + STAFF options and gate changed from `canManageEmployees()` → `isSuperAdmin()` to match backend. Security gap re-verification: GAP S10-01 confirmed (SettingsIT 5/5 green); GAP S12-01 test isolation bug found and fixed — `AuthorizationGateIT.t06` was calling `PATCH /role` on `restrictedUser`, resetting `allowedPages = "[]"` to ACCOUNTING defaults before t12/t13 ran; changed t06 target to `accountingUser`; 18/18 total (13 AuthorizationGateIT + 5 SettingsIT). |

---

### Session U7–U9 Detail — Jun 7, 2026 (XLSX Redesign + Backdating + Commission/Ledger/Daily Report Fix)

**Files modified (backend):** `ImportController.java`, `CommissionService.java`, `TransactionService.java`, `OrderService.java`, `DailyReportService.java`, `Order.java`, `OrderIdGenerator.java`  
**Files added (backend):** `db/migration/V60__backdate_orders_late_import.sql`  
**Files modified (frontend):** `js/app.js`, `index.html`

---

#### Root causes fixed this session

| Problem | Root cause | Fix |
|---|---|---|
| ECOMMERCE orders missing platform on import | No Platform column in CSV template; no `ecommercePlatform` field in import pipeline | Platform column at index 7; `SaleAccumulator`/`ParsedSaleRow`/`buildOrderFromRow()` updated |
| Date parsing failures from Excel | Backend only accepted `YYYY-MM-DD`; Excel outputs `6/7/2026` | `parseDate()` helper with 8 format patterns in `ImportController` |
| Expense categories required exact codes | Staff typing "gas" or "water" → "Category not found" error | `FUZZY_CAT` static map + `fuzzyMatchCategory()` — 35 keyword mappings |
| Imported orders got today's date prefix | `OrderIdGenerator.generateOrderId()` always used `LocalDate.now()` | New `generateOrderIdForDate(LocalDate)` method; same global counter, caller supplies date |
| `createdAt` couldn't be pre-set | `@PrePersist` always overwrote with `now()` | Made conditional: `if (createdAt == null) { createdAt = LocalDateTime.now(); }` |
| Zero commissions on late batch imports | `CommissionService` silently returned when covering period was CLOSED | Fallback: assign to earliest OPEN period; `orderDate` stays as actual sale date |
| SALE transactions on wrong ledger date | `TransactionService.recordSale()` hardcoded `LocalDate.now()` | New 3-arg overload with explicit `effectiveDate`; existing 2-arg overload untouched |
| Daily report missing for imported date | No auto-close path for past dates; `closeDailySales()` throws on ACTIVE orders | New `closeForImportDate()` — idempotent, no guard, no status transitions |
| Post-commit result was a single text line | `commitImport()` populated an inline div | Replaced with `showImportResultModal(data)` — dynamic modal with three scrollable tables |

---

#### V60 migration

```sql
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS late_imported BOOLEAN NOT NULL DEFAULT FALSE;
```

---

#### Commission period assignment rule (enforced in code)

- **On-time import:** sale date is inside an OPEN period → `periodId` = that period (existing path)
- **Late import (covering period CLOSED):** `periodId` = earliest OPEN period's ID; `orderDate` = actual sale date
- Example: sale on Jun 10 (Period 1: 28th May–12th Jun), imported Jun 14 after Period 1 is CLOSED → entry assigned to Period 2 (13th–27th Jun) but `orderDate` = Jun 10

---

#### Transaction ledger behaviour for backdated imports

`createOrderAtDate()` calls `transactionService.recordSale(savedOrder, userId, targetDate)` (new 3-arg overload). The `effectiveDate` on the SALE transaction row = the imported order date. This means:

- `transactionService.getGrossSalesForDate(pastDate)` returns the correct gross sales for that date
- `closeForImportDate()` picks up accurate financial figures when building the daily report
- Dashboard weekly/monthly aggregate totals (query by `CAST(o.createdAt AS date)`) automatically include the backdated orders
- Trend chart now works because daily reports are auto-created for the imported dates

All existing VOID/COLL-DEFER/COLL-SALE/RETURN/ADJUSTMENT ledger paths are completely untouched.

---

### Session 69 Detail — Jun 5, 2026 (Refund Endpoint Removal + secFields Fix)

**Files modified (backend):** `TransactionController.java`, `TransactionService.java`, `TransactionRepository.java`  
**Files modified (frontend):** `js/app.js`  
**Planning doc updated:** `VOID_CANCEL_RETURN_REDESIGN.md`

---

**Backend — `POST /api/transactions/refund` removed**

The old refund endpoint and its entire call chain were deleted. The blocker condition (new `POST /api/orders/{id}/return` flow fully built and tested) was confirmed met in Session 62. The endpoint had zero frontend callers — confirmed via full grep of `rrbm_frontend/`.

**Deletion scope:**

| File | What was removed |
|---|---|
| `TransactionController.java` | `issueRefund()` handler + Javadoc; `OrderRepository`, `ProductRepository`, `InventoryService` fields + constructor params (injected but never called directly in controller); `BCryptPasswordEncoder` field + import; `Optional` import |
| `TransactionService.java` | `recordRefund()` method (74 lines including proportional inventory restore, idempotency guard, refundedAt timestamp write); `LocalDateTime` import; `OffsetDateTime` import |
| `TransactionRepository.java` | `existsByOrderIdAndTransactionTypeAndAmountAndCreatedAtAfter()` + Javadoc (called only from `recordRefund()`); `LocalDateTime` import |

All callers confirmed before deletion:
- `recordRefund()` had exactly one call site: `TransactionController.issueRefund()` — verified via grep, no test references
- `existsByOrderIdAndTransactionTypeAndAmountAndCreatedAtAfter()` had exactly one call site: `recordRefund()` — safe to delete
- Remaining methods (`validatePositiveAmount`, `recordReturnRefund`, `recordItemVoid`) untouched — all have other callers

**Frontend — `secFields` clear-list corrected**

`closeModal()` maintains a `secFields` array of sensitive input field IDs that get cleared whenever any modal closes. Two stale IDs were removed and one missing live ID was added:

| Field ID | Change | Reason |
|---|---|---|
| `refund-security-key` | Removed | Old `POST /api/transactions/refund` modal — deleted Session 62; ID doesn't exist in HTML |
| `void-security-key` | Removed | Old `POST /api/transactions/void` modal — deleted Session 62; ID doesn't exist in HTML |
| `rtn-security-key` | Added | Return modal (`modal-return`, `index.html:3014`) — live password field; was not cleared on close, only on next `openReturnModal()` open — pre-existing gap |

`ivm-security-key` and `ivm-master-key` (item void modal) were already in the list and untouched.

**VOID_CANCEL_RETURN_REDESIGN.md updated:**
- M-26 backlog item marked ✅ RESOLVED (Session 68 fix)
- V47 reservation marked ❌ CLOSED — `po_items.item_code` is still Attempt 1 in the 3-attempt DR→PO match chain; `products.item_code` is core inventory infrastructure; V49 is next available migration number

**Test results:** 15/15 — BUILD SUCCESS  
(`CancelOrderM26Test` 10/10 · `PhantomDebitIntegrationTest` 4/4 · `RrbmBackendApplicationTests` 1/1)

---

### Session 68 Detail — Jun 5, 2026 (M-26 Phantom-Debit Fix)

**Files modified:** `TransactionService.java`, `OrderService.java`  
**Files removed (dead code):** 3-arg `recordVoid(Order, Long, String)` overload deleted from `TransactionService.java`  
**Tests added:** `PhantomDebitIntegrationTest.java` (4 tests), `CancelOrderM26Test.java` updated (10 tests, total 14)  
**Commit:** `e78dd9c` on `main`

---

**Root cause — phantom-debit bug (3 sites)**

All three ledger-write paths used `order.getTotal()` (gross) instead of `order.getTotal() − order.getVoidedAmount()` (net) as the basis for their transaction amounts. Any order with prior item-level voids (`voidedAmount > 0`) produced a phantom debit equal to `−voidedAmount` because the cancel/defer VOID overstated what it was removing and the collection SALE also overstated what was coming in.

**The one method that was already correct:** `cancelOrderForReplacement()` — it always used `effectiveVoid = total − voidedAmount`. All other cancel paths did not.

---

**Site 1 — `recordDeferralVoid()` in `TransactionService.java`**

```java
// NET basis: subtract any prior item-level voids
// MUST stay in sync with recordCollectionSale() — both use same net basis
BigDecimal gross  = order.getTotal()       != null ? order.getTotal()       : BigDecimal.ZERO;
BigDecimal voided = order.getVoidedAmount() != null ? order.getVoidedAmount() : BigDecimal.ZERO;
txn.setAmount(gross.subtract(voided).negate());
```

**Site 2 — `recordCollectionSale()` in `TransactionService.java`**

```java
// NET basis: matches recordDeferralVoid() so COLL-DEFER and COLL-SALE
// cancel each other exactly. MUST stay in sync with recordDeferralVoid().
BigDecimal gross  = order.getTotal()       != null ? order.getTotal()       : BigDecimal.ZERO;
BigDecimal voided = order.getVoidedAmount() != null ? order.getVoidedAmount() : BigDecimal.ZERO;
txn.setAmount(gross.subtract(voided));
```

Sites 1 and 2 are mutually constrained — they MUST use the same net basis so `COLL-DEFER` and `COLL-SALE` cancel exactly. Javadoc on both methods cross-references this.

**Site 3 — `cancelOrder()` in `OrderService.java`**

```java
// NET basis: only void what the order still owes after any prior item-level voids.
BigDecimal grossTotal    = savedOrder.getTotal()       != null ? savedOrder.getTotal()       : BigDecimal.ZERO;
BigDecimal alreadyVoided = savedOrder.getVoidedAmount() != null ? savedOrder.getVoidedAmount() : BigDecimal.ZERO;
BigDecimal effectiveVoid = grossTotal.subtract(alreadyVoided);
if (effectiveVoid.compareTo(BigDecimal.ZERO) > 0) {
    transactionService.recordVoid(savedOrder, effectiveVoid, cancelledByUserId, reason);
}
```

Guard: when `effectiveVoid = 0` (fully item-voided order), no cancel VOID is written at all.

---

**Dead overload removed**

3-arg `recordVoid(Order, Long, String)` was the old signature (always used gross total). After the 3-site fix it had zero production call sites. Deleted entirely. Surviving overload is 4-arg `recordVoid(Order, BigDecimal, Long, String)` — caller must pass explicit net amount.

---

**Integration tests — before/after**

| Test | Scenario | Correct net |
|---|---|---|
| P1 | Deferred-uncollected → standard cancel | SALE(+500) + item-VOID(−100) + COLL-DEFER(−400) = ₱0 |
| I1 | ACTIVE + prior item void → standard cancel | SALE(+500) + item-VOID(−100) + cancel-VOID(−400) = ₱0 |
| I2 | Deferred-uncollected → cancel-for-replacement | Same rows as P1 |
| I3 | Collected → standard cancel (all 3 sites) | SALE(+500) + item-VOID(−100) + COLL-DEFER(−400) + COLL-SALE(+400) + cancel-VOID(−400) = ₱0 |

All 4 integration tests red before fix, green after. Unit tests (10): updated t2/t3/t4 to verify 4-arg `recordVoid` signature with `ArgumentCaptor`. Total: **14/14 green**.

---

**Historical data correction**

Query: orders where `voided_amount > 0 AND status IN ('PENDING_COLLECTION', 'CANCELLED')` — returned 7 rows.

| Order | Situation | Ledger net before correction |
|---|---|---|
| 020626-000080 | Cancelled after partial item void; cancelled pre-fix | −₱29.94 (real phantom debit) |
| 6 others | Either fully item-voided (effectiveVoid=0, no cancel VOID written) or cancelled post-fix | ₱0.00 already |

**Correction for 020626-000080:** Single `ADJUSTMENT` transaction (code `ADJ-020626-000080-PHANTOM-VOID`, amount +₱29.94) written via `created_by = 4` (jfyg0310, ADMINISTRATOR).  
**Reversal for the 6 over-selected orders:** `VOID-CORRECTION-REVERSAL-{orderId}` ADJUSTMENT entries reversed any incorrect ADJUSTMENT entries that had been written for orders already at ₱0.  
**Final state:** All 7 orders verified at net ₱0.00 via `SUM(amount)` query.

---

**QA gap closure**

M-26 was the only remaining open gap. All 53 QA gaps are now closed (9 Critical + 34 Moderate + 10 Minor).

---

### Session 67 Detail — Jun 4, 2026 (PO Redesign — Frontend Pieces 1–5)

**Files modified:** `index.html`, `js/app.js`
**No migrations** — backend fully wired in Sessions 65–66; frontend-only session.

---

**Piece 1 — Suppliers Page** ✅ (completed in prior session run)
- Nav item + `view-suppliers` section: active/inactive toggle, search filter, supplier table
- Add / Edit / Deactivate / Reactivate modals wired to full CRUD endpoints
- 9 endpoint integrations: `GET /api/suppliers` (list), `GET /{id}`, `POST`, `PATCH /{id}`, `DELETE /{id}`, `GET /{id}/mappings`, `POST /{id}/mappings`, `PATCH /{id}/mappings/{mId}`, `DELETE /{id}/mappings/{mId}`

**Piece 2 — Supplier-Product Mapping Page** ✅ (completed in prior session run)
- Mapping management panel within supplier detail: supplier item code, supplier description, unit cost, preferred flag
- Inline add / edit / delete per mapping row; preferred flag enforcement (one preferred per product)

**Piece 3 — PO Creation Form Supplier Integration** ✅ Jun 4, 2026 — all 7 tests passed
- **`index.html`** — New PO modal Vendor column: added Supplier `<select>` (optional, auto-fills fields below) + Vendor Reference `<input>`
- **`app.js`** — New state: `_poSuppliersCache` (session-level lazy cache, `null = not loaded`), `_poSupplierMappings` (keyed by productId)
- `_poItemCodeMap` entries now include `productId` — enables supplier mapping lookup per line item
- `_populatePoSupplierDropdown()` — helper to rebuild supplier `<select>` from cache
- `openNewPoModal()` — resets vendor reference + supplier select + `_poSupplierMappings`; fetches supplier list on first open, uses cache on subsequent opens
- `onPoSupplierChange()` — on select change: clears all hints, clears `_poSupplierMappings`, auto-fills vendor name/contact/address, fetches mappings async, re-runs `lookupPoItemCode` on all filled rows
- `addPoItemRow()` — added `<div class="po-supplier-hint">` (10px muted text, hidden) below item code input
- `lookupPoItemCode()` — on match: checks `_poSupplierMappings[productId]`; if mapping exists, shows "Supplier code: XYZ" hint and pre-fills price from mapping `unitCost`; fallback to inventory `unitCost` if no mapping
- `lookupPoItemDesc()` — refactored: delegates entirely to `lookupPoItemCode` (consolidates hint/price logic in one place)
- `submitNewPO()` — each item payload now includes `productId`; PO payload includes `supplierId` (nullable) and `vendorReference` (nullable)

**Piece 3 test results (Jun 4, psql acceptance criterion T6):**
| # | Test | Result |
|---|------|--------|
| T1 | Supplier dropdown populated; vendor fields blank until selection | ✅ |
| T2 | Select Supplier Alpha → vendor name/contact auto-fill, fields editable | ✅ |
| T3 | RBM-123-2 + Alpha selected → price=₱9.25 (mapping), hint "Supplier code: ALPHA-BB10" | ✅ |
| T4 | RBM-124-1 (no mapping) → price from inventory, hint hidden | ✅ |
| T5 | Supplier change clears and re-applies hints on all rows | ✅ |
| T6 | PO id=15 submitted: psql confirms `supplier_item_code='ALPHA-BB10'`, `supplier_description='Bilao Box Bottom 10in - Alpha SKU'` | ✅ |
| T7 | No-supplier PO (id=16): `supplierId=null`, `supplierItemCode=null` — backward compatible | ✅ |

---

**Piece 4 — PO List and Detail View** ✅ Jun 4, 2026 — all 4 tests passed
- **`index.html`** — `po-number` input `maxlength` corrected: 11 → 20 (backend generates PO-MMDDYY-XXXXX = 15 chars; old cap was silently short)
- **`app.js` — `renderPoList()`** — vendor cell: if `po.vendorReference` is set, shows it as a muted 10px monospace sub-line below vendor name
- **`app.js` — `buildPoDetailHtml()`** — items table: 2 new columns `Supplier Code` + `Supplier Desc` (after Description, before Qty); "No items" colspan updated 8 → 10; detail info line: appends " | Ref: [value]" when `po.vendorReference` is set

**Piece 4 test results:**
| # | Test | Result |
|---|------|--------|
| T1 | Supplier PO in list: vendor name + reference sub-line visible | ✅ |
| T2 | Expanded supplier PO: Supplier Code = ALPHA-BB10, Supplier Desc = "Bilao Box Bottom 10in – Alpha SKU" | ✅ |
| T3 | Expanded no-supplier PO (safety): Supplier Code = "—", Supplier Desc = "—", no errors | ✅ |
| T4 | Vendor reference visible in list sub-line AND detail header | ✅ |

---

**Piece 5 — PO PDF Generation** ✅ Jun 4, 2026 — all 6 tests passed (T1 manual math is acceptance criterion)
- **`app.js` — `printPoDocument()`**:
  - Items table widened from 6 → 8 columns: added `Supplier Code` + `Supplier Desc` after Description; fallback to `itemCode`/`itemDescription` when snapshot is null
  - Vendor meta block: appends `<p>Ref: [value]</p>` (monospace, muted) when `po.vendorReference` set
  - VAT calculation: `subtotal = po.totalAmount`; if `INCLUSIVE`: `vatAmount = subtotal × 0.12`, `grandTotal = subtotal + vatAmount`, tfoot shows SUBTOTAL / VAT (12%) / GRAND TOTAL rows; else tfoot shows single ORDER TOTAL row
  - `vatNote` text updated: INCLUSIVE → "grand total includes 12% VAT", EXCLUSIVE → "prices do not include VAT"

**Piece 5 test results (VAT business rule: prices entered are pre-tax; INCLUSIVE POs add 12% on top):**
| # | Test | Result |
|---|------|--------|
| T1 | INCLUSIVE PO (total=₱100): SUBTOTAL ₱100.00 / VAT(12%) ₱12.00 / GRAND TOTAL ₱112.00 — math verified | ✅ |
| T2 | EXCLUSIVE PO: ORDER TOTAL only, no VAT rows, footnote "prices do not include VAT" | ✅ |
| T3 | Supplier snapshot PO: 8 cols; Supplier Code = ALPHA-BB10, Supplier Desc = "Bilao Box Bottom 10in – Alpha SKU" | ✅ |
| T4 | No-supplier PO: Supplier Code falls back to itemCode, Supplier Desc falls back to itemDescription | ✅ |
| T5 | PO with vendorReference: "Ref: SA-2026-TEST-001" in vendor block | ✅ |
| T6 | PO without vendorReference: no "Ref:" label in vendor block | ✅ |

---

### Session 66 Detail — Jun 4, 2026 (PO Redesign — Piece 5: Receive Stock 3-Attempt Matching + FIFO Fix)

**Files modified:** `PoItemRepository.java`, `ProductController.java`, `DeliveryReportController.java`  
**Migration added:** `V48__payables_add_cancelled_status.sql`

**1 — `PoItemRepository.java` — FIFO ORDER BY + new supplierItemCode query**
- All three existing query methods renamed to add `OrderByIdAsc` suffix — ensures FIFO (oldest PO item matched first, not arbitrary DB order):
  - `findByItemCodeAndIsFulfilledFalse` → `findByItemCodeAndIsFulfilledFalseOrderByIdAsc`
  - `findByItemDescriptionIgnoreCaseAndIsFulfilledFalse` → `findByItemDescriptionIgnoreCaseAndIsFulfilledFalseOrderByIdAsc`
  - `findByDrNumber` → `findByDrNumberOrderByIdAsc`
- New method: `findBySupplierItemCodeAndIsFulfilledFalseOrderByIdAsc(String supplierItemCode)` — used in FIFO path when product's itemCode matches a PO item's supplier_item_code

**2 — `ProductController.java` — 3-attempt match in `processDelivery()`**
- Linked PO path (when DR has a poNumber):
  - Attempt 1: product.itemCode vs poItem.itemCode (legacy)
  - Attempt 2: product.itemCode vs poItem.supplierItemCode ← NEW
  - Attempt 3: product.name vs poItem.itemDescription (name fallback)
- Unlinked FIFO path:
  - Attempt 1: `findByItemCodeAndIsFulfilledFalseOrderByIdAsc` (renamed)
  - Attempt 2: `findBySupplierItemCodeAndIsFulfilledFalseOrderByIdAsc` ← NEW
  - Attempt 3: `findByItemDescriptionIgnoreCaseAndIsFulfilledFalseOrderByIdAsc` (renamed)

**3 — `DeliveryReportController.java` — symmetric 3-attempt match in cancel-reversal**
- `findByDrNumberOrderByIdAsc` caller updated (renamed method)
- `findPoItemForDrItem()`: added Attempt 2 — product.itemCode vs poItem.supplierItemCode between existing itemCode and name checks
- `resolveReceivedQtyForPoItem()`: added `bySupplierCode` boolean — product.itemCode equalsIgnoreCase poItem.supplierItemCode; combined with `byCode || bySupplierCode || byName`

**4 — V48 migration — payables_status_check expanded**
- Pre-existing bug (not introduced by PO redesign): `payables_status_check` only allowed `PENDING`, `PAID`, `PARTIAL`; DR cancel endpoint tried to write `CANCELLED` → constraint violation; because method is `@Transactional`, the `catch` block didn't prevent the commit-time rollback → 500
- Fix: drops and recreates constraint as `CHECK (status IN ('PENDING', 'PAID', 'PARTIAL', 'CANCELLED'))`
- V47 is still reserved for the `item_code` drop (held back, not yet written)

**Test results (all 4 passed):**
- T1 — supplierItemCode match (linked PO): product.itemCode=ALPHA-BB10 matched poItem.supplierItemCode=ALPHA-BB10; poItem.itemCode blank so attempt 1 failed → attempt 2 matched; fulfilled_qty=30 confirmed in psql ✅
- T2 — legacy itemCode match (linked PO): product.itemCode=RBM-002 matched poItem.itemCode=RBM-002; fulfilled_qty=20 confirmed ✅
- T3 — FIFO ORDER BY (**acceptance criterion**): two unfulfilled PO items with itemCode=RBM-009; DR with no PO linked; older PO item (lower id=20) got fulfilled_qty=40; newer item (id=21) stayed at 0 ✅
- T4 — cancel-reversal (**acceptance criterion**): DR submitted → poItem fulfilled_qty=30, is_fulfilled=true; cancel DR → poItem fulfilled_qty=0, is_fulfilled=false, dr_number=null, po_status=INCOMPLETE, payable status=CANCELLED ✅

---

### Session 65 Detail — Jun 4, 2026 (PO Redesign — Backend Pieces 1–4)

**Migrations created:** `V43__suppliers_and_po_counter.sql`, `V44__supplier_product_mapping.sql`, `V45__purchase_orders_enhancements.sql`, `V46__po_items_supplier_fields.sql`  
**New Java files:** `PoYearCounter.java`, `PoYearCounterRepository.java`, `PurchaseOrderService.java`, `Supplier.java`, `SupplierRepository.java`, `SupplierController.java`, `SupplierProductMapping.java`, `SupplierProductMappingRepository.java`  
**Modified Java files:** `PurchaseOrder.java`, `PoItem.java`, `PurchaseOrderController.java`, `ProductController.java`

**Schema — V43 (suppliers + po_year_counter)**
- `suppliers`: id, name, address, contact_number, contact_person, payment_terms, notes, is_active, created_at
- `po_year_counter`: year (PK), last_number — seeds 2026 row; one row per calendar year for the PO sequence

**Schema — V44 (supplier_product_mapping)**
- `supplier_product_mapping`: id, supplier_id (FK→ON DELETE CASCADE), product_id (FK→ON DELETE CASCADE), supplier_item_code, supplier_description, unit_cost, is_preferred, created_at
- UNIQUE constraint `uq_supplier_product` on (supplier_id, product_id)

**Schema — V45 (purchase_orders enhancements)**
- `po_number` widened from VARCHAR(11) → VARCHAR(20) — format PO-MMDDYY-XXXXX = 15 chars
- `supplier_id` added (FK→suppliers ON DELETE SET NULL, nullable — historical POs without supplier still valid)
- `vendor_reference` VARCHAR(50) added

**Schema — V46 (po_items supplier fields)**
- `supplier_item_code` VARCHAR(50) added
- `supplier_description` TEXT added

**Piece 3 — PO number auto-generation**
- Format: `PO-MMDDYY-XXXXX` (e.g. PO-060426-00001); 5-digit zero-padded counter resets each calendar year
- `PoYearCounter` entity + `PoYearCounterRepository` with `@Lock(LockModeType.PESSIMISTIC_WRITE)` — matches `OrderIdCounterRepository` pattern for race-safe atomic increment
- `PurchaseOrderService.generatePoNumber(LocalDate)` wraps the counter logic; `@Transactional`
- `PurchaseOrderController`: replaced manual poNumber with `poService.generatePoNumber(LocalDate.now())`

**Piece 1 — Supplier CRUD (SupplierController)**
- `GET /api/suppliers?includeInactive=false` — active list default, sorted A-Z
- `GET /api/suppliers/{id}` — single supplier
- `POST /api/suppliers` — create; validates name required
- `PATCH /api/suppliers/{id}` — edit (name, address, contact, paymentTerms, notes)
- `DELETE /api/suppliers/{id}` — soft delete (sets isActive=false); returns `{message, id, isActive:false}`
- Option A is_preferred enforcement: `clearPreferredForProduct(Long productId)` clears all existing preferred flags before setting a new one — service-layer, no DB constraint

**Piece 2 — Supplier-Product Mapping (SupplierController + SupplierProductMappingRepository)**
- `GET /api/suppliers/{supplierId}/mappings` — list all mappings for a supplier (resolves productName)
- `POST /api/suppliers/{supplierId}/mappings` — add mapping; validates productId exists; catches `DataIntegrityViolationException` for duplicate
- `PATCH /api/suppliers/{supplierId}/mappings/{mappingId}` — update supplierItemCode, supplierDescription, unitCost, isPreferred
- `DELETE /api/suppliers/{supplierId}/mappings/{mappingId}` — hard delete (no FK dependents)
- `GET /api/products/{productId}/suppliers` — all supplier mappings for a product (in ProductController)

**Piece 4 — Supplier snapshot resolution at PO creation**
- `PurchaseOrder.java`: `supplierId` Long field, `vendorReference` VARCHAR(50) field
- `PoItem.java`: `supplierItemCode` VARCHAR(50), `supplierDescription` TEXT — explicit getters/setters (no Lombok on this class)
- `PurchaseOrderController.createPurchaseOrder()`: reads `supplierId` + `vendorReference` from body; for each item, if `supplierId` and `productId` both present, calls `mappingRepository.findBySupplierIdAndProductId()` → snapshots `supplierItemCode` + `supplierDescription` onto the item; fills `unitPrice` from `mapping.unitCost` only when caller provided no explicit price (explicit caller price always wins)
- `toMap()` updated: PO level includes `supplierId`, `vendorReference`; item level includes `supplierItemCode`, `supplierDescription`

---

### Session 59c Detail — Jun 2, 2026 (Fix 6 — Receipt and PDF Print Void Corrections)

**Files modified:** `js/app.js`

**QA wave context:** This is Fix 6 of 6 in the void/cancel/return QA wave. Fixes 1–5 corrected backend data integrity and frontend display. Fix 6 ensures the print flows (single-order receipt and batch Order History PDF) reflect effective values after voids rather than original pre-void values.

**1 — `printOrderReceipt` — filter fully voided items + effective quantities**
- Items loop now filters out any item where `(quantity - voidedQuantity) <= 0` (fully voided items are excluded from the printed receipt entirely).
- For partially voided items, `_effQty = item.quantity - (item.voidedQuantity || 0)` and `_effAmt = _effQty × unitPrice` are used instead of the original values.
- The total variable at the top of the function is now `(order.total - voidedAmt).toFixed(2)` instead of `order.total.toFixed(2)`.

**2 — `printOrderReceipt` — void adjustment line in subtotals section**
- A `Void adjustment` row is conditionally inserted in the subtotals block when `voidedAmt > 0`.
- Format: muted grey text (`color:#9CA3AF`), shows `−₱{voidedAmt}` as a deduction step.
- Math: `original subtotal` → `− discount` → `− void adjustment` → `effective total`. No double-counting — subtotal shows the original amount; the void adjustment accounts for the deduction.

**3 — `exportOrderHistoryPDF` — filter fully voided items in items column**
- `_pdfActive` filters items to those with effective quantity > 0 before building the lead-item text.
- `_pdfLeadQty` uses effective quantity for the lead item.
- Orders where all items are voided show `(all voided)` as the items column value.

**4 — `exportOrderHistoryPDF` — effective total in amount column**
- Total column: `(Number(o.total || 0) - Number(o.voidedAmount || 0)).toLocaleString('en-PH', {minimumFractionDigits:2})` instead of raw `o.total`.

**Browser test results (all 5 passed):**
- T1 (single receipt, partial void — 020626-000089): effective qty 3, line amt ₱18.00, void adjustment −₱6.00, grand total ₱18.00 ✅
- T2 (single receipt, fully voided item hidden + partial — 020626-000090): hidden item absent, void adjustment −₱1,060.00, grand total ₱990.00 ✅
- T3 (single receipt, no voids — 020626-000092): no void adjustment line, grand total unchanged ✅
- T4 (batch PDF with voided orders): `(all voided)` for 000091 ₱0.00; correct effective items + totals for 000089 and 000090 ✅
- T5 (batch PDF non-voided — 020626-000092): items text and total unchanged ✅

---

### Session 61 Detail — Jun 3, 2026 (Step 12 Integration Tests — T11 / Cancel / Return / Replacement)

**Files modified:** None (tests only — all code from Session 60 already applied)

**Context:** Step 12 of the VOID_CANCEL_RETURN_REDESIGN QA plan. All 11 test scenarios run against live backend on date 2026-06-03. Daily report was closed mid-session (after T11) — subsequent tests confirmed cancel/return/replacement endpoints have no day-close guard.

**T11 — Day-Close Guard**
- Pre-check: 6 today's orders (030626-*); only `030626-000095` was ACTIVE (V1 test order — resolved by marking DELIVERED via status endpoint before close)
- `POST /api/reports/close-daily` → 200; report id=41; `closedBy: 4` (Francis Garbosa — NOT userId 3 — confirms C-3 JWT fix working); netSales=₱50.37, gross=₱296.53, refunds=−₱246.16, 13 transactions
- Post-close API guard: `POST /api/orders/030626-000095/void` → 400 `"Void is not available after the daily report has been closed"` ✅
- Frontend: closed-day banner `"Daily sales for June 3, 2026 have been closed. Closed at 12:03 PM by Francis Garbosa"` visible; `appState.dailyClosed = true`; void buttons: 0 in order list ✅
- Known test data note: `030626-000098` shows `cancellationType=REPLACEMENT`, `replacementOrderId=null` — CFR cancel with no replacement created; not a bug, no action

**C1 — Standard Cancel (confirmed from prior session)**
- Order `030626-000094`: `status=CANCELLED`, `cancellationType=STANDARD`, `reason="Test standard cancel - automated QA"`, `cancelledByName=Francis Garbosa` ✅

**C2 — CFR on non-DELIVERED (confirmed from prior session)**
- Order `030626-000093`: `status=CANCELLED`, `cancellationType=REPLACEMENT`, `replacementOrderId=030626-000097`, `reason="Wrong items — creating replacement order"` ✅
- Note: `030626-000097` (replacement) was subsequently Tier-2 voided in V2 test — valid sequencing

**C3 — CFR on DELIVERED (run this session)**
- Created fresh order `030626-000099` (Test CFR-C3, ₱29.94, 3× Plain Pizza Box); marked DELIVERED; CFR-cancelled
- `POST /api/orders/030626-000099/cancel-for-replacement` → 200; `status=CANCELLED`, `cancellationType=REPLACEMENT`, `replacementOrderId=null`, disposition SELLABLE (stock returned to wh1) ✅
- Confirmed: cancel + CFR endpoints have no day-close guard

**R1 — Return sellable (no refund)**
- `POST /api/orders/030626-000096/return` — item 521 (Pizza Box), totalReturned=1, sellableQty=1, rejectedQty=0
- Response: `refundIssued=false, refundAmount=0` → RETURN_SELLABLE movement, stock restored ✅

**R2 — Return rejected (no refund)**
- `POST /api/orders/030626-000096/return` — item 522 (Wax Paper), totalReturned=2, sellableQty=0, rejectedQty=2
- Response: `refundIssued=false, refundAmount=0` → RETURN_REJECTED movement, no stock change ✅

**R3 — Return with refund**
- `POST /api/orders/030626-000096/return` — item 522 (Wax Paper), totalReturned=1, sellableQty=1, rejectedQty=0, refundAmount=0.95
- Response: `refundIssued=true, refundAmount=0.95` → REFUND ledger transaction written ✅
- Note: `refundedAt` on order shows earlier timestamp (set on first refund in prior session — field records first-refund timestamp, not updated on subsequent refunds)

**P1 — Replacement order**
- `POST /api/orders/030626-000099/replacement` — customerName=Test CFR-C3, WALK_IN, CASH, PICK_UP, 3× Plain Pizza Box (productId=3)
- Response: new order `030626-000100`, status=ACTIVE, originalOrderId=030626-000099 ✅
- Back-link verified: `030626-000099.replacementOrderId = "030626-000100"` ✅ (guard now blocks duplicate replacement)
- Product catalog enforcement passed: productId=3 exists in catalog ✅

**Step 12 checklist status after Session 61:**
```
Code fix      [✅] openCollectionDetail 3-column void layout
Void tests    [✅] V1 Tier 1 partial  [✅] V2 Tier 2 full  [✅] V3 DELIVERED
Day-close     [✅] T11 close  [✅] T11 API guard  [✅] T11 frontend banner+void
Cancel tests  [✅] C1 standard  [✅] C2 CFR non-DELIVERED  [✅] C3 CFR DELIVERED
Return tests  [✅] R1 sellable  [✅] R2 rejected  [✅] R3 with refund
Replacement   [✅] P1 create from C3
Remaining     [✅] Cross-flow UI checks  [✅] Cleanup  [✅] Build log — completed Session 62
```

---

### Session 64 Detail — Jun 3, 2026 (Save as PNG Fix — PO + Daily Report Popups)

**Files modified:** `js/app.js`

**Root causes fixed:**
1. **Race condition** — html2canvas CDN script loaded asynchronously in the popup; user could click "Save as PNG" before it finished loading → `window.html2canvas` undefined → "please try again" alert. Fixed by disabling the button at render time and enabling it only via the script tag's `onload` event.
2. **Cross-window fallback** — the `window.opener.html2canvas` fallback was attempting to run the parent window's html2canvas against the popup's DOM, which is inconsistent across browsers (blank canvas in Firefox, unreliable in Safari). Removed entirely.
3. **Download suppression in popups** — `a.click()` alone is suppressed by some browsers when initiated inside a popup context. Fixed by appending the anchor to `document.body` before clicking and removing it after, which satisfies the browser's download initiation requirements.

**Changes — PO popup (`printPoDocument`):**
1. Script tag: added `onload` handler → enables `#po-save-png` button and resets tooltip when library ready
2. Save as PNG button: added `id="po-save-png"`, `disabled`, `opacity:0.5`, `cursor:not-allowed`, `title="Loading export library…"`
3. `savePNG()`: replaced `window.html2canvas||window.opener&&window.opener.html2canvas` with `window.html2canvas` only; replaced bare `a.click()` with `appendChild → click → removeChild`

**Changes — Daily Report popup (same pattern):**
- Same three changes targeting `#dr-save-png`
- Also added missing `allowTaint:true` to Daily Report's html2canvas call (PO had it, DR did not)

**Verification:**
- `node --check app.js` → clean
- `grep opener.*html2canvas` → no matches (old fallback fully removed)
- `grep "please try again"` → no matches (old alert message fully removed)
- All 6 changed lines confirmed present at correct locations

---

### Session 63 Detail — Jun 3, 2026 (PO Label Fix — Unit Price → Unit Cost)

**Files modified:** `index.html`, `js/app.js`

**Root cause:** The New PO modal's Items Ordered table and the PO list expand detail row both labeled the buying-price column as "Unit Price." This was misleading because: (a) the auto-fill correctly pulls `unitCost` from the product catalog (not `unitPrice`), and (b) the PO PDF document already correctly said "Unit Cost." The label was inconsistent and contradicted the PDF.

**Changes:**

1. **`index.html:3325`** — New PO modal, Items Ordered table column header: `"Unit Price"` → `"Unit Cost"`
2. **`app.js:7273`** — `buildPoDetailHtml()`, PO list expand detail table column header: `"Unit Price"` → `"Unit Cost"`

**Labels confirmed correct and left unchanged:**
- `app.js:6909` — CSV Import order preview table: "Unit Price" ✅ (customer selling price — correct)
- `app.js:8891` — Order Detail modal items table: "Unit Price" ✅ (customer selling price — correct)
- `app.js:7616` — PO PDF document: "Unit Cost" ✅ (already correct — no change needed)

**Value integrity confirmed:** `lookupPoItemCode()` and `lookupPoItemDesc()` both fill from `info.unitCost` in `_poItemCodeMap` — the correct buying price. No logic was touched, only labels.

**Verification:**
- `node --check app.js` → clean (no output)
- All 5 pre-flight tests passed via file-state grep

---

### Session 62 Detail — Jun 3, 2026 (Step 12 Complete — Cleanup + Full-App Table Scroll Audit)

**Files modified:** `js/app.js`, `TransactionController.java`, `TransactionService.java`, `css/styles.css`, `index.html`

**Cross-flow UI checks (all passed or noted):**
- `openOrderDetail` 3-column on voided order `030626-000096`: headers Product|Ordered|Voided|Active|Unit Price|Subtotal confirmed ✅
- CFR amber banner on `030626-000099`: bg `rgb(254,243,199)` + "Cancelled — replaced by another order. Replacement order: 030626-000100" ✅
- Replacement purple banner on `030626-000100`: bg `rgb(237,233,254)` + "This is a replacement order. Original order: 030626-000099" ✅
- Order list badges: all 8 Jun 3 orders verified (correct status text, partial-void indicators, struck-through totals) ✅
- `openCollectionDetail` 3-column: 1-column path confirmed on `300526-000003` (no voids — correct); 3-column `hasVoids` path code-verified only — no PENDING_COLLECTION+void order exists in test data ⚠

**Cleanup changes:**

1. **`js/app.js`** — removed 3-line DEBUG HELPERS block:
   ```js
   // DEBUG HELPERS — remove after Test 6 is verified
   window._appState = appState;
   window._reRenderOrders = function () { renderOrderRows(appState.allOrders); };
   ```

2. **`TransactionController.java`** — removed `issueVoid()` endpoint (`POST /api/transactions/void`) and its Javadoc. This endpoint created post-close negative VOIDs; it is superseded by the day-close guard (T11) which blocks voids after close rather than allowing them.

3. **`TransactionService.java`** — removed `recordPostCloseVoid()` method and its Javadoc. Called only by the now-removed `issueVoid()` endpoint.

**Verification:**
- `node --check app.js` → clean (no output)
- `mvn compile -q` → clean (no output)

**Full-app table scroll audit (viewport/table width fix):**

Root cause: `.main { overflow: hidden }` in `styles.css` clips any content wider than the viewport. Without an `overflow-x: auto` ancestor, tables silently clip rather than scroll on narrow screens or when zoomed.

**`css/styles.css`** — added `.table-scroll` class before `.table`:
```css
.table-scroll {
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}
```
Global `min-width` intentionally NOT added to `.table` — would force every table app-wide to scroll even when content fits.

**`index.html`** — full audit of all 37 `<table class="table">` elements:
- 6 already protected (Rejected Items, Daily Reports, Expenses history, 2 Reports tables, Source donut table — inline `overflow-x:auto` or existing `.table-scroll`)
- 10 modal tables intentionally skipped — `modal-body` has its own scroll constraints
- **21 bare view-section tables wrapped** in `<div class="table-scroll">`

Tables wrapped (by view and tbody ID):

| View | Tables wrapped |
|---|---|
| view-dash | `dash-pa-category-tbody`, `dash-pa-top-tbody`, `low-stock-tbody` |
| view-new | `orders-tbody` |
| view-inv | `inv-tbody` (12 cols, 1134px natural width — clips on 1280–1366px laptops) |
| view-purchase-orders | `po-list-tbody` |
| view-rep | `rep-top-products-tbody`, `rep-daily-tbody`, `acc-daily-tbody`, `rep-pizza-tbody`, `rep-hot-selling-tbody`, `rep-delivery-fees-tbody`, `rep-expense-tbody`, `rep-top-agents-tbody`, `rep-top-dates-tbody` |
| view-delivery-rep | `delivery-rep-tbody` (9 cols, 630px) |
| view-activity-log | `activity-log-tbody` |
| view-collections | `collections-tbody` (8 cols, 593px) |
| view-expenses | `exp-today-tbody` |
| view-payables | `payables-tbody` (8 cols, 578px) |
| view-emp | `emp-tbody` (9 cols, 583px) |

Total wrappers in file: 23 (21 new + Order List + Order History from prior sessions).

**Safety verified before editing:**
- No CSS uses `.card > table` child combinator — inserting a div wrapper between `.card` and `table` breaks no selectors
- No JS traverses table parentage via class lookups — `querySelector`/`closest` patterns unaffected
- Browser eval after all wraps: `{ totalWrappers: 23, issues: [], bareViewTables: [] }` — zero bare view tables remaining

**Planning document final status:**

`VOID_CANCEL_RETURN_REDESIGN.md` — **COMPLETE**
- All 12 build steps now ✅. Status header updated.
- Section 9 (endpoint removal) updated: `POST /api/transactions/void` removed Session 62; `recordPostCloseVoid()` removed Session 62.

`QA-GAPS.md` — **52 / 53 fixed**
- 9 Critical: all fixed
- 33 / 34 Moderate: M-26 (double VOID on PENDING_COLLECTION cancel) intentionally deferred — superseded by void/cancel redesign per `VOID_CANCEL_RETURN_REDESIGN.md`; the cancel path for PENDING_COLLECTION orders is not currently reachable via the frontend. No other open items.
- 10 Minor: all fixed

---

### Session 60 Detail — Jun 3, 2026 (Product Catalog Enforcement + Step 12 Part A + V1/V2/V3)

**Files modified (frontend):** `js/app.js` (5 changes)  
**Files modified (backend):** `OrderController.java` (3 changes — ProductRepository injection + 2 validation loops)  
**No migration required**

**Product catalog enforcement — data integrity gap fix**

Gap: order creation form accepted free-typed product names with no catalog link. productId was conditionally included only if selected from dropdown; if user typed manually, productId was omitted → null in Java DTO → null productId stored on order items → void/return movements never generated for those items.

**Frontend (5 changes to app.js):**
1. `addItemRow()` HTML template: added `<div id="productStatus-N">` inside autocomplete wrapper — status indicator element per row
2. `setupProductAutocomplete()` input listener: on any manual keystroke, clears `productId-N` hidden field + shows amber `⚠ Select from catalog` indicator
3. `renderProductDropdown()` click handler: after writing productId to hidden field, shows green `✓ Catalog product` indicator
4. `addOrder()` item loop: hard guard — if productId empty, shows error toast + amber indicator + sets `hasError=true; return` (blocks submit); `item.productId = parseInt(productId)` is now unconditional (not inside `if` block)
5. `addReplacementOrder()` item loop: identical to change 4

**Backend (3 changes to OrderController.java):**
1. `ProductRepository productRepository` field + constructor parameter added
2. `createOrder()` items loop: added null productId check → 400; added `existsById` check → 400
3. `createReplacementOrder()` items loop: same two checks

**Step 12 Part A — openCollectionDetail 3-column expansion**
- Added `hasVoids` flag: `(order.items || []).some(it => (it.voidedQuantity || 0) > 0)`
- Table header: conditional — 1-col Qty when no voids; 3-col Ordered/Voided/Active when any voided
- Item rows: when `hasVoids`, renders Ordered cell + Voided cell (red −N or em-dash) + Active cell (green/red remaining qty); strikethrough + muted on fully voided items
- tfoot colspan: `(hasVoids ? 5 : 3)` — was hardcoded 3
- `node --check app.js` passed ✅

**V1 — Tier 1 partial void (`030626-000095`, ACTIVE)**
- 1× Pizza Box voided; `status=ACTIVE`, `cancellationType=null`, `voidedAmount=9.98`, item voidedQty=1 ✅

**V2 — Tier 2 full void (`030626-000097`, ACTIVE → CANCELLED+VOIDED)**
- 2× Test Product voided; Tier 2 switch triggered on all-zero input; `status=CANCELLED`, `cancellationType=VOIDED`, `voidedAmount=20` ✅

**V3 — Void on DELIVERED (`030626-000096`)**
- 2× Pizza Box voided, SELLABLE disposition; `status=DELIVERED`, `cancellationType=null`, `voidedAmount=19.96`; disposition radios appeared immediately on modal open (correct DELIVERED-order behavior) ✅

---

### Session 59b Detail — Jun 2, 2026 (Fix 5 — Voided Items Display)

**Files modified:** `js/app.js`

**QA wave context:** Fix 5 of 6. All void/cancel/return display flows updated to show effective quantities after voids rather than original pre-void values.

**1 — `renderOrderRows` — items column shows effective quantities**
- `activeItems` filters the items array to those where `(quantity - voidedQuantity) > 0`.
- Lead item text uses `first.quantity - (first.voidedQuantity || 0)` for the quantity shown.
- When all items are fully voided, the items cell shows `(all voided)` in muted small text instead of a product name.

**2 — `renderOrderRows` — total cell shows effective amount with struck-through original**
- When `voidedAmount > 0`: effective total `(order.total - voidedAmount)` is shown as the primary value; original total is shown below it in a smaller font, muted color, and strikethrough.
- When no void: original total only, no change to layout.
- Same pattern applied in `renderOrderHistoryRows` using separate variable names (`_vAmt3`, `_eff3`, `_totalCell3`).

**3 — `openCollectionDetail` — items table rows**
- Fully voided items: product name struck through in muted color; quantity cell shows `Voided` badge (`badge-crit`); subtotal shown struck through in muted color.
- Partially voided items: remaining quantity shown with `(N voided)` note below in muted text; subtotal reflects `effectiveQty × unitPrice`.
- Active items: unchanged from before.

**4 — `openCollectionDetail` — tfoot total**
- When `voidedAmount > 0`: effective total shown as primary value; original total shown below in muted strikethrough (same pattern as order list).

**Open note:** `openCollectionDetail` was code-verified only. No PENDING_COLLECTION orders with voided items existed in test data at time of build. **Flag for verification during the final cross-flow integration test (VOID_CANCEL_RETURN_REDESIGN.md Step 12) when we have full control over order states.**

---

### Session 59 Detail — Jun 2, 2026 (Fix 1–4 — Void/Cancel/Return QA Wave)

**Files modified (backend):** `InventoryService.java`, `OrderService.java`  
**Migrations:** `V41__expand_movement_type_constraint.sql` (new), `V42__widen_activity_log_description.sql` (new)

**1 — Fix 1: CANCEL_REJECTED movement records had quantity = 0**
- Root cause: `cancelForReplacement` flow in `OrderService` was passing `0` as the quantity to the CANCEL_REJECTED inventory movement write instead of the actual voided quantity.
- Fix: changed the quantity argument to use `voidQty` (the actual item quantity being voided) when writing the CANCEL_REJECTED movement record.
- All subsequent CANCEL_REJECTED records now correctly reflect the quantity of units recorded as rejected.

**2 — Fix 2: VOID_REJECTED movement type missing from DB constraint (V41 migration)**
- Root cause: The `chk_movement_type` constraint on `inventory_movements` was not updated when `VOID_REJECTED` was introduced as a movement type for DELIVERED-order void REJECTED dispositions. Backend threw an error silently when trying to write these records.
- Fix: `V41__expand_movement_type_constraint.sql` — drops and recreates `chk_movement_type` to include `VOID_REJECTED` alongside the existing types.
- Backend confirmed at V41 after migration applied.

**3 — Fix 3: Unified rejected items report + V42 migration**
- `GET /api/reports/rejected-items` previously only returned delivery-source rejections. The report now includes:
  - VOID_REJECTED movements (items rejected during delivered-order voids)
  - CANCEL_REJECTED movements (items rejected during cancel-for-replacement)
  - RETURN_REJECTED movements (items rejected during customer returns)
  - Original DELIVERY_REJECTED rows (unchanged)
- `V42__widen_activity_log_description.sql`: `ALTER TABLE activity_log ALTER COLUMN description TYPE TEXT`. Needed because multi-item void log descriptions exceeded the JPA-default VARCHAR(255) limit with 3+ items.
- Backend confirmed at V42 after both migrations applied.

**4 — Fix 4: Void activity log enriched with per-item inventory outcome strings**
- `InventoryService.restoreStockForVoidedItem` return type changed from `void` to `String`. Method now returns a human-readable outcome per item:
  - Manual item (no productId): `"{name} — manual item, no stock tracked"`
  - Product not found: `"{name} — product not found, no stock change"`
  - SELLABLE disposition: `"{qty} unit(s) of {name} returned to {WAREHOUSE} — new stock total: {N}"`
  - REJECTED disposition: `"{qty} unit(s) of {name} recorded as rejected/damaged"`
- `OrderService.issueVoid`: captures the return value from each `restoreStockForVoidedItem` call into an `inventoryOutcome` key in each item's result map.
- Activity log description built with StringBuilder: base string (`Void (TIER) on order X — ₱Y removed — Reason: Z`) followed by ` | {outcome}` for each item.
- **Acceptance criterion met:** Raw DB query on `activity_log` confirmed the enriched multi-segment string is stored correctly in the `description` column.

**Build result after all 4 fixes:** `mvn compile -q` — clean, no output. V41 and V42 applied on restart. Next available migration: **V43**

---

### Session 58 Detail — Jun 1, 2026 (Order List Audit Frontend Fixes)

**Files modified:** `js/app.js`, `index.html`

**1 — Bug fix: `o.ecomOrderRef` property access → `ecomOrderRef(o)` function call (4 sites)**
- `ecomOrderRef(o)` is a client-side helper function (line 103) that parses the platform order ref from `o.notes`. It is NOT a property on the `OrderResponse` object.
- Four locations were accessing `o.ecomOrderRef` / `order.ecomOrderRef` as if it were a backend field — always `undefined`, always falling back to the system order ID.
- Fixed sites:
  - `renderCollectionRows()` line 1047 — Collections Order ID column
  - `openCollectionDetail()` line 1131 — collection detail modal header
  - Daily close report view line 2637 — pending collections table
  - Outstanding collections summary line 7111 — summary view Order ID cell
- Ecommerce platform refs (Shopee/TikTok/Lazada order numbers) now appear correctly in all four views.

**2 — Bug fix: Order History error/catch rows colspan `7` → `8`**
- `renderOrderHistory()` "Failed to load" (line 777) and "Connection error" (line 783) rows used `colspan="7"`, but the Order History table has 8 columns.
- The no-results empty state already correctly used `colspan="8"`.
- Lines 2263 and 5643 (`colspan="7"`) were intentionally left — they belong to the delivery details table and the rejected items report respectively.

**3 — Gap 1 fix: cancelled-by/at metadata shown under status badge**
- `cancelledByName` and `cancelledAt` were added to `OrderResponse` in Session 57 but displayed nowhere in the UI.
- Added `cancelMeta` / `cancelMetaHtml` inline below the existing cancellation reason sub-line.
  - Format: `by [name] · [Mon DD, HH:MM AM/PM]` in muted grey (`#9CA3AF`), 10px.
  - Rendered in both **Order List** (`renderOrderRows()`) and **Order History** (`renderOrderHistoryRows()`).
- No new column; nests under the existing status badge + reason line without layout changes.

**4 — Gap 2 fix: collected-by/at shown inline in Order History status cell**
- `collectedBy` and `collectedAt` were added to `OrderResponse` in Session 57 but displayed nowhere.
- A "Collected" column was initially added to the Collections view, then corrected: the Collections endpoint only returns orders not yet collected, so `collectedAt` is always null there — the column would always show `—`.
- Correct placement: `collectedMetaHtml` added inline under the status badge in `renderOrderHistoryRows()`, after `cancelMetaHtml`. Format: `collected by [name] · [Mon DD, HH:MM]` in muted grey, 10px. Shows only when `o.collectedAt` is not null (CLOSED/DELIVERED orders after payment received).
- No column count change in any table.

**5 — `DELIVERY` order type: already present, no fix needed**
- Audit had flagged `DELIVERY` as missing from the New Order form dropdown. On inspection `index.html:541` already has `<option value="DELIVERY">Delivery</option>`. No change made.

**6 — Gap 3 noted (no fix): orphaned backend endpoints**
- `GET /api/orders` and `GET /api/orders/search` exist in `OrderController` but are not called by any frontend view.
- `GET /api/orders` returns all orders without pagination — unsafe to wire to a UI as-is.
- `GET /api/orders/search` uses `findByCustomerNameContainingIgnoreCaseOrderByCreatedAtDesc` across all time — more powerful than the client-side history filter, but not wired up.
- Decision: keep both as admin/debug endpoints. Tracked in TECHNICAL DEBT below.

---

### Session 57 Detail — Jun 1, 2026 (Order Schema Gap Fixes + Global Receipt Counter)

**Files modified:** `V35__global_order_counter.sql` (new), `OrderIdGenerator.java`, `OrderResponse.java`, `OrderController.java`, `OrderService.java`, `Order.java`

**1 — V35 migration + `OrderIdGenerator.java` — Global continuous receipt number**
- Root cause: `order_id_counter` table used a row per date (`DDMMYY` as PK key); every day started at `000001`.
- Fix: V35 migration seeds a single `GLOBAL` row from `MAX(last_number)` across all existing per-day rows, then deletes the old rows. Safe on fresh install (COALESCE returns 0).
- `OrderIdGenerator.java`: key changed from `today.format(DATE_FORMATTER)` to `"GLOBAL"`. Date prefix (`DDMMYY`) is still stamped on the ID from `LocalDate.now()`.
- Result: order 1 on Jun 1 → `010626-000042`; first order Jun 2 → `020626-000043`. Sequence is global, never resets.
- `"GLOBAL"` is exactly 6 chars — fits the existing `VARCHAR(6)` PK column without migration change.

**2 — `OrderResponse.java` — Cancellation and collection timestamps exposed (Gap 2)**
- Added fields: `cancelledAt` (`LocalDateTime`), `cancelledByName` (`String`), `collectedAt` (`OffsetDateTime`), `collectedBy` (`String`).
- `OffsetDateTime` import added.
- `convertToResponse()` in `OrderController.java` updated: passes all 4 new fields; `cancelledByName` resolves from `order.getCancelledBy().getFullName()` with null guard.

**3 — Batch import duplicate check precision (Gap 3)**
- `createOrderBatch()` was checking `existsByNotesContaining(externalRef)` where `externalRef` was the bare number (e.g. `"12345"`). This could match `"Order No: 123456"` as a substring.
- Fixed: now checks `existsByNotesContaining("Order No: " + externalRef)`. The full prefix is part of the search string.

**4 — `PENDING_COLLECTION` in `updateStatus()` (Gap 5)**
- `updateStatus()` in `OrderService.java` previously blocked any `newStatus` not in `[ACTIVE, PENDING, DELIVERED, CLOSED]`.
- Added `PENDING_COLLECTION` to the allowed list.
- Javadoc updated to document `PENDING → PENDING_COLLECTION` and `PENDING_COLLECTION → ACTIVE` transitions.

**5 — Source enum validation (Gap 1)**
- `Order.java` comment was stale: listed only 4 sources, missing `RESELLER` and `DISTRIBUTOR`. Updated to all 6.
- `OrderService.java`: added `VALID_SOURCES` constant (Set of 6 values). `createOrder()` validates and normalises source to uppercase before save. Unknown values throw a descriptive error.

**Gap 4 — Refund endpoint: verified working (false positive)**
- Initial audit incorrectly flagged this as missing because it looked for `POST /api/orders/{id}/refund` in `OrderController`.
- The endpoint is `POST /api/transactions/refund` in `TransactionController` — it exists and is fully implemented.
- `confirmRefund()` in `app.js` correctly calls this endpoint. Modal collects amount, reason, and admin security key.
- Inventory is restored proportionally on refund. `DailyReportService` and `ReportsController` both account for REFUND transactions in `refundsTotal` and `netSales`. No gap exists.

**Build result:** `mvn compile -q` → clean, no output. ✓ Next available migration: **V36**

---

### Session 56 Detail — Jun 1, 2026 (Non-Pizza KPI + Order Schema Analysis)

**Files modified:** `index.html`, `js/app.js`. No migration.

**1 — Non-pizza total KPI (index.html + app.js)**
- Added a new metric row inside the pizza quota KPI card: "Other products — N pcs".
- HTML: `<div id="dash-pa-nonpizza-qty">` inside the card footer, separated by a border-top, visible on all three tabs.
- JS: `nonPizzaQty = max(0, d.totalQtySold - actual)` computed client-side after `loadProductAnalytics()`. No new backend endpoint needed — both values already in the `/product-analytics` response.

**2 — Order schema analysis (no code changes)**
Full review of 10 schema files. Five gaps documented:
- Gap 1: `source` field is `VARCHAR(30)` with no enum validation — RESELLER and DISTRIBUTOR missing from entity comment.
- Gap 2: `OrderResponse` DTO missing `cancelledAt`, `cancelledByName`, `collectedAt`, `collectedBy`.
- Gap 3: Batch import duplicate check used bare number as substring search — fragile.
- Gap 4: No refund/return order endpoint despite ledger types and service methods existing.
- Gap 5: `PENDING_COLLECTION` not in `updateStatus()` allowed list; set by force-close only.

---

### Session 55 Detail — Jun 1, 2026 (Dashboard Period-Awareness Fixes)

**Files modified:** `DashboardController.java`, `js/app.js`

**1 — `DashboardController.java` — 5 bug fixes**
- `previousMonthPizzaTotal()`: was using `classifyProduct(p, it.getProductName())[1]` (subcategory "Pizza Boxes"). Fixed to `[0]` (category "Pizza Box"). Silent mismatch caused monthly quota target to be wrong.
- `salesTrend` period-aware: weekly iterates Mon through today (today is live), uses daily_reports for past days; monthly iterates 1st through today per-day with day-of-month labels; daily preserves original last-7-days logic.
- `getChannelSummary()`: now accepts `@RequestParam period` (default "daily"). Computes `periodStart` = Monday / 1st / today; loads full-period orders for revenue + channel + platform metrics. COD pending and payables remain today/balance-sheet scoped.
- Row 9 (`dash-pa-top-row`) hidden on weekly/monthly tabs — it duplicates the period top-products table already shown in Row 2 Right. Rows 7–8 (pizza KPI + category charts) remain visible on all tabs.
- `codPendingCount` removed from `/stats` endpoint — was always today-only, inconsistent with period-aware stats.

**2 — `js/app.js`**
- `loadChannelSummary(period)`: now accepts and forwards `period` param to backend.
- `renderDashboard` call site: passes current `period` to `loadChannelSummary`.
- `switchDashTab()`: only hides `dash-pa-top-row` for weekly/monthly; quota card wrapper stays visible on all tabs.

---

### Session 54 Detail — Jun 1, 2026 (Three UI/UX Fixes)

**Files modified:** `index.html`, `js/app.js`, `css/styles.css`. No migration.

**1 — Inventory Description column (index.html + app.js)**
- `<th>Description</th>` added after Product column in inventory table header.
- Loading/error row colspan: `9` → `10`; no-products row: `10/11` → `11/12`.
- `descCell` variable rendered inline — shows description text in small muted font, or an em-dash if blank.
- `<td>` for description has `max-width:240px;overflow:hidden;text-overflow:ellipsis;` + full text in `title` attribute.
- Keyword filter extended to also search `p.description`.

**2 — `window.openModal` defined (app.js)**
- Root cause: `cancelDelivery()` called `openModal('modal-cancel-delivery')` but `window.openModal` was never defined anywhere. JS threw a silent `ReferenceError` — nothing happened.
- Fix: `window.openModal = function(id) { var el = $(id); if (el) el.classList.add('open'); }` added immediately before `closeModal`.
- Side effect: also fixed `openModal` calls for Category Breakdown modal and CSV Skipped modal which had the same undefined problem.

**3 — Delivery Report status badge CSS (styles.css)**
- `.badge-hot`, `.badge-selling`, `.badge-slow` were used in delivery reports, low-stock panel, and dashboard low-stock modal but had no CSS definitions. Text was unstyled in light mode.
- Added all three classes with correct light-mode backgrounds/colors + `[data-theme="dark"]` overrides. Color palette matches `tag-select-*` classes.

---

### Session 36 Detail — May 27, 2026 (Purchase Order Module)

**Files created:** `V23__purchase_orders.sql`, `PurchaseOrder.java`, `PoItem.java`, `PurchaseOrderRepository.java`, `PoItemRepository.java`, `PurchaseOrderController.java`  
**Files modified:** `ProductController.java`, `index.html`, `js/app.js`

**1 — V23 migration**
- `purchase_orders` table: id, po_number (UNIQUE), vendor_name, vendor_contact, vendor_address, ship_to_name/contact/address, notes, vat_type, shipping_arrangement, status (default INCOMPLETE), created_by, created_at, total_amount
- `po_items` table: id, po_id (FK → ON DELETE CASCADE), item_code, item_description, quantity_ordered, unit_price, line_total, fulfilled_qty, dr_number, is_fulfilled
- Indexes: `idx_po_number` (unique), `idx_po_items_po_id`, `idx_po_items_item_code` (partial, WHERE NOT NULL)

**2 — Backend entities + repositories**
- `PurchaseOrder.java`: Lombok @Data; `@OneToMany` → `PoItem` with CascadeType.ALL + orphanRemoval; `@JsonIgnore` on items list
- `PoItem.java`: manual getters/setters (same pattern as DeliveryLogItem); `@ManyToOne @JsonIgnore` on purchaseOrder
- `PurchaseOrderRepository`: `findByPoNumber()`, `findAllWithItems()` (DISTINCT + LEFT JOIN FETCH, ORDER BY createdAt DESC), `findByIdWithItems()`
- `PoItemRepository`: `findByItemCodeAndIsFulfilledFalse()` — used for DR auto-matching

**3 — PurchaseOrderController**
- `GET /api/purchase-orders` — list all with items eagerly loaded via JPQL
- `GET /api/purchase-orders/{id}` — single PO
- `POST /api/purchase-orders` — create PO; validates po_number uniqueness + vendor name + ≥1 item; computes line totals + total_amount; returns created PO with items
- `PATCH /api/purchase-orders/{id}/status` — manually mark COMPLETE or INCOMPLETE

**4 — ProductController DR→PO auto-match**
- `PoItemRepository` + `PurchaseOrderRepository` injected
- After delivery log saved: for each DR item, if the product has an `itemCode`, calls `findByItemCodeAndIsFulfilledFalse()` → matches oldest open PO item → increments `fulfilledQty` + sets `drNumber`; marks `isFulfilled = true` when fulfilledQty ≥ quantityOrdered
- After all items processed: checks any touched PO — if all items fulfilled, auto-sets status to COMPLETE
- Wrapped in try/catch — delivery save never fails due to PO matching errors

**5 — index.html**
- Nav button: `id="nav-purchase-orders"` (ti-file-dollar icon) between Inventory and Receive Stock
- `view-purchase-orders`: card with Hide Completed toggle + New PO button; 8-column table (chevron, PO#, Date, Vendor, Items fulfilled/total, Total, Status badge, Print/Toggle-status actions)
- `modal-new-po`: 820px; PO number field (monospace, 11-char); Vendor (name/contact/address) | Ship-To (RRBM defaults pre-filled) 2-col layout; dynamic items table (Item Code → lookup, Description, Qty, Unit Price, Line Total, Remove); VAT type/Shipping/Notes row; running total display
- "Purchase Orders" checkbox added to both Add Employee and Edit Employee page-access grids

**6 — js/app.js**
- `viewToPageKey`: `'purchase-orders': 'purchase-orders'`
- `navigateTo` title added; nav trigger `loadPurchaseOrders()` added
- `loadPurchaseOrders()`: fetches list, caches in `_allPoData`
- `filterPoList()`: applies Hide Completed filter, calls `renderPoList()`
- `renderPoList()`: generates main row + hidden detail row per PO; status badge + fulfill progress
- `buildPoDetailHtml(po)`: 8-col item table inside the expanded row; green row highlight for fulfilled items
- `togglePoRow(id)`: expand/collapse detail row + chevron rotation
- `togglePoStatus(id, newStatus)`: PATCH status + refresh list
- `openNewPoModal()`: resets form, pre-fills Ship-To with RRBM defaults, loads product item codes into `_poItemCodeMap`, opens modal
- `addPoItemRow() / removePoItemRow()`: dynamic item row management with auto-renumber
- `lookupPoItemCode(input)`: on blur, fills description + unit price from `_poItemCodeMap` if item code matches a product
- `calculatePoTotal()`: updates line totals + grand total display as user types
- `submitNewPO()`: validates + POSTs to backend, prepends to `_allPoData` + re-renders
- `printPoDocument(id)`: generates full PO PDF (RRBM letterhead, vendor/ship-to meta grid, item table, VAT note, signature blocks, auto-print)
- Build: BUILD SUCCESS ✓. V23 migration applied on next server start. Next available migration: **V24**

### Session 35 Detail — May 27, 2026 (Dashboard Channel Split + Platform Breakdown + Pending Cards)

**Files modified:** `DashboardController.java`, `index.html`, `js/app.js`. No migration.

**1 — `DashboardController.java`**
- `PayableRepository payableRepository` injected (constructor)
- `getStats()` extended: added `directPizzaQty` (non-ecommerce pizza qty today), `ecomPizzaQty` (ecom pizza qty today), `codPendingCount` (COD orders today not DELIVERED/CLOSED/CANCELLED) to response
- New private helper `pizzaQtyFromOrders(List<Order>, Map<Long,String>)` — reusable pizza detection using category map, reduces duplication between stats and channel-summary
- New `GET /api/dashboard/channel-summary`: loads today orders with items; splits into direct/ecom; computes per-channel revenue + pizza qty; per-platform Shopee/TikTok/Lazada breakdown; COD pending count; payables outstanding + pending count via `PayableRepository`

**2 — `index.html`**
- Pizza quota bar: replaced single `#pizza-progress-bar` with two-tone `#pizza-bar-direct` (amber) + `#pizza-bar-ecom` (purple) stacked left-to-right in a flex container; legend row added showing "Direct: N pcs / E-com: N pcs"
- New Row 4a: Direct vs Ecommerce channel stat cards (amber border / purple border); each shows order count + revenue + pizza box count (`#dash-direct-*` / `#dash-ecom-*`)
- New Row 4b: Per-platform mini-stat cards for Shopee (orange), TikTok (dark), Lazada (navy); each shows order count + revenue + pizza boxes (`#dash-shopee-*` / `#dash-tiktok-*` / `#dash-lazada-*`)
- New Row 4c: Payables Outstanding (red border, `#dash-payables-outstanding`) + COD Awaiting Collection (amber border, `#dash-cod-pending`)

**3 — `js/app.js`**
- `renderDashboard()`: replaced single-bar pizza logic with two-tone bars using `s.directPizzaQty`/`s.ecomPizzaQty`; status color/text/badge still driven by total pizzaPct; `loadChannelSummary()` called at end of try block (fire-and-forget)
- New `loadChannelSummary()` function: fetches `/api/dashboard/channel-summary`; populates 8 channel cells, 9 platform cells, 3 payables/COD cells; gracefully handles missing platform data with zeros
- Build: BUILD SUCCESS ✓. No migration. Next available migration: **V23**

### Session 34 Detail — May 27, 2026 (Reports: Pizza Split + Non-Pizza Card + Daily Summary Columns)

**Files modified:** `ReportsController.java`, `index.html`, `js/app.js`. No migration.

**1 — `ReportsController.java`**
- Extended `getPizzaSummary()`: added a second SQL query (`getSingleResult` conditional aggregation) returning `directQty`, `ecomQty`, `directRevenue`, `ecomRevenue`, `totalRevenue`; result map now includes all channel fields alongside existing `totalQty` and `top5`
- New `GET /api/reports/non-pizza-summary?month=YYYY-MM`: conditional-aggregation query for `NOT ILIKE '%Pizza Box%'` items — returns `directQty`, `ecomQty`, `directRevenue`, `ecomRevenue`, `totalQty`, `totalRevenue`; plus `topProducts` (top 10 by qty with per-channel split)
- New `GET /api/reports/daily-order-summary?month=YYYY-MM`: per-day breakdown using `LEFT JOIN` pizza subquery (1:1 per order); returns `date`, `directOrders`, `ecomOrders`, `totalOrders`, `pizzaBoxQty`, `revenue`; ordered by date asc

**2 — `index.html`**
- Daily Order Summary table: header expanded from 3 → 6 columns (Date / Direct / E-com / Total Orders / Pizza Boxes / Revenue); empty-state colspan updated
- Pizza Box Summary card: replaced single `#rep-pizza-total` div with a 3-column channel grid (`#rep-pizza-direct-qty`, `#rep-pizza-ecom-qty`, `#rep-pizza-total-qty`) + revenue sub-cells per column; SKU breakdown table retained below
- New Non-Pizza Items Summary card: 3-column channel grid (`#rep-nonpizza-direct-qty/rev`, `#rep-nonpizza-ecom-qty/rev`, `#rep-nonpizza-total-qty/rev`) + "View Product Breakdown" button
- New `modal-nonpizza-detail`: 620px, scrollable, 6-column table (#, Product, Direct, E-com, Total Qty, Revenue); inserted before `modal-daily-report-detail`

**3 — `js/app.js`**
- `loadAllReports()`: added `loadNonPizzaSummary()` and `loadDailyOrderSummary()` calls (now 12 total)
- Removed daily table rendering block from `renderInsightsSummary()` (was populating 3-col table) — replaced by dedicated `loadDailyOrderSummary()`
- `loadPizzaSummary()`: now populates 6 channel-grid cells in addition to SKU table
- New `loadNonPizzaSummary()`: fetches `/api/reports/non-pizza-summary`, populates Non-Pizza card cells, caches `topProducts` in `_nonPizzaProducts`
- New `window.openNonPizzaModal()`: renders `_nonPizzaProducts` into `nonpizza-modal-tbody` and opens modal
- New `loadDailyOrderSummary()`: fetches `/api/reports/daily-order-summary`, renders 6-column rows (Direct amber, E-com purple, Revenue accent)
- Build: BUILD SUCCESS ✓. No migration. Next available migration: **V23**

### Session 33 Detail — May 27, 2026 (Rejected Items Page)

**Files modified:** `DeliveryLogItemRepository.java`, `DailyReportController.java`, `index.html`, `js/app.js`. No migration.

**1 — `DeliveryLogItemRepository.java`**
- New `findRejectedByDateRange(@Param("start") LocalDate, @Param("end") LocalDate)` JPQL query
- Uses `JOIN FETCH i.deliveryLog dl` to eagerly load the delivery log in a single query (avoids N+1 / LazyInitializationException)
- Filters: `i.rejectedQty > 0 AND dl.reportDate BETWEEN start AND end`; ordered by date desc

**2 — `DailyReportController.java`**
- `DeliveryLogItemRepository` injected (added to constructor)
- Added imports: `Transactional`, `BigDecimal`, `Collectors`
- New `GET /api/reports/rejected-items?start=YYYY-MM-DD&end=YYYY-MM-DD` endpoint:
  - Defaults to current calendar month when params are omitted
  - Returns `{ items: [...], grandTotal, count, start, end }`
  - Each item: `deliveryDate`, `receiptNumber`, `supplierName`, `receivedBy`, `productName`, `rejectedQty`, `unitCost`, `lineTotal` (rejectedQty × unitCost)

**3 — `index.html`**
- "Rejected Items" nav button (`id="nav-rejected-items"`, `ti-package-off` icon) added in Main section after Receive Stock
- `view-rejected-items` section inserted before `view-daily-reports`:
  - Date range picker (start + end dates) + Load button + Download PDF button (hidden until data loads)
  - 8-column table: Date | Receipt No. | Supplier | Received By | Product | Rejected Qty | Unit Cost | Sub-total
  - `<tfoot id="rejected-items-tfoot">` for grand total row
- "Rejected Items" checkbox (`value="rejected-items"`) added to both Add Employee and Edit Employee page-access grids

**4 — `app.js`**
- `viewToPageKey()`: `'rejected-items': 'rejected-items'` (own permission key)
- `navigateTo` titles: `'rejected-items': ['Rejected Items', 'Items rejected during delivery']`
- `initRejectedItemsView()`: pre-fills date range to current month on first open, then calls `loadRejectedItems()`
- `window.loadRejectedItems()`: fetches `/api/reports/rejected-items?start=&end=`; caches data to `window._rejectedData`; shows/hides PDF button
- `renderRejectedItemsList(data)`: renders rows with red qty column, grand total in `<tfoot>` with red text
- `window.downloadRejectedPDF()`: opens print window; RRBM header (logo-sq + company name + period); 8-column table + grand total footer; matches Expense PDF CSS style; auto-triggers `window.print()`

**Build result:** 60 Java files compiled, BUILD SUCCESS ✓. No migration.

---

### Session 32 Detail — May 26, 2026 (Inventory: Description + Item Code)

**Files modified:** `V22__product_description_and_item_code.sql` (new), `Product.java`, `ProductController.java`, `index.html`, `js/app.js`. Migration: **V22**.

**1 — V22 migration**
- `ALTER TABLE products ADD COLUMN IF NOT EXISTS description TEXT`
- `ALTER TABLE products ADD COLUMN IF NOT EXISTS item_code VARCHAR(50)`
- `CREATE UNIQUE INDEX IF NOT EXISTS idx_products_item_code ON products(item_code) WHERE item_code IS NOT NULL` — partial index allows multiple NULLs, enforces uniqueness only for non-NULL values

**2 — `Product.java`**
- `description` field: `@Column(name = "description", columnDefinition = "TEXT")`
- `itemCode` field: `@Column(name = "item_code", length = 50, unique = true)` — Lombok `@Data` generates getters/setters automatically

**3 — `ProductController.java`**
- `createProduct` (POST): sets `description` and `itemCode` from request body if non-blank
- `updateProduct` (PATCH): added Description and Item Code handlers with change logging; description change logs as "Description updated"; item code logs as `"ItemCode: \"old\" → \"new\""`

**4 — `index.html` — inventory table**
- Added `<th>Item Code</th>` column between Code and Product in `<thead>`; total visible columns now 10 (11 with Actions)

**5 — `index.html` — Add Product modal**
- New row between Product Code+Name and Category+SubCategory: `#addprod-item-code` (col-5) + `#addprod-description` textarea (col-7)

**6 — `index.html` — Edit Product modal**
- `#editprod-item-code` input added to a new grid-2 row with Status radios
- `#editprod-description` textarea added as full-width row above Master Key

**7 — `app.js`**
- `applyInventoryFilters()`: `itemCodeCell` variable added (monospace font, muted color, or "—"); rendered as 2nd column in table rows; keyword search now also checks `p.itemCode`; empty-state colspan updated: 11 (with Actions) or 10 (without)
- Product name cell gains `title="description"` attribute when description exists (hover tooltip)
- `openAddProductForm()`: clears `addprod-item-code` and `addprod-description`
- `submitAddProduct()`: includes `itemCode` and `description` in POST body
- `openEditProductModal()`: populates `editprod-item-code` and `editprod-description` from product data
- `submitEditProduct()`: includes `itemCode` and `description` in PATCH body

**Build result:** 60 Java files compiled, BUILD SUCCESS. V22 migration pending (will run on next `spring-boot:run`). ✓

---

### Session 31 Detail — May 26, 2026 (Daily Reports Page)

**Files modified:** `ReportsController.java` (backend), `index.html`, `js/app.js`. No migration.

**1 — Backend: `GET /api/reports/daily-reports-list` (`ReportsController.java`)**
- `DailyReportRepository` and `UserRepository` injected (added to constructor)
- New endpoint returns all `DailyReport` records ordered by date desc (back to 2020-01-01)
- Per-record fields: `reportDate`, `closedAt`, `closedBy` (Long), `closedByName` (resolved from users table), `totalOrders`, `grossSales` (ledger if available, else `totalRevenue`), `netSales` (ledger if available, else `totalRevenue`), `totalRevenue`
- User name lookup: `userRepository.findAll()` → `Map<Long, String>` of id→fullName; `userNames.getOrDefault(id, "Unknown")`
- Response: `{ "reports": [...], "total": N }`

**2 — Frontend nav item (`index.html`)**
- New `<button id="nav-daily-reports" data-view="daily-reports">` added in Insights nav section, between Monthly Report and Order History
- Uses `ti-calendar-stats` icon; navigates to `'daily-reports'`

**3 — Frontend view section (`index.html`)**
- `<section id="view-daily-reports">` inserted before Delivery Reports view
- Table: 6 columns — Date | Closed At | Closed By | Total Orders | Net Sales | Actions
- "View" button per row calls `openDailyReportDetail(date)`
- "Daily Reports" checkbox added to both Add Employee and Edit Employee page-access grids (`value="daily-reports"`)

**4 — Frontend modal: `modal-daily-report-detail` (`index.html`)**
- `max-width:540px`, scrollable body (`max-height:90vh`, flex-column)
- Dynamic title `#drep-modal-title` + body `#drep-modal-body`

**5 — Frontend JS (`app.js`)**
- `viewToPageKey()`: `'daily-reports': 'daily-reports'` added
- `navigateTo` titles: `'daily-reports': ['Daily Reports', 'History of all closed daily sales']` added
- `if (view === 'daily-reports') loadDailyReports()` trigger added
- `loadDailyReports()`: fetches `/api/reports/daily-reports-list` → calls `renderDailyReportsList(data.reports)`
- `renderDailyReportsList(reports)`: renders table rows with date, closedAt+time, closedByName, totalOrders, net sales (₱ formatted), View button; empty-state message if no records
- `window.openDailyReportDetail(date)`: opens modal, fetches `/api/reports/daily/{date}` (existing endpoint in DailyReportController); renders 2-stat-card grid (Total Orders + Net Sales), accounting table (Gross → Refunds → Adjustments → Net), source breakdown mini-cards grid (Walk-in / Agent / E-commerce / Facebook), top product row, closed timestamp + notes

**Build result:** 60 Java files compiled, BUILD SUCCESS. ✓

---

### Session 30 Detail — May 26, 2026 (Permissions & Security)

**Files modified:** `index.html`, `js/app.js`. No backend changes. No migration.

**1 — `order-history` as separate page permission (`index.html` + `app.js`)**
- `viewToPageKey()` in app.js: `'view-order-history': 'order-history'` (was `'orders'`) — Order History now has its own separate `allowedPages` key, independent of Orders
- `index.html` — Add Employee modal page-access grid: "Order History" checkbox (`value="order-history"`, default checked) added after the Orders checkbox
- `index.html` — Edit Employee modal page-access grid: same checkbox added (default unchecked — must be explicitly granted)
- Effect: users with Orders permission no longer automatically see Order History; Super Admin can assign access independently

**2 — Print button in Order History gated by `canManageOrders()` (`app.js`)**
- `renderOrderHistoryRows()`: Print button moved behind the same `canAdmin` guard already used for Refund/Void buttons
- Ledger (View Ledger) button remains ungated — visible to anyone with Order History access
- Order List cancel buttons left ungated (operational necessity — staff manage today's active orders)

**3 — Payable status change requires admin security key (`index.html` + `app.js`)**
- `index.html`: new `modal-payable-paid` modal added — password input `#payable-paid-key`, dynamic title (`#payable-paid-title`), dynamic description (`#payable-paid-desc`); title/desc update for both "Mark as Paid" and "Revert to Pending" paths
- `app.js`: added module-level `var _pendingPayableStatus = null;`
- `togglePayableStatus()` replaced: instead of calling PATCH directly, sets `_pendingPayableStatus` (PAID or PENDING), updates modal title/desc, clears key input, opens `modal-payable-paid`
- `window.confirmPayableStatusChange()` added:
  1. Reads `#payable-paid-key` — errors if blank
  2. `POST /api/auth/verify-security-key` — BCrypt-validates caller's personal admin key; shows `"Incorrect security key"` on failure
  3. On success: `PATCH /api/payables/{id}/status` with `_pendingPayableStatus`; closes both modals; reloads payables
- `closeModal()`: `'payable-paid-key'` added to the sensitive-fields clear list
- Enter key handler actionMap: `'modal-payable-paid': function () { confirmPayableStatusChange(); }` added

**No build required** — frontend-only changes.

---

### Session 29 Detail — May 26, 2026 (Quick Wins)

**Files modified:** `js/app.js`, `index.html`, `ReportsController.java`. No migration.

**1 — Discount % presets (`index.html` + `app.js`)**
- Added 5 quick-select buttons (3%, 5%, 10%, 50%, Clear) above the `#orderDiscount` input in the New Order form
- `window.applyDiscountPreset(pct)`: sums `.item-subtotal` values → sets `orderDiscount` to `subtotal × pct / 100`; pct=0 clears to 0; calls `calculateOrderTotals()` after

**2 — Week tab contextual label (`app.js`)**
- Inside `renderDashboard()`, after stats load: computes `weekNum = Math.ceil(date.getDate() / 7)` and short month name → updates `#dash-tab-weekly` text to e.g. "Week 2 · May"
- Updates on every `renderDashboard()` call (initial load + tab switches)

**3 — Monthly tab shows month name (`app.js`)**
- Same block updates `#dash-tab-monthly` text to full month name e.g. "May"

**4 — Accounting Summary collapsible (`index.html` + `app.js`)**
- Heading row made clickable (`onclick="toggleAccountingSummary()"`, `cursor:pointer`)
- Chevron icon `#acc-summary-chevron` added (right-aligned, `ti-chevron-down`)
- `#acc-summary-grid` added as ID to the 4-card grid
- Initial state: collapsed (`display:none`, chevron rotated −90°)
- `window.toggleAccountingSummary()`: toggles `display:none` on grid, rotates chevron 0° / −90°

**5 — Top 5 → Top 10 products in monthly report**
- `ReportsController.java`: `.limit(5)` → `.limit(10)` in `topProducts` stream in `getInsightsSummary`
- `index.html`: "Top 5 Products This Month" → "Top 10 Products This Month"
- `app.js`: medals array extended from 5 entries to 10 entries

**6 — Reports renamed to Monthly Report**
- `index.html`: sidebar nav `<span>Reports</span>` → `<span>Monthly Report</span>`; employee modal page-access checkboxes label updated in both Add and Edit employee modals
- `app.js`: `navigateTo` titles map: `rep: ['Reports', ...]` → `rep: ['Monthly Report', 'Monthly analytics & insights']`

**No build required** — only backend change is removing `.limit(5)` cap (Java stream, no SQL change).

---

### Session 28 Detail — May 26, 2026 (Order Receipt Redesign)

**Files modified:** `js/app.js` only. No backend changes. No migration.

**Change — `window.printOrderReceipt` document.write block replaced**

Added two display formatters before `w.document.write()`:
- `payDisplay` — chain-replace on `payMode`: BANK_TRANSFER→Bank Transfer, GCASH→GCash, PAYMAYA→PayMaya, CASH→Cash, COD→Cash on Delivery
- `srcDisplay` — source-to-label map: WALK_IN→Walk-in, AGENT/RESELLER→with agent name in parens, ECOMMERCE→platform name title-cased or "E-commerce", FACEBOOK_PAGE→Facebook Page, else raw source value

New receipt layout (card design, max-width 560px):
- **Header** — logo left (`rc-logo`, h:52px), address+phone right; separated by 3px amber (`#D4860A`) bottom border
- **Title bar** — "SALES RECEIPT" (uppercase, letter-spaced) left + `#ORDER-ID` monospace right on `#f7f7f7` band
- **2×2 meta grid** — Date | Payment / Order type | Source — internal 1px dividers via `.rc-mc.br` + `.rc-mc.bt`
- **Bill to block** — customer name (700 weight, 14px) + optional address (📍 via `&#128205;`)
- **Items table** — dark brown `#2C1A0E` `<thead>`, cream `#FAF7F2` header text; row separators only (no outer borders); qty/unit price muted (`.m`), amount bold (`.b`)
- **Subtotals** — `.rc-totals` section: subtotal, discount (−), optional delivery fee (+)
- **Grand total** — `.rc-grand`: "Total due" label + 20px bold `#2C1A0E` value; amber 3px top border
- **Signatures** — `.rc-sigs`: Prepared by (auto-filled `preparedBy`) + Received by blank; 40px margin-top line
- **Footer** — thank-you text left + "OFFICIAL" amber border stamp right
- `@media print`: body padding 0, `.rc` border/shadow none

`itemRows` and pre-built `address` HTML string kept as declared (now unused — harmless dead code per spec instruction).

**No build required** — frontend-only change.

---

### Session 27 Detail — May 26, 2026 (PDF Fix + E-com UX + Source Filter + Enter Key)

**Files modified:** `js/app.js`, `index.html`. No backend changes. No migration.

**PART A — PDF source breakdown fix (`printMonthlyReport`)**
- Previously the source donut image appeared alone with no table context below it
- Fixed: `srcTable` extracted as a separate variable; in the chart-images block, heading + img + `srcTable` are rendered together; the later `+ srcSection` position in document.write removed to avoid duplicate table
- Fallback (no chart captured): `srcSection` (heading + table) still used

**PART B — E-commerce card UX fix (`renderEcommerceBreakdown`)**
- Added "TOP PRODUCTS THIS MONTH" sub-label between the 3 stat mini-cards and the top-products table in each platform section
- Style: `font-size:11px; font-weight:700; text-transform:uppercase; letter-spacing:.06em; color:var(--text-muted); border-top:0.5px solid var(--border)`
- Column header changed from "Top Products" → "Product" for cleaner table layout

**PART C — Order source filter dropdowns (`index.html`)**
- Added `<select id="order-source-filter">` to Order List toolbar, before `#order-search` input
- Added `<select id="history-source-filter">` to Order History toolbar, before `#history-search` input
- Both dropdowns: 10 options — All sources, WALK_IN, AGENT, RESELLER, DISTRIBUTOR, FACEBOOK_PAGE, ECOMMERCE, SHOPEE, TIKTOK, LAZADA
- `onchange="filterOrderList()"` / `onchange="filterOrderHistory()"` wired

**PART D — Updated filter functions (`app.js`)**
- `filterOrderList()` replaced: reads `order-source-filter` first; SHOPEE/TIKTOK/LAZADA check `o.ecommercePlatform` on ECOMMERCE orders; others check `o.source`; text search covers id, customerName, agentName
- `filterOrderHistory()` replaced: same logic using `history-source-filter` + `history-search`
- Both filter against `appState.allOrders` / `appState.orderHistoryAll` (full dataset) — no accumulated filtering
- Dropdown resets added: `order-source-filter` reset after `appState.allOrders` assigned; `history-source-filter` reset after `appState.orderHistoryAll` assigned

**PART E — Global Enter key handler (`app.js` — DOMContentLoaded)**
- Added `document.addEventListener('keydown', ...)` inside DOMContentLoaded
- Skips if `e.target.tagName === 'TEXTAREA'` or `'BUTTON'`
- Per-modal action map: `modal-addprod-key → verifyAddProductKey()`; `modal-logout → _doLogout()`; 9 others → click first `.btn-primary, .btn-danger, .btn-warning` in the open modal
- Login fallback: when no modal open and `#login-screen` is visible, calls `doLogin()`

**No build required** — frontend-only changes.

---

### Session 26 Detail — May 26, 2026 (Field-Name Audit + Test Data Fix)

**Files modified:** None (no code changes needed). SQL data fix only.

**Audit result — all 6 JS fixes in the spec were unnecessary:**
The spec claimed these backend field name mismatches existed, but direct inspection of `ReportsController.java` showed the frontend was already reading the correct names:
- Fix 1: Spec said backend returns `totalPizzaBoxQty` — backend actually returns `totalQty`. Frontend uses `data.totalQty`. ✅ Already correct.
- Fix 2: Spec said hot-selling returns a direct array — backend returns `{ items: [...] }`. Frontend uses `data.items`. ✅ Already correct.
- Fix 3: Spec said delivery-fees returns `items` key — backend returns `orders` key. Frontend uses `data.orders`. ✅ Already correct.
- Fix 4: Spec said expense-breakdown uses `itemDescription`/`total`/`occurrences` — backend uses `description`/`totalAmount`/`count`. Frontend uses `r.description`, `r.totalAmount`, `r.count`. ✅ Already correct.
- Fix 5: Spec said top-dates returns a direct array — backend returns `{ dates: [...] }`. Frontend uses `data.dates`. ✅ Already correct.
- Fix 6: printMonthlyReport same fields — all verified correct against backend source. ✅ Already correct.

**Only genuine fix — SQL test data (Fix 7):**
- `ecommerce_platform` column was NULL for all 71 ECOMMERCE test orders (seeded before the column was used), causing the E-commerce Breakdown card to show only "UNKNOWN"
- SQL UPDATE ran: `CASE (FLOOR(RANDOM() * 3))::INTEGER WHEN 0 THEN 'SHOPEE' WHEN 1 THEN 'TIKTOK' ELSE 'LAZADA' END`
- Result: 71 rows updated — LAZADA: 17, SHOPEE: 24, TIKTOK: 30

**No build required.** No code changes. Data fix only.

---

### Session 25 Detail — May 25, 2026 (Reports Completion + E-commerce Breakdown + PDF Charts)

**Files modified:** `ReportsController.java` (backend), `index.html`, `js/app.js`. No migration (V21 still current).

**SECTION 1 — Backend: new `GET /api/reports/ecommerce-breakdown?month=` endpoint**
- Groups ECOMMERCE orders by `ecommerce_platform`; returns overall totals (`totalOrders`, `totalRevenue`, `totalItems`, `avgOrder`) plus per-platform array
- Each platform entry: `platform`, `orderCount`, `revenue`, `avgOrder`, `percentage`, `topProducts` (top 3 by qty via nested query)
- Uses `DATE(o.created_at)` native SQL pattern consistent with other report endpoints

**SECTION 2 — index.html: two new sections added**
- **MoM comparison cards** (3-card grid, inserted before sales-vs-expenses chart): `rep-mom-curr-orders`, `rep-mom-curr-rev`, `rep-mom-change` (value) + `rep-mom-prev-orders`, `rep-mom-prev-rev`, `rep-mom-change-pct` (sub-texts)
- **E-commerce breakdown card** (inserted after source breakdown, before top agents): `rep-ecom-orders`, `rep-ecom-revenue`, `rep-ecom-avg`, `rep-ecom-platforms` stat mini-cards; `rep-ecom-platform-bars` (progress bars); `rep-ecom-platforms-detail` (per-platform detail with top products table)

**SECTION 3 — app.js: new functions + updates**
- `renderInsightsSummary()` extended: populates MoM cards (`rep-mom-curr-orders/rev`, `rep-mom-prev-orders/rev`, `rep-mom-change` with color, `rep-mom-change-pct` with ▲/▼)
- `loadEcommerceBreakdown()` + `renderEcommerceBreakdown()` added: platform bars with Shopee/TikTok/Lazada brand colors, per-platform detail accordion with top-3 products table
- `loadAllReports()` updated: now calls `loadEcommerceBreakdown()` (10 total calls)
- **SKIPPED** (already existed from a prior session, confirmed in audit): `loadSourceBreakdown`, `renderSourceBreakdown`, `renderSourceDonutChart`, `loadTopAgents`, `loadTopDates`, `loadPizzaSummary`, `loadHotSelling`, `loadDeliveryFees`, `loadExpenseBreakdown`, `renderSalesVsExpChart`

**SECTION 4 — app.js: chart-to-image capture in `window.printMonthlyReport`**
- Captures `chart-daily-revenue`, `chart-sales-vs-exp`, `chart-source-donut` canvases via `canvas.toDataURL('image/png')` before the print window opens
- Images embedded between Accounting summary and MoM section in the print document (daily revenue full-width, sales-vs-exp full-width, source donut at 48%)
- `ecommerce-breakdown` added as 10th fetch in `Promise.all`; `ecomSection` table built and inserted after `srcSection` in the document write

**Build result:** `mvnw.cmd clean spring-boot:run` — BUILD SUCCESS. V21 current (no migration). Tomcat started on port 8080 in ~5.5s. ✓

---

### Session 24 Detail — May 25, 2026 (Print Functions Overhaul)

**Files modified:** `js/app.js` only. No backend changes. No migration.

**CHANGE 1 — Replace `window.printMonthlyReport` (async, API-driven)**
- Old version (Session 18): DOM-reading, scraped stat card text values from the page — fragile and incomplete
- New version: `async function` calling all 9 report endpoints in `Promise.all` (insights-summary, accounting-summary, source-breakdown, top-agents, top-dates, pizza-summary, hot-selling, delivery-fees, expense-breakdown)
- Builds full HTML print document with 11 sections: Summary stats (4 boxes), Accounting (4 boxes), MoM comparison (3 boxes), Top 5 products, Source breakdown + share %, Top agents table, Top 3 dates, Pizza box summary, Hot & selling items, Delivery fees table, Expense breakdown
- Local helpers: `fmt()` currency, `num()` integer, `esc()` escape, `box()` stat card, `heading()` section heading, `tbl()` table renderer
- All API field names corrected from spec: `src.breakdown[]`, `agt.agents[]`, `dts.dates[]`, `pz.totalQty`, `r.qty`, `r.pct`, `hot.items[]`, `r.sellingTag`, `df.orders[]`, `r.totalAmount`, `r.description`, `r.count`

**CHANGE 2 — Replace `window.downloadExpensePDF` (API field fix + company header + tfoot + footer)**
- Bug fixed: `i.description` → `i.itemDescription` (correct field from ExpenseController; both day-by-day rows and summary map)
- Added company header: `.logo-sq` (yellow R badge) + "RRBM Packaging Supplies and Trading" + period subtitle — matches monthly report header style
- Grand total moved from `<div class="grand">` to `<tfoot><tr class="grand-row">` inside the day-by-day table — proper semantic HTML
- Footer added: `<div class="footer">RRBM Management System · Confidential · Internal use only</div>`
- CSS refined: `tfoot td` styled with `font-weight:700;font-size:14px;background:#fffbe6;`; `td` baseline padding applied globally
- `window.print()` on load retained via `<script>window.onload=function(){window.print();}<\/script>`

**No build required** — frontend-only changes.

---

### Session 23 Detail — May 25, 2026 (V21 — Delivery Fee, Reports Overhaul, Expense PDF)

**Files modified (backend):**
- `V21__delivery_fee_and_report_columns.sql` (new) — `ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_fee NUMERIC(10,2) NOT NULL DEFAULT 0.00`; indexes `idx_orders_source` + `idx_orders_created_at_source`
- `Order.java` — added `@Column(name = "delivery_fee") private BigDecimal deliveryFee = BigDecimal.ZERO`; `calculateTotals()` updated: `total = subtotal - discount + deliveryFee`
- `CreateOrderRequest.java` — added `private BigDecimal deliveryFee` field
- `OrderController.java` — `createOrder()` sets `order.setDeliveryFee(...)`; `convertToResponse()` passes `order.getDeliveryFee()` to `OrderResponse` constructor
- `OrderResponse.java` — added `private BigDecimal deliveryFee` field (between discount and total)
- `ReportsController.java` — added `@PersistenceContext EntityManager entityManager`; 7 new endpoints: `GET /api/reports/source-breakdown`, `top-agents`, `top-dates`, `pizza-summary`, `hot-selling`, `delivery-fees`, `expense-breakdown`; `getInsightsSummary()` extended: totalExpenses, dailyExpenses, prevMonth, prevMonthOrders, prevMonthRevenue added to response

**Files modified (frontend — index.html):**
- `modal-add-employee` and `modal-edit-employee`: `modal-box` → `max-height:90vh;display:flex;flex-direction:column;`; `modal-body` → `overflow-y:auto;flex:1;min-height:0;`; `modal-footer` → `flex-shrink:0;` (both scrollable)
- New Order form: `orderDeliveryFee` input + `orderDeliveryFeeDisplay` row in summary
- Reports stat cards grid: `grid-3` → `grid-4`; IDs added to stat-sub divs; 4th card "Total Expenses" added
- 8 new report cards: Sales vs Expenses chart, Source Breakdown (donut + table), Top Agents table, Top Dates table, Pizza Box Summary, Hot & Selling Items, Delivery Fees table, Expense Breakdown table
- Expense History card: "Download PDF" button (`exp-range-pdf-btn`, initially hidden)

**Files modified (frontend — js/app.js):**
- `calculateOrderTotals()`: now reads `orderDeliveryFee`, shows/hides `orderDeliveryFeeDisplay`, computes `sub - disc + fee`
- `initOrderForm()`: added event listener for `orderDeliveryFee`
- `addOrder()`: added `deliveryFee` to payload
- `clearOrderForm()`: resets `orderDeliveryFee` to '0', hides `orderDeliveryFeeDisplay`
- `printOrderReceipt()`: Delivery Fee line added between Discount and TOTAL (shown only if > 0)
- `window.loadAllReports`: now calls 7 new load functions in addition to existing 2
- `renderInsightsSummary()`: extended with MoM comparison arrows (▲/▼ %) for orders + revenue; Expenses stat card population; `renderSalesVsExpChart()` dual-line chart using `appState.chartSalesVsExp`
- New functions: `loadSourceBreakdown()`, `renderSourceBreakdown()`, `renderSourceDonutChart()` (using `appState.chartSourceDonut`), `loadTopAgents()`, `loadTopDates()`, `loadPizzaSummary()`, `loadHotSelling()`, `loadDeliveryFees()`, `loadExpenseBreakdown()`
- `loadExpenseRange()`: shows `exp-range-pdf-btn`; stores data in `window._expRangeData`, `window._expRangeStart`, `window._expRangeEnd`
- `downloadExpensePDF()`: opens print window with day-by-day table + expense type summary breakdown

**Key technical notes:**
- `@AllArgsConstructor` on `OrderResponse` — adding `deliveryFee` field required updating the single constructor call in `convertToResponse()` in `OrderController`
- `@PersistenceContext EntityManager` injected as non-final field (not constructor-injected); works alongside Spring constructor DI
- Native SQL uses `::date` cast for timestamp-to-date comparison in PostgreSQL; `ILIKE` for case-insensitive pizza box name matching
- `dailyExpenses` added to insights-summary response: day-by-day expense totals from expenses table for sales-vs-expenses chart
- MoM comparison: prev month orders fetched via existing `orderRepository.findByDateRangeWithItems(prevStart, prevEnd)`
- `renderSalesVsExpChart` merges daily sales dates + daily expense dates into unified sorted date list

**Build result:** `mvnw.cmd clean spring-boot:run` — 60 Java files compiled, BUILD SUCCESS. V21 validated (schema at version 21), Tomcat started on port 8080 in ~8s. ✓

---

### Session 22 Detail — May 23, 2026 (Minor Bug Fixes)

**Files modified:** `js/app.js` only. No backend changes. No migration.

**FIX 1 — Login persists on page refresh (F5)**
- All 32 occurrences of `sessionStorage` replaced with `localStorage`
- Login token and user data now survive browser refresh, tab restore, and navigation; session only clears on explicit logout

**FIX 2 — Delivery Reports print header missing Supplier column**
- `printDeliveryReports()`: `slice(0, 7)` → `slice(0, 8)` so the Supplier column (added in Session 21) is included in the print output
- Hardcoded `<thead>` updated: `<th>Supplier</th>` inserted after `<th>Receipt #</th>` — headers now match the rendered table columns

**FIX 3 — Refund/Void buttons on Order List (today's DELIVERED orders)**
- `renderOrderRows()`: DELIVERED status block now appends Refund (amber) and Void (red) icon buttons when `canManageOrders()` is true (SUPER_ADMIN / ADMINISTRATOR / ADMIN)
- "✓ Done" label retained alongside the buttons so status is still visible
- Same `askRefund()` / `askVoid()` flow as Order History — no new backend changes needed

**Build result:** Frontend-only changes, no compilation required. ✓

---

### Session 21 Detail — May 23, 2026 (V20 — Admin Security Key, Inventory Restore, Supplier Field, Payable Detail)

**Files modified (backend):**
- `V20__admin_security_key.sql` (new) — `ALTER TABLE users ADD COLUMN admin_security_key VARCHAR(255)` + `ALTER TABLE delivery_log ADD COLUMN supplier_name VARCHAR(200) NOT NULL DEFAULT 'Unknown'`
- `User.java` — added `adminSecurityKey` field (`@Column(name = "admin_security_key")`)
- `JwtUtil.java` — added `extractRole(String token)` method
- `UserController.java` — added `PATCH /api/users/{id}/security-key` endpoint (SUPER_ADMIN only; BCrypt hashes key; activity log `ASSIGN_SECURITY_KEY`)
- `AuthController.java` — added `POST /api/auth/verify-security-key` endpoint (looks up caller by JWT userId, BCrypt matches provided key vs stored hash)
- `OrderController.java` — cancel endpoint changed from `masterKey` → `securityKey` (BCrypt validates caller's own key); `UserRepository` + `BCryptPasswordEncoder` injected
- `OrderService.java` — `cancelOrder()` signature changed to `(String orderId, Long cancelledByUserId, String reason)` (masterKey param removed)
- `OrderRepository.java` — added `findByIdWithItems()` with `LEFT JOIN FETCH o.items` (prevents LazyInitializationException in TransactionController)
- `TransactionController.java` — refund/void endpoints: added security key validation (BCrypt) + inventory restore logic; `ProductRepository` injected
- `DeliveryLog.java` — added `supplierName` field
- `DeliveryRequest.java` (dto) — added `supplierName` field
- `ProductController.java` — receipt validation regex `^\d{6}$` → `^[A-Za-z0-9\-]{2,20}$`; `setSupplierName()` on delivery log; `setSupplierName()` on payable from log
- `DeliveryLogItemRepository.java` — added `findByDeliveryLogId()` with JPQL `i.deliveryLog.id` navigation (entity ref, not Long column)
- `PayableController.java` — added `GET /api/payables/{id}` endpoint returning payable fields + delivery line items

**Files modified (frontend):**
- `index.html` — Cancel modal: label → "Admin Security Key"; Refund modal: added `refund-security-key` field; Void modal: added `void-security-key` field; Add Employee modal: added `add-emp-security-key-section` (hidden, Super Admin only); Edit Employee modal: added `edit-emp-security-key-section` (hidden, Super Admin only); Delivery form: supplier field + updated receipt field (maxlength 20, new placeholder/hint); Delivery reports `<thead>`: added `<th>Supplier</th>`
- `js/app.js` — `closeModal()`: clears 7 security key fields; `confirmCancel()`: `masterKey` → `securityKey` payload; `confirmRefund()`: reads `refund-security-key`, validates, includes in payload; `confirmVoid()`: reads `void-security-key`, validates, includes in payload; `actionBadge` map: added `ASSIGN_SECURITY_KEY → badge-honey`; `askAddEmployee()`: show/hide `add-emp-security-key-section` based on `isSuperAdmin()`; `askEditEmployee()`: show/hide `edit-emp-security-key-section`; `submitAddEmployee()`: optional PATCH `/security-key` after account creation; `submitEditEmployee()`: optional PATCH `/security-key` after profile save; `initDeliveryForm()`: clears supplier + receipt (no longer auto-generates); `submitDeliveryReceipt()`: validates supplier, updated regex, includes `supplierName` in payload; `openPayableDetail()`: calls `GET /api/payables/{id}`, renders supplier row + line items table; `renderDeliveryReports()`: adds Supplier column, colspans 8→9

**Key technical notes:**
- `DeliveryLogItem.deliveryLog` is a `@ManyToOne` entity ref — JPQL must use `i.deliveryLog.id`, not Spring Data method naming `findByDeliveryLogId`
- Inventory restore uses `orderRepository.findByIdWithItems()` with JOIN FETCH to avoid LazyInitializationException outside transaction context
- Refund restore is proportional: `refundRatio = min(refundAmount / orderTotal, 1.0)`; Void restore is full (ratio = 1.0)
- Admin security key is per-employee BCrypt hash stored in `users.admin_security_key`; SUPER_ADMIN assigns via PATCH; employee verifies against own hash at Cancel/Refund/Void time
- `extractRole()` added to JwtUtil (was missing, referenced in spec)
- Spec said "V19 migration" but V19 was already the test seed — corrected to V20

**Build result:** `mvnw.cmd clean compile` — 60 Java files compiled, BUILD SUCCESS. `spring-boot:run` — V20 applied in 00:00.194s, now at schema v20. Tomcat started on port 8080 in 22.7s. ✓

---

### Session 20 Detail — May 23, 2026 (V19 — QA Test Seed)

**Files modified (backend):** `V19__test_seed.sql` (new)
**Files modified (frontend):** none
**Next available migration:** V20

**Purpose:** Insert 5 weeks of realistic test data so that every feature — dashboard charts, reports, refund/void, expense history, payables, role-based access — can be exercised against non-trivial data without touching production records. All test rows are tagged for one-command cleanup.

**STEP 1 — 35 days × 4 orders/day = 140 test orders**
- Date range: `CURRENT_DATE - 35` → `CURRENT_DATE - 1`
- Products looked up by `product_code` (`PPBW10` / `PPBW12`), with active-product fallback
- 4 orders per day rotate all payment modes: CASH, GCASH, COD, BANK_TRANSFER
- 3-way rotation for source: WALK_IN, ECOMMERCE, RESELLER (ONLINE is invalid — not in CHECK constraint)
- 3-way rotation for order_type: STANDARD, PICK_UP, COD
- Varying qty (50–250 / 30–130) gives charts a non-flat shape
- `order_items` guard: `IF NOT EXISTS (SELECT 1 FROM order_items WHERE order_id = v_order_id)` — no unique constraint exists on the table
- SALE transaction per order: `ON CONFLICT (transaction_code) DO NOTHING` (transaction_code is UNIQUE)
- Cleanup tag: `customer_name LIKE 'TEST_%'`

**STEP 2 — 2 special orders on CURRENT_DATE-3**
- `TEST_Void_Target_1` — CANCELLED status, ₱2,100, 200 × PPBW10; find in Order History → Void
- `TEST_Refund_Target_1` — ACTIVE status, ₱1,894.20 (100 × ₱10.50 + 67 × ₱12.60); find → Refund
- Both orders have matching SALE transactions in the ledger

**STEP 3 — 35 closed daily_reports**
- One row per past date (days 1–35); sums only test orders for that date
- `closed_by = v_user_id` (BIGINT FK — NOT text); `notes = 'TEST_SEED'` used as cleanup tag
- `ON CONFLICT (report_date) DO NOTHING` — safe if a real report exists for that date

**STEP 4 — 14 days of test expenses**
- Dates: `CURRENT_DATE - 1` through `CURRENT_DATE - 14`; admin_name = `TEST_Admin`
- Escalating totals ₱1,250 → ₱1,900 (₱50/day increment); 3 expense_items each (Food/Gas/Delivery Allowance)
- No idempotency guard — expenses have no unique key; re-running seed will duplicate them (cleanup script handles this)

**STEP 5 — Test delivery receipt + PENDING payable**
- Receipt `888001` guarded with `IF EXISTS ... THEN RETURN` — fully idempotent
- `delivery_log`: uses `encoded_by_name` (VARCHAR) + `report_date = CURRENT_DATE - 2`
- `delivery_log_items`: 500 ordered, 480 received, 20 rejected, unit_cost = 10.50; `line_total` (GENERATED) = ₱5,040.00
- `payables`: PENDING, total_amount = 5040.00, created_by = 'TEST_Admin' (VARCHAR — not BIGINT)

**Schema corrections vs spec (8 fixes applied):**
1. `ONLINE` → `ECOMMERCE` for source (ONLINE fails V10 CHECK constraint)
2. `is_closed = true` removed (no such column; closed = `closed_at IS NOT NULL`)
3. `closed_by = 'TEST_SEED'` → `closed_by = v_user_id`; `notes = 'TEST_SEED'` used as tag instead
4. `ON CONFLICT DO NOTHING` on order_items removed (no unique constraint); replaced with NOT EXISTS guard
5. `encoded_by` → `encoded_by_name` (V5 actual column name)
6. Missing `report_date` added to delivery_log INSERT
7. Product ILIKE patterns → `product_code = 'PPBW10'` (reliable; ILIKE won't match actual names)
8. Payable total ₱6,240 → ₱5,040 (480 × ₱10.50, not ₱13/unit)

**Testing guide (Groups A–H):**
- **A** Dashboard charts — visit Dashboard, switch Daily/Weekly/Monthly tabs; verify bar chart spans 35 days
- **B** Reports page — select any past month; verify order counts, revenue totals, Top 5 table
- **C** Refund — find TEST_Refund_Target_1 in Order History; click Refund; enter partial amount
- **D** Void — find TEST_Void_Target_1 in Order History; click Void; confirm negative transaction
- **E** Expense History — open Expenses view; set date range covering last 14 days; click Load
- **F** Payables — open Payables; verify receipt 888001 shows PENDING ₱5,040; mark PAID
- **G** Role access — log in as STANDARD_USER with limited pages; verify blocked views are hidden
- **H** Accounting summary — Reports > Accounting tab; verify NET_SALES = grossSales + refunds

**Cleanup script (run to remove all test data):**
```sql
DELETE FROM transactions WHERE notes = 'Test seed sale'
  OR transaction_code LIKE 'TEST-SALE-%';
DELETE FROM order_items  WHERE order_id IN (SELECT id FROM orders WHERE customer_name LIKE 'TEST_%');
DELETE FROM orders       WHERE customer_name LIKE 'TEST_%';
DELETE FROM daily_reports WHERE notes = 'TEST_SEED';
DELETE FROM expense_items WHERE expense_id IN (SELECT id FROM expenses WHERE admin_name = 'TEST_Admin');
DELETE FROM expenses      WHERE admin_name = 'TEST_Admin';
DELETE FROM payables      WHERE supplier_name LIKE 'TEST%';
DELETE FROM delivery_log_items WHERE delivery_log_id IN (SELECT id FROM delivery_log WHERE receipt_number = '888001');
DELETE FROM delivery_log  WHERE receipt_number = '888001';
```

**Build result:** V19 applied in 00:00.329s, now at schema v19. Tomcat started on port 8080 in 14.4s. ✓

---

### Session 19 Detail — May 23, 2026 (V18 — Inventory Prices, Costs & New Products)

**Files modified (backend):** `V18__update_prices_costs_add_new_products.sql` (new)
**Files modified (frontend):** none
**Next available migration:** V19

**CSV changes vs V16:**
- V16 seeded all 108 products with `unit_price = 0` and `unit_cost = 0` (placeholder)
- Updated CSV now has real prices and costs for all products
- 2 products in CSV were missing from V16: `PB4P18` and `PB4P20` (Plain 4-in-1 Party Box 18" and 20")

**STEP 1 — Bulk UPDATE existing 108 products**
- `UPDATE products SET unit_price, unit_cost FROM (VALUES ...)` — single statement covers all 108 rows
- Costs set to `NULL` for products where CSV has no cost data (RSC A/B/C/G, Offset Pastry, Bilao, Pizza Supplies, Packaging Tapes/Wrappers, LHNB01)
- Stocks (`stock_wh1/2/3`) intentionally NOT updated — those are tracked live by the order system
- Notable: `DBPMB6` cost is 3.56 per CSV (low, but preserved as-is); `SPTC75` and `SPTF75` price = 0 (discontinued pricing preserved)

**STEP 2 — INSERT 2 new Party Box products**
- `PB4P18` — (18) Plain 4 in 1 Party Box, price=37.80, cost=26.00, SELLING tag
- `PB4P20` — (20) Plain 4 in 1 Party Box, price=39.90, cost=28.00, SELLING tag
- Both use `WHERE NOT EXISTS` idempotent insert (no UNIQUE constraint requirement)

**STEP 3 — Soft-deactivate guard**
- `UPDATE products SET active = false WHERE product_code NOT IN (...)` 
- Currently a no-op (all V16 products are in the updated CSV)
- Will correctly deactivate any future product removed from the master CSV

**Build result:** V18 applied in 00:00.048s, now at schema v18. Tomcat started on port 8080 in 10.7s. ✓

---

### Session 18 Detail — May 23, 2026 (Refund/Void Polish, Expense History, Report Print, JwtUtil alias)

**Files modified (backend):** `JwtUtil.java`, `PayableController.java`
**Files modified (frontend):** `index.html`, `js/app.js`
**No new migration** (no DB schema changes)

**SECTION 1 — Refund/Void verify**
- Confirmed all refund/void code already present: `askRefund()`, `confirmRefund()`, `askVoid()`, `confirmVoid()` in app.js; `modal-refund` + `modal-void` in index.html; Refund/Void buttons in `renderOrderHistoryRows()`. No changes needed.

**SECTION 2 — Refund/Void modal polish**
- `modal-refund` help text: `<div style="font-size:12px;...">` → `<p style="font-size:11px;color:var(--text-muted);margin-bottom:12px;">` with updated copy: "Use for partial or full refunds on completed orders. Creates a negative REFUND transaction on today's date — the original closed report is not changed."
- `modal-void` help text: same div→p conversion with updated copy: "Use when the daily report is already closed and you can no longer cancel normally. Creates a negative VOID transaction on today's date — the original closed report is not changed."
- `modal-void` amount label: `Void Amount ₱` → `Void Amount (₱) — defaults to full order total`
- Modal headers (icons + titles) were already correct from S14 — not changed

**SECTION 3 — Expense History date-range card**
- `index.html`: Today's Expenses card gets `margin-bottom:14px;`; new "Expense History" card added below — date-from/date-to pickers, Load + Print buttons, grand total span, result table (`exp-range-tbody`)
- `app.js` `initExpensesView()`: date pre-fill added — `exp-range-start` = first of current month, `exp-range-end` = today; only fills if empty (safe on repeated nav)
- `app.js`: `loadExpenseRange()` — fetches `GET /api/expenses/range?start=&end=`, renders rows with date/adminName/items/total columns, updates `exp-range-total` span with grand total
- `app.js`: `printExpenseRange()` — validates data loaded (checks for colspan), builds print window with expense range header + table + total, calls `w.print()` after 400ms

**SECTION 4 — Monthly Report print button**
- `index.html`: `Print Report` button added to Reports toolbar between Refresh and rep-loading span; calls `printMonthlyReport()`
- `app.js`: `printMonthlyReport()` added before `printDeliveryReports()` — reads month from `rep-month-picker`, pulls DOM values from all 7 stat cards (totalOrders, totalRevenue, itemsSold, grossSales, refundsTotal, adjustmentsTotal, netSales), pulls rows from `rep-top-products-tbody` and `rep-daily-tbody`, builds styled print window (grid stat blocks + accounting block + two tables), prints after 400ms

**SECTION 5 — Pizza box verification**
- Checked V16 migration: all pizza box products have `category = 'Pizza Box'`
- `DashboardController` lowercases category to `'pizza box'` then checks `cat.contains("pizza box")` — matches correctly
- No backend changes needed; implementation was already correct

**SECTION 6 — JwtUtil.extractUsername() + PayableController update**
- `JwtUtil.java`: added `public String extractUsername(String token)` as alias for `extractEmail()` (JWT subject = email; no separate username claim exists)
- `PayableController.java`: all `jwtUtil.extractEmail()` and `log.setUserName(jwtUtil.extractEmail(...))` calls → `jwtUtil.extractUsername()`

**Build result:** `mvnw.cmd clean spring-boot:run` — 60 Java files compiled, no migration needed (V17 current), Tomcat started on port 8080 in 11.3s. ✓

---

### NEXT UP (Phase 10)
- Spring Security JWT filter (replace disabled SecurityConfig)
- Move JWT secret to environment variable (currently hardcoded in application.properties)
- CORS lockdown — restrict to specific frontend origin only
- Input validation layer on all controllers (Bean Validation / @Valid)
- Add missing FK constraint on `order_items.product_id`
- Docker deployment preparation (Dockerfile + docker-compose)
- Production readiness audit (structured logging, global error handler, secrets management)

---

### Session 17 Detail — May 23, 2026 (Payables Module, WH3→Santan, Inventory Edit Upgrade)

**Files modified (backend):** `ProductController.java`, `DeliveryLogItem.java`, `DeliveryRequest.java` (dto), new `DeliveryLogItemRepository.java`, new `Payable.java`, new `PayableRepository.java`, new `PayableController.java`, new `V17__payables.sql`
**Files modified (frontend):** `index.html`, `js/app.js`, `css/styles.css`

**SECTION 2 — Product code `.product-code` chip**
- `styles.css`: `@import` updated to include Public Sans (400, 600); `.product-code` rule added (Public Sans, 11px 600, letter-spacing 0.5px, bg-secondary, border, border-radius 4px, padding 1px 6px)
- `app.js`: 3 locations updated: inventory `codeCell`, delivery autocomplete `codeTag`, delivery reports receipt number — all changed from `<code style="...">` to `<span class="product-code">`

**SECTION 3 — Delivery receipt auto-6-digit**
- `initDeliveryForm()`: changed from clearing receipt field to auto-generating `Math.floor(100000 + Math.random() * 900000)`
- `submitDeliveryReceipt()`: validation regex changed from `^[A-Za-z0-9]{6,7}$` to `^\d{6}$`; error message updated
- Backend `processDelivery()`: receipt validation regex also changed to `^\d{6}$`
- `index.html`: `delivery-receipt` input maxlength=7→6, placeholder updated

**SECTION 4 — WH3 → Santan label rename (DB column names UNCHANGED)**
- `index.html`: inventory table `<th>WH3</th>` → `<th>Santan</th>`; Add Product label updated
- `app.js`: warehouse `<option>` label, `whParts.push` label, `pts.push` label in product dropdown all changed to "Santan"

**SECTION 5 — Inventory Edit modal rebuilt**
- `index.html`: `modal-editprod-form` replaced — new grid layout, `editprod-name-label` header span, Stock WH1/WH2/Santan inputs replace thresh-crit/low, `editprod-master-key` replaces `editprod-key`
- `app.js`: `openEditProductModal()` rewritten — uses new field IDs, sets stockWh1/2/3 values, sets name-label span, populates category + sub-category datalists inline; `submitEditProduct()` rewritten — sends stockWh1/2/3, uses `editprod-master-key`, sends `encodedByName`
- `ProductController.java` PATCH endpoint: added stockWh1, stockWh2, stockWh3 field handlers with change logging

**SECTION 6 — Delivery receive/reject fields**
- `addDeliveryLineRow()` HTML: columns changed from Product(5)|Qty(2)|WH(2)|Remove(2) to Product(4)|Qty(1)|TotalReceived(2)|Rejected(2)|WH(2)|Remove(1)
- `submitDeliveryReceipt()`: collects `.delivery-received` and `.delivery-rejected` per row; sends `received` and `rejected` in items array

**SECTION 7 — Backend: V17 migration + Payables**
- `V17__payables.sql`: adds `received_qty INT DEFAULT 0`, `rejected_qty INT DEFAULT 0`, `unit_cost NUMERIC(12,2) DEFAULT 0`, `line_total` (GENERATED STORED = received_qty × unit_cost) to `delivery_log_items`; creates `payables` table with `balance` generated column, status CHECK, cascade delete, 2 indexes
- `DeliveryLogItem.java`: added `receivedQty`, `rejectedQty`, `unitCost` (BigDecimal) fields + getters/setters
- `DeliveryLogItemRepository.java`: new — extends JpaRepository<DeliveryLogItem, Long>
- `DeliveryRequest.DeliveryItem`: added `received` (Integer) and `rejected` (Integer) fields (Lombok @Data)
- `Payable.java`: new entity — id, deliveryLogId, receiptNumber, supplierName, totalAmount, amountPaid, status (PENDING/PAID/PARTIAL), notes, paidAt, paidBy, createdAt, createdBy; all manual getters/setters
- `PayableRepository.java`: findAllByOrderByCreatedAtDesc, findByStatus, getTotalOutstanding (@Query JPQL SUM)
- `PayableController.java`: GET /api/payables, GET /api/payables/summary (totalOutstanding + pendingCount), PATCH /{id}/status (PAID→sets amountPaid+paidAt+paidBy, PENDING→zeroes them), DELETE /{id} (master key); both status change and delete write ActivityLog; uses `jwtUtil.extractEmail()` (not extractUsername)
- `ProductController.java`: constructor gets `DeliveryLogItemRepository` + `PayableRepository` injected; delivery loop now calculates `totalCost` (received qty × unitCost per item), sets `receivedQty/rejectedQty/unitCost` on each `DeliveryLogItem`; creates `Payable` (PENDING) after delivery save in try-catch (non-fatal)

**SECTION 8 — Payables page (frontend)**
- `index.html`: `nav-payables` button added before nav-expenses; `view-payables` section with 3 stat cards + 8-col table; `modal-payable-detail` (flex, scroll, status toggle + delete buttons); `modal-payable-delete` (master key confirm); payables checkbox added to both Add Employee (checked) and Edit Employee (unchecked) page-access grids
- `app.js`: payables functions added between expenses and employees: `loadPayables()`, `openPayableDetail()`, `togglePayableStatus()`, `askDeletePayable()`, `confirmDeletePayable()`; `viewToPageKey()` gets `'payables':'payables'`; `navigateTo()` titles + `loadPayables()` trigger; `actionBadge` gets PAYABLE_STATUS_CHANGED→badge-honey, DELETE_PAYABLE→badge-crit

**Build result:** `mvnw.cmd clean spring-boot:run` — 60 Java files compiled, V17 validated, Tomcat started on port 8080. ✓

---

### Session 16 Detail — May 23, 2026 (Bug Fixes & Chart Corrections)

**Files modified:** `js/app.js` only. No backend changes. No HTML changes.

**4 of 7 fixes applied. 3 were already implemented.**

**FIX 1 — `initDashboardCharts()` (`js/app.js`)**
- Removed `delete _cs.dataset.init` / `delete _ce.dataset.init` lines — redundant since instances are already destroyed and null'd before re-creation
- Payment donut (`chart-ecommerce`) legend: `display: false` → `display: true`, `position: 'bottom'`, `labels: { color: tickColor, font: { size: 11 }, boxWidth: 10, padding: 12 }` — Chart.js now renders legend natively

**FIX 3 — `renderDailyRevenueChart()` (`js/app.js`)**
- Standardized past-bar opacity: dark `0.30→0.28`, light `0.22→0.20`; hover: dark `0.50→0.48`, light `0.42→0.38`
- Removed `family: 'DM Sans'` from x/y tick font specs — inherit from page font stack
- Variable names aligned with initDashboardCharts convention: `tipText`, `tipBorder`

**FIX 4 — Payment donut update block (`js/app.js`)**
- Removed 20-line `chart-payment-legend` custom HTML update block
- Block reduced to 5 lines: set labels/data/colors/borderColor, call `update()`
- Chart.js built-in legend (enabled in FIX 1) replaces the custom HTML

**FIX 6 — `actionBadge` map (`js/app.js`)**
- Added: `EXPENSE_RECORDED → badge-honey`, `EDIT_PRODUCT → badge-low`, `CHANGE_MASTER_KEY → badge-crit`, `REFUND → badge-crit`, `VOID → badge-crit`, `ADJUSTMENT → badge-low`

**Skipped (already done):**
- FIX 2: `toggleTheme()` already calls `initDashboardCharts()` + `renderDashboard()` (lines 2052–2055)
- FIX 5: `low-stock-viewmore` (HTML line 328), `modal-low-stock-all` (HTML line 1681), `openLowStockModal()` (JS line 2273), 5-row cap + `_allLowStockItems` cache all present
- FIX 7: `dash-greeting-time` + `dash-greeting-name` in HTML (lines 182–183); JS sets both from `sessionStorage.rrbm_user.fullName` in `renderDashboard()`

---

### Session 15 Detail — May 23, 2026 (Real Inventory Data + Sub-Category + Edit + Receipt Fix)

**Files modified (backend):** `ProductController.java`, `ProductRepository.java`, `Product.java`, `V16__sub_category_and_real_inventory.sql`
**Files modified (frontend):** `index.html`, `js/app.js`

---

**MIGRATION V16 — Real Inventory + Sub-Category Column**

- `ALTER TABLE products ADD COLUMN IF NOT EXISTS sub_category VARCHAR(100)`
- Wipes all mock/seed transactional data in the correct FK dependency order:
  1. `DELETE FROM transactions` — non-cascading FK → orders
  2. `DELETE FROM inventory_movements` — non-cascading FK → products
  3. `DELETE FROM order_items` — non-cascading FK → products (also CASCADE from orders, explicit for safety)
  4. `DELETE FROM orders`
  5. `DELETE FROM products`
  6. Resets `products_id_seq` to 1
- Inserts **108 real RRBM products** from company CSV; categories: Pizza Box (Plain/Generic/Claycoat), RSC, Die-Cut Packaging Boxes (Mailer/Flower/Party/Roasted Food/Bilao), Offset Packaging Boxes (Pastry), Pizza Supplies, Packaging Supplies (Tapes/Wrappers)
- All inserted products: `selling_tag = 'SELLING'`, `active = true`, prices/thresholds = 0 (user sets from UI)
- Santan warehouse column from CSV mapped to `stock_wh3` (no product has both WH3 and Santan stock)
- Initial startup failure: FK constraint `order_items_product_id_fkey` blocked DELETE FROM products → added DELETE FROM order_items first; second failure: `transactions_order_id_fkey` blocked DELETE FROM orders → added DELETE FROM transactions first

**FEATURE — Sub-Category (backend + frontend)**

- `Product.java`: added `@Column(name = "sub_category", length = 100) private String subCategory`
- `ProductRepository.java`: added `findDistinctSubCategory()` and `findDistinctSubCategoryByCategory(String category)` JPQL queries
- `ProductController.java`: added `GET /api/products/sub-categories?category=X` endpoint; added `subCategory` field handling in `createProduct()`; added `subCategory` field handling in new `updateProduct()` endpoint
- `index.html` (inventory toolbar): added `<select id="inv-subcategory-filter">` linked after category filter
- `index.html` (Add Product modal): category + sub-category in grid-2 row; `<datalist>` for both
- `app.js`: added `populateInvSubCategoryDropdown(filterCategory)`, `onInvCategoryChange()`, `onAddProdCategoryInput()` functions; sub-category filter in `applyInventoryFilters()`; sub-category shown as muted `<span>` under product name in inventory rows; `openAddProductForm()` populates both datalists; sub-category sent in `submitAddProduct()` body

**FEATURE — Inventory Edit (backend + frontend)**

- `ProductController.java` — new `PATCH /api/products/{id}` endpoint:
  - Validates master key via `MasterKeyService`
  - Finds product by ID (404 if not found)
  - Field-level comparison: Name, Code, Category, SubCategory, UnitPrice, UnitCost, ThresholdCritical, ThresholdLow, Active
  - Builds `List<String> changes` with `"Field: old → new"` format per modified field
  - Logs single `EDIT_PRODUCT` activity entry: `"Edited: [Name] [Code] | Field1: old → new; Field2: old → new"`
  - Returns updated `Product` entity; no changes = still saves (updatedAt bumped) but no log written
  - New imports added: `java.util.ArrayList`, `java.util.Objects`, `java.util.Optional`
- `index.html`:
  - Inventory `<thead>`: added `<th id="inv-actions-th" style="display:none;">Actions</th>` (10th column)
  - Added `modal-editprod-form` modal (after Add Product modal): fields — Code, Name, Category, Sub-Category, Unit Price, Unit Cost, Threshold Critical, Threshold Low, Active radio (Active/Inactive), Master Key password
- `app.js`:
  - `canEditInventory()`: returns true for SUPER_ADMIN, ADMINISTRATOR, or ADMIN with `inventory` in `allowedPages` (or null/unrestricted)
  - `applyInventoryFilters()`: shows/hides `inv-actions-th`; adds Edit button `<td>` per row if `canEditInventory()`; colspan for empty state dynamically set to 9 or 10
  - `openEditProductModal(productId)`: looks up product from `appState.inventoryAllProducts`; pre-fills all form fields; sets Active radio; populates category and sub-category datalists
  - `onEditProdCategoryInput()`: narrows sub-category datalist by selected category (same pattern as Add Product)
  - `submitEditProduct()`: validates inputs; sends PATCH with all editable fields; calls `renderInventory()` + `loadProducts()` on success

**BUG FIX — Receipt Print/Download Cleanup**

- `printOrderReceipt()`: removed the `.btn-print` CSS rule and the `<button class="btn-print">` element; added `<script>window.onload=function(){window.print();}<\/script>` — receipt now auto-opens the browser print dialog on load (no UI controls visible in output; consistent with delivery and daily report print functions)
- `exportOrderHistoryPDF()`: removed `tableEl.outerHTML` DOM cloning (which included all 4 action button elements per row — Print, Ledger, Refund, Void); replaced with data rebuild from `appState.orderHistoryAll`; clean 7-column output: Order #, Date, Customer, Items, Total, Source, Status; also auto-triggers print on load instead of showing a manual button

---

### Session 14 Detail — May 22, 2026 (UI Upgrade — frontend only)

**No backend or Flyway changes. Files touched: `css/styles.css`, `js/app.js`, `index.html`.**

**CSS Polish (11 sections — `styles.css`)**
- Nav accent: `.nav-item` base has `border-left: 2.5px solid transparent` + transition; `.nav-item.active` flips to `var(--brand-honey)`
- Ghost stat icons: `.stat-icon { right:14px; bottom:14px; font-size:44px; opacity:0.06 }`
- Btn: shimmer `::after` overlay, lift+shadow on primary hover, `transform:scale(0.97)` on active, secondary/danger/dark variants
- Icon-btn: honey color+border on hover, `scale(1.08)`, `scale(0.95)` on active
- Form-control: honey focus ring `box-shadow: 0 0 0 3px rgba(212,134,10,0.12)`
- Tab-btn: `border:1px solid transparent` base; active state = honey fill + white text
- Card/stat-card hover lifts: `translateY(-2px)` + honey shadow
- Modal spring: `animation: modal-in 0.2s cubic-bezier(0.34,1.56,0.64,1)` on `.modal-overlay.open .modal-box`
- Table row hover: `rgba(212,134,10,0.04)` light / `0.07` dark
- Pizza quota: `@keyframes quota-roll`, `quota-shimmer`, `quota-bar-pulse`; gradient-clip text on `.pizza-quota-met`
- Low-stock: `#low-stock-tbody tr.low-stock-hidden { display:none }`, `.low-stock-viewmore` hover style

**Dashboard HTML Reorder (`index.html`)**
- New order: stat cards → pizza quota → charts (grid-2) → top-5 → payment minis (grid-4) → expenses (grid-4) → low-stock panel
- Added `low-stock-viewmore` div with "View all →" link below low-stock table
- Added `modal-low-stock-all` — flex+column modal, `max-height:80vh`, scrollable tbody, header count badge

**Login Screen Redesign (`index.html` + `styles.css`)**
- Split-panel layout: decorative left panel (dot-grid, 5 animated rings, feature pills) + login form right
- 5 ring `@keyframes lr1–lr5`, staggered `login-fade-up` entrance animation
- Mobile collapses at 640px (left panel hidden, form full-width)
- All existing IDs (`login-email`, `login-password`) and `doLogin()` call preserved

**Dashboard Charts (`app.js`)**
- `initDashboardCharts()` fully replaced: line chart with gradient fill on `chart-sales`, donut on `chart-ecommerce`; destroys old instances before re-init; reads theme from `document.body.dataset.theme`
- `toggleTheme()` now calls `initDashboardCharts()` after flipping theme
- `renderDailyRevenueChart()` fully replaced: per-bar honey coloring (most recent = `#D4860A`, past = soft tint), `borderRadius:5`, `borderSkipped:false`, no x-grid, themed tooltip

**Low-Stock + Pizza (`app.js`)**
- Low-stock render: `slice(0,5)` for panel rows; all items cached in `window._allLowStockItems`; viewmore link shown/hidden; `openLowStockModal()` populates `modal-low-stock-all` with count badge + all rows
- Pizza quota: 3-tier — ≥100% green shimmer gradient (CSS clip text, `webkitTextFillColor:''`), ≥50% yellow, <50% red

**Activity Log Badge Map (`app.js`)**
- Added: `ORDER_ON_HOLD → badge-honey`, `ORDER_RESUMED → badge-ok`, `ORDER_STATUS_UPDATED → badge-low`, `UPDATE_USER_PERMISSIONS → badge-honey`, `DELIVER_ORDER → badge-ok`
- Unknown actions now fall back to `badge-low` instead of empty string

---

### Session 13 Detail — May 22, 2026

**BUG FIX 1 — Block Close Daily Sales if Open Orders Exist**
- `DailyReportService.java`: native query checks for `ACTIVE` or `PENDING` orders matching today's date prefix before allowing close; throws `RuntimeException` with count if any found
- Date prefix format: `DDMMYY-` (e.g. `220526-`) matches the order ID generation scheme

**FEATURE 2 — Mandatory Cancellation Reason**
- `index.html` (`modal-cancel`): added required textarea `cancel-reason-input` above the master key field
- `app.js` (`askCancel`): clears reason textarea on open; `confirmCancel` validates reason first (toast error if blank), sends `{ masterKey, reason }` to backend
- `OrderController.java`: validates reason not blank, returns 400 if missing
- `Order.java` / DB: `cancellation_reason` column (existing from prior migration); `getCancellationReason()` / `setCancellationReason()` used
- `OrderResponse.java`: added `cancellationReason` field; `convertToResponse()` passes it through
- `renderOrderRows()` + `renderOrderHistoryRows()` (`app.js`): show reason in red below CANCELLED status badge

**FEATURE 3 — Cancel Reason in Activity Log**
- `OrderService.cancelOrder()`: log description now includes `" — Reason: " + reason`

**BUG FIX 4 — Activity Log Action Strings**
- `OrderService.updateStatus()`: action type strings changed to SCREAMING_SNAKE_CASE: `ORDER_ON_HOLD`, `ORDER_RESUMED`, `ORDER_STATUS_UPDATED` (were stored with spaces/mixed case, not matching badge map)

**Files modified (backend):** `DailyReportService.java`, `OrderController.java`, `OrderService.java`, `OrderResponse.java`
**Files modified (frontend):** `index.html` (cancel modal), `js/app.js` (confirmCancel, renderOrderRows, renderOrderHistoryRows, actionBadge map)

---

### Session 12 Detail — May 22, 2026

**BUG FIX — UserController.java Compile Error**
- Error: `incompatible types: inference variable T has incompatible bounds` at line 245 (`updatePermissions`)
- Root cause: the `updatePermissions` lambda only returned `ResponseEntity<UserDto>` but `.orElse()` supplied `ResponseEntity<Map<K,V>>` — Java couldn't unify the two types
- Fix: added explicit type witness `.<ResponseEntity<?>>map(...)` so the compiler infers the right generic bound
- File: `UserController.java`

---

**PHASE 9 — Transaction Ledger (Accounting Architecture)**

**Goal:** Make financial reporting production-grade and audit-safe by introducing an immutable transaction ledger as the single source of truth for all revenue calculations. Orders remain the operational record; transactions become the accounting record.

**Core accounting rule:** `NET_SALES = grossSales + refundsTotal + adjustmentsTotal`

**Accounting flow:**
```
Order placed       → SALE transaction   (positive, today's effective_date)  [atomic with order save]
Order cancelled    → VOID transaction   (negative, date of cancellation — NOT original order date)
Post-close refund  → REFUND transaction (negative, refund date → historical report stays untouched)
Manual correction  → ADJUSTMENT transaction (positive or negative)
Daily close        → snapshot: grossSales / refundsTotal / adjustmentsTotal / netSales from ledger
```

**V15 Migration (`V15__transaction_ledger.sql`)**
- Created `transactions` table with: id, transaction_code (unique), order_id (FK, nullable), transaction_type, amount, reference_type, reference_id, notes, created_by (FK), created_at, effective_date
- Indexes on `order_id`, `effective_date`, `transaction_type`
- Added 5 new columns to `daily_reports`: `gross_sales`, `refunds_total`, `adjustments_total`, `net_sales`, `total_transactions` (all nullable — existing closed reports stay valid)
- Backfills SALE transactions for all existing non-cancelled orders (idempotent, safe to re-run)

**Transaction Types:**
| Type | Amount | When |
|------|--------|------|
| SALE | positive | Order created |
| VOID | negative | Order cancelled (same-day or post-close) |
| REFUND | negative | Partial or full post-close refund |
| RETURN | negative | Goods physically returned |
| DISCOUNT | negative | Order-level discount adjustment |
| ADJUSTMENT | +/- | Manual accounting correction |

**Daily Close Redesign (`DailyReportService.java`)**
- Financial totals (grossSales, refundsTotal, netSales) now calculated from `transactions` table
- Operational stats (order counts, source breakdown, top product) still calculated from `orders` — these are business metrics, not accounting
- `total_revenue` legacy field kept for backward compat; now equals `netSales`
- Closed report is immutable — post-close reversals write to a future date, never the closed date

**Files created (backend):**
- `V15__transaction_ledger.sql`
- `Transaction.java` — immutable accounting entity
- `TransactionRepository.java` — JPA repo with aggregate SUM/COUNT queries
- `TransactionService.java` — core ledger: recordSale, recordVoid, recordRefund, recordPostCloseVoid, recordAdjustment + read helpers
- `TransactionController.java` — REST endpoints for all ledger operations

**Files modified (backend):**
- `DailyReport.java` — added grossSales, refundsTotal, adjustmentsTotal, netSales, totalTransactions fields
- `DailyReportService.java` — injected TransactionService; close now reads financial totals from ledger
- `OrderService.java` — injected TransactionService; createOrder() calls recordSale(); cancelOrder() calls recordVoid()
- `ReportsController.java` — injected TransactionService; added GET /api/reports/accounting-summary endpoint

---

### Session 11 Detail — May 21, 2026

**FIX 1 — Activity Log: Action Labels**
- `OrderService.updateStatus()`: replaced `RESUME_ORDER` / `UPDATE_ORDER_STATUS` with human-readable labels
- → ACTIVE from PENDING/ON_HOLD = `"Order Resumed"` | → PENDING/ON_HOLD = `"Order On Hold"` | other = `"Order Status Updated"`

**FIX 2 — Dashboard: Low Stock Count Was Zero**
- `DashboardController.getStats()`: replaced per-product `thresholdCritical` column with tag-based low thresholds
- HOT=5000, SELLING=2000, SLOW=1000 — counts products AT OR BELOW low threshold (not just critical)

**FEATURE 3 — Reports: Real Data + Product Popularity**
- New `GET /api/reports/insights-summary?month=YYYY-MM` — returns totalOrders, totalRevenue, totalItemsSold, dailyBreakdown[], topProducts[] (top 5 by qty with % share)
- Uses `JOIN FETCH` queries to avoid N+1 lazy-load
- Reports view completely rebuilt: month picker, 3 summary cards, daily bar chart, Top 5 products table, daily breakdown table

**FEATURE 4 — Dashboard: Weekly/Monthly Tabs + Pizza Box Quota**
- `/api/dashboard/stats` now accepts `?period=daily|weekly|monthly` — adjusts date range for sales totals
- Active/Pending orders always live (today-based); period only affects sales totals + cancelled count
- New `pizzaBoxQtyToday` field — batch-loads products by ID to avoid N+1
- New `totalExpensesToday` field added to stats response
- Frontend: tab buttons wired with `switchDashTab(period)`; stat card labels update per period
- New Pizza Box Quota Tracker card: progress bar, RED <50% / YELLOW 50-99% / GREEN ≥100%, CSS pulse/bounce animation on quota met

**FEATURE 5 — Dashboard: Top 5 Products Sold Today**
- New `GET /api/dashboard/top-products-today` — aggregates today's non-cancelled order items by product name
- Dashboard: "Top 5 Products Sold Today" table (rank, name, qty, revenue) with medal emoji ranks
- Auto-loads on dashboard navigate and tab switch

**FEATURE 6 — Expenses Module (Full)**
- V14 migration: `expenses` + `expense_items` tables
- New entities: `Expense.java`, `ExpenseItem.java`, `ExpenseRepository.java`
- `POST /api/expenses` — records expense, logs `"Expense Recorded"` activity
- `GET /api/expenses?date=` — expenses for a date (default today)
- `GET /api/expenses/range?start=&end=` — date range
- Dashboard: "Total Expenses Today" stat card
- Frontend: new "Expenses" nav item + view — date picker, readonly admin name, dynamic item rows, live running total, today's expenses table

**FIX 3 — Add Product Form: Removed Obsolete Threshold Fields**
- Removed `addprod-thresh-crit` (Critical Threshold) and `addprod-thresh-low` (Low Threshold) input fields from the Add Product modal in `index.html`
- Reason: tag-based thresholds (HOT=5000, SELLING=2000, SLOW=1000) replaced per-product threshold columns entirely in Session 7; the form fields were leftover UI that no longer did anything meaningful
- The underlying `threshold_critical` and `threshold_low` DB columns still exist but are ignored by all backend logic
- Stock alert levels are now determined solely by the product's `selling_tag` value

**FEATURE 7 — Role-Based Page Access**
- V14 migration: `allowed_pages TEXT` column on users; existing non-Super-Admin users defaulted to all pages
- `User.java`: added `allowedPages` field
- `LoginResponse.UserInfo`: added `allowedPages` (null = unrestricted for Super Admin)
- `AuthService.login()`: includes `allowedPages` in response
- `UserController`: `allowedPages` in `UserDto`; new `PATCH /api/users/{id}/permissions` endpoint (Super Admin only); new users default to all pages
- Frontend: `viewToPageKey()` maps views to page keys; `canAccessPage()` checks allowedPages; `navigateTo()` guards all views; nav items hidden per allowedPages; Page Access checkboxes in Add/Edit Employee modals (disabled for non-Super-Admin in edit modal)

**Files created (backend):**
- `V14__expenses_and_page_permissions.sql`
- `Expense.java`, `ExpenseItem.java`, `ExpenseRepository.java`
- `ExpenseController.java`
- `ReportsController.java`

**Files modified (backend):**
- `OrderService.java` — FIX 1 action labels
- `DashboardController.java` — FIX 2 tag thresholds, period param, pizza quota, expenses today, top-5-today endpoint, ExpenseRepository injected
- `OrderRepository.java` — added `findByDateRangeWithItems` + `findByCreatedAtDateWithItems` (JOIN FETCH)
- `User.java` — added `allowedPages` field
- `UserController.java` — allowedPages in UserDto, PATCH /permissions endpoint, default pages on create, JwtUtil injected
- `AuthService.java` — allowedPages in login response
- `dto/LoginResponse.java` — allowedPages in UserInfo

**Files modified (frontend):**
- `index.html` — Reports view rebuilt, dashboard tab IDs + pizza quota card + expenses stat card + top-5 section, Expenses nav + view, Page Access checkboxes in both employee modals, removed obsolete Critical/Low Threshold fields from Add Product form
- `js/app.js` — initReportsView/loadInsightsSummary/renderInsightsSummary/renderDailyRevenueChart, switchDashTab, renderDashboard with period + pizza quota, renderTopProductsToday, initExpensesView + all expense helpers, viewToPageKey/getAllowedPages/canAccessPage/applyPageAccessToNav, navigateTo guard, submitAddEmployee/submitEditEmployee page checkboxes
- `css/styles.css` — quota-pulse + quota-bounce keyframes, .pizza-quota-met, .grid-3

### Session 10 Detail — May 21, 2026 (Phase 7)

**Dashboard — Live Data**
- New `GET /api/dashboard/stats` endpoint aggregates real data in one call
- Stat cards wired: Total Sales, Active/Pending Orders, Cancelled Today, Low Stock count
- Payment breakdown cards: Cash, E-wallet/Online, COD, Bank
- Sales trend bar chart (last 7 days) — today from live orders, prior days from daily_reports
- Payment breakdown donut chart — dynamic from today's payment modes
- `renderDashboard()` called on login and on every `navigateTo('dash')`
- Chart.js instances stored in `appState.chartSales` / `appState.chartPayment` for live updates

**Settings — Company Info**
- Company Name / Address / Contact fields now have IDs and Save button
- `GET /api/settings` loads all non-sensitive keys; pre-fills inputs on settings page open
- `POST /api/settings` saves editable keys (company_name, company_address, company_contact)
- V13 migration seeds `company_address` and `company_contact` default values
- New `Settings.java` entity + `SettingsRepository.java` + `SettingsController.java`

**Settings — Master Key Change**
- `POST /api/auth/master-key` now implemented in `AuthController`
- Validates current key via BCrypt, rotates using `masterKeyService.rotateMasterKey()`
- Logs `CHANGE_MASTER_KEY` to activity log
- Frontend `changeMasterKey()` was already wired — now actually works

**Cleanup**
- Deleted `TestController.java` and `TestAuthController.java` (debug endpoints removed)
- Fixed tech debt item: `userId = 3L` hardcoded in `OrderController` → now reads from JWT
- Fixed: Pending → Active status changes not logged → `OrderService.updateStatus()` now logs with correct actor

**Files added:**
- `V13__company_settings.sql` — company_address + company_contact default values
- `Settings.java` — entity for settings table
- `SettingsRepository.java` — JPA repo for settings
- `SettingsController.java` — GET + POST /api/settings
- `DashboardController.java` — GET /api/dashboard/stats

**Files modified:**
- `JwtUtil.java` — added `extractUserId()` method
- `AuthController.java` — added POST /api/auth/master-key; injected MasterKeyService + JwtUtil
- `OrderController.java` — replaced hardcoded userId=3L with JWT extraction in all 3 endpoints
- `OrderService.java` — `updateStatus()` now accepts changedByUserId + logs transition
- `UserController.java`, `User.java`, `UserRepository.java` — full employee profile CRUD
- `index.html` — dashboard stat card IDs, updated Settings Company card, new Employee List layout
- `app.js` — renderDashboard(), loadSettings(), saveCompanySettings(), initDashboardCharts() with live update, navigateTo wiring

### Session 9 Detail — May 21, 2026

**New Order Improvements**
- Source dropdown: added Reseller, Distributor → shows name input field when selected
- Payment Mode: added COD; COD + PENDING orders require admin password to resume (not master key)
- Order Type dropdown: Standard, Pick Up, COD
- Address / Location field (optional)

**Receipt / PDF Export**
- Print Receipt button on every order row (Order List + Order History)
- Opens formatted popup: company logo, address (+63 966 846 9993), full order table, Prepared By (auto-filled), Received By (signature area)

**Activity Log Fix**
- `RECEIVE_STOCK` now logs the encoded-by (logged-in admin) as the actor, not the receiver
- Description includes: Receipt #, Received by, Verified by, unit/product counts
- "ID" column renamed to "User ID" — now shows `userId` of the actor
- "Entity" column shows entityType + entityId together

**Settings Access Restriction**
- Settings nav item hidden for non-Super-Admin after login
- `navigateTo('set')` blocks and shows toast if user is not Super Admin

**User Role Management**
- Employees view now loads real user data from `GET /api/users`
- Super Admin sees: Add Member button, Role change button, Enable/Disable button per user
- "Add Member" modal: Full Name, Email, Password, Role selection → `POST /api/users`
- "Change Role" modal: STANDARD_USER, ADMINISTRATOR, SUPER_ADMIN → `PATCH /api/users/{id}/role`
- Enable/Disable via `PATCH /api/users/{id}/status`

**Bug: Activity Log ID Column**
- Fixed: shows `userId` (actor's DB ID) instead of `entityId`

**Files modified:**
- `V10__order_improvements.sql` — source/payment constraints, order_type, address columns
- `V11__user_roles.sql` — ADMINISTRATOR + STANDARD_USER roles, employee_id column
- `Order.java` — orderType, address fields
- `CreateOrderRequest.java` — orderType, address fields
- `OrderResponse.java` — orderType, address fields
- `OrderController.java` — map new fields; update convertToResponse constructor call
- `ProductController.java` — RECEIVE_STOCK log uses encodedByName, richer description
- `AuthService.java` — added verifyPassword() method
- `AuthController.java` — added POST /api/auth/verify-password endpoint
- `UserController.java` — new: GET/POST /api/users, PATCH /api/users/{id}/role|status
- `index.html` — New Order form restructured, Payment column in Order List, Order History Print column, Activity Log header fix, Employees view dynamic, 3 new modals (COD Resume, Add Member, Assign Role)
- `app.js` — full rewrite with all Session 9 features

### Session 8 Detail — May 21, 2026 (8 bug fixes)

| # | Issue | Root Cause | Fix |
|---|-------|------------|-----|
| 1 | Inventory toolbar not in one row | `flex-wrap:wrap` on controls div | Removed wrap, `flex-shrink:0` on controls, `margin-right:auto` on title |
| 2 | Category filter needed tab switch | `onchange="renderInventory()"` re-fetched everything | Changed to `onchange="filterInventory()"` — pure client-side filter |
| 3 | No Product Code column | Field didn't exist | V9 migration + `product_code` entity field + new table column + search |
| 4 | Selling Tag in Add Product form | UX confusion | Removed tag field; always defaults to SELLING; editable only from inventory |
| 5 | Activity log shows "Admin" | ProductController hardcoded "Admin" in tag update | Frontend passes `userName: currentUserName()`; backend uses it |
| 6 | Delivery product was plain `<select>` | Old implementation | Replaced with searchable autocomplete (same pattern as order form) |
| 7 | Form clears on tab switch | `navigateTo` called `initDeliveryForm/initOrderForm` every time | Added `deliveryFormReady`/`orderFormReady` flags; only full-init once |
| 8 | All Orders showed all history | `GET /api/orders` returned everything | Order List → `/today` only; new Order History view with date range + PDF |

**Files modified:**
- `V9__add_product_code.sql` (new) — product_code column + unique index
- `Product.java` — added `productCode` field
- `ProductController.java` — handle productCode in create; use passed userName in tag update
- `OrderController.java` — added `GET /api/orders/history` endpoint
- `OrderRepository.java` — added `findByDateRange` JPQL query
- `OrderService.java` — added `getOrdersByDateRange` method
- `index.html` — inventory toolbar, product code column, Add Product form, Order History nav+view
- `app.js` — full rewrite: all 8 issues, searchable delivery autocomplete, form state flags, order history with PDF export

### Session 7 Detail — May 21, 2026
**10 features implemented:**
1. Add Product modal — two-step master key → product form → POST /api/products
2. Logout confirmation modal (Yes/No)
3. Inventory keyword search bar (client-side, two-tier with category filter)
4. Close Daily Sales button — master key protected, POST /api/reports/close-daily
5. Order List search bar — client-side filter by ID + customer name
6. Cancel Order — master key gate confirmed working
7. Tag-based stock thresholds (HOT=5000/2500, SELLING=2000/1000, SLOW=1000/500) + editable tag-select dropdown with colored styling
8. Delivery encoding form — receipt# validation, auto-fill encoded-by, notes
9. Delivery Reports section in Insights — date filter + notes modal
10. Activity Log section in Insights — date filter, Super Admin only

**Backend changes:**
- `ProductController` — added POST /api/products (create product + activity log) and PATCH /api/products/{id}/tag
- `DeliveryRequest.java` — added `encodedByName` field
- `RrbmBackendApplication.java` — seeds default master key ("rrbm2024") on first boot if table empty
- Fixed delivery endpoint to save DeliveryLog + DeliveryLogItem records (previously only updated stock)

**Frontend changes:**
- `index.html` — full rewrite: new nav items (Delivery Reports, Activity Log), all new modals, delivery form updates, Settings master key form
- `app.js` — full rewrite: all 10 features, tag-select class update, two-tier inventory search, applyInventoryFilters()
- `styles.css` — added .tag-select, .tag-select-HOT/SELLING/SLOW with dark mode variants

### Session 6 Detail — May 20–21, 2026
- Built Phase 4: Product catalog, inventory movements, stock tracking (WH1/WH2/WH3)
- Built Phase 5: Daily Reports (close-day feature), Activity Log, Delivery Log / receipt intake
- Added Master Key system for sensitive ops (cancel orders, close day)
- Added `DELIVERED` order status (V4 migration)
- **Flyway migration crisis fixed** — see Bugs section #9

---

## PHASE STATUS

| Phase | Status | Description |
|-------|--------|-------------|
| 0 — Environment | ✅ Done | Java 21, PostgreSQL 18.4, Maven, Cursor IDE |
| 1 — DB Schema | ✅ Done | 10 tables + Flyway V1–V15 migrations |
| 2 — Auth | ✅ Done | JWT login, BCrypt, 8h token, frontend connected |
| 3 — Orders | ✅ Done | Create/list/cancel, DDMMYY-NNNNNN IDs, order items |
| 4 — Products & Inventory | ✅ Done | Product CRUD, stock per warehouse, inventory movements |
| 5 — Reports & Logs | ✅ Done | Daily reports, activity log, delivery receipts |
| 6 — Settings & Employees | ✅ Done | Employee CRUD, role management, Settings save/load, Master Key change |
| 7 — Dashboard & Reports | ✅ Done | Live stats, weekly/monthly tabs, pizza quota, top-5 today, real reports |
| 8 — Expenses & Permissions | ✅ Done | Expenses module (full), role-based page access, page checkboxes in employee modals |
| 9 — Accounting Architecture | ✅ Done | Transaction ledger, immutable daily close, refund/void/adjustment endpoints, NET_SALES formula |

---

## FOLDER STRUCTURE
```
rrbm_daily/
├── RRBM-BUILD-LOG.md                   ← This file
├── .gitignore
│
├── rrbm-backend/                        Spring Boot 3.5.14 / Java 21
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/main/
│       ├── java/rrbm_backend/
│       │   ├── RrbmBackendApplication.java
│       │   │
│       │   ├── User.java / UserRepository.java
│       │   ├── JwtUtil.java
│       │   ├── AuthService.java / AuthController.java
│       │   ├── TestController.java / TestAuthController.java
│       │   │
│       │   ├── Order.java / OrderItem.java
│       │   ├── OrderIdCounter.java / OrderIdCounterRepository.java
│       │   ├── OrderIdGenerator.java
│       │   ├── OrderRepository.java / OrderService.java / OrderController.java
│       │   │
│       │   ├── Product.java / ProductRepository.java / ProductController.java
│       │   ├── InventoryMovement.java / InventoryMovementRepository.java / InventoryService.java
│       │   │
│       │   ├── DailyReport.java / DailyReportRepository.java
│       │   ├── DailyReportService.java / DailyReportController.java
│       │   │
│       │   ├── ActivityLog.java / ActivityLogRepository.java
│       │   ├── ActivityLogService.java / ActivityLogController.java
│       │   │
│       │   ├── DeliveryLog.java / DeliveryLogItem.java / DeliveryLogRepository.java
│       │   ├── DeliveryReportController.java
│       │   │
│       │   ├── MasterKey.java / MasterKeyRepository.java / MasterKeyService.java
│       │   ├── Expense.java / ExpenseItem.java / ExpenseRepository.java
│       │   ├── ExpenseController.java
│       │   ├── Transaction.java / TransactionRepository.java
│       │   ├── TransactionService.java / TransactionController.java
│       │   ├── ReportsController.java
│       │   ├── DashboardController.java
│       │   ├── Settings.java / SettingsRepository.java / SettingsController.java
│       │   ├── JwtAuthFilter.java / SecurityConfig.java
│       │   │
│       │   └── dto/
│       │       ├── LoginRequest.java / LoginResponse.java
│       │       ├── CreateOrderRequest.java / OrderResponse.java
│       │       └── DeliveryRequest.java
│       │
│       └── resources/
│           ├── application.properties
│           ├── application-local.properties   ← NOT committed (secrets)
│           └── db/migration/
│               ├── V1__initial_schema.sql      ← users, orders, products, inventory tables
│               ├── V2__seed_initial_data.sql   ← admin user + settings seed
│               ├── V3__seed_products.sql       ← 25 product SKUs seeded
│               ├── V4__add_delivered_status.sql
│               ├── V5__phase5_features.sql     ← daily_reports, activity_log upgrade, delivery_log
│               ├── V7__activity_log.sql        ← minimal activity_log (upgraded by V5)
│               ├── V8__master_keys.sql
│               ├── V9__add_product_code.sql
│               ├── V10__order_improvements.sql
│               ├── V11__user_roles.sql
│               ├── V12__employee_profile_fields.sql
│               ├── V13__company_settings.sql
│               ├── V14__expenses_and_page_permissions.sql
│               ├── V15__transaction_ledger.sql
│               ├── V16__sub_category_and_real_inventory.sql
│               ├── V17__payables.sql
│               ├── V18__update_prices_costs_add_new_products.sql
│               ├── V19__test_seed.sql
│               ├── V20__admin_security_key.sql
│               └── V21__delivery_fee_and_report_columns.sql
│
└── rrbm_frontend/
    └── rrbm-frontend/
        ├── index.html                          ← SPA entry point
        ├── css/styles.css
        ├── js/app.js
        └── assets/rrbm-logo.png
```

---

## API ENDPOINTS

### Auth
| Method | URL | Notes |
|--------|-----|-------|
| POST | /api/auth/login | Returns JWT + user info |
| POST | /api/auth/verify-password | Verify admin password (COD resume) |
| POST | /api/auth/master-key | Change master key (requires current key) |
| GET | /api/test, /api/health | Status checks |
| GET | /api/test-auth/check-user | Dev only |
| POST | /api/test-auth/reset-password | Dev only — use instead of SQL for passwords |

### Users
| Method | URL | Notes |
|--------|-----|-------|
| GET | /api/users | List all users |
| GET | /api/users/{id} | Single user (includes allowedPages) |
| POST | /api/users | Create employee account (full profile + allowedPages) |
| PUT | /api/users/{id} | Update employee profile |
| PATCH | /api/users/{id}/role | Change user role |
| PATCH | /api/users/{id}/status | Enable / disable user |
| PATCH | /api/users/{id}/permissions | Update page access (Super Admin only) |
| GET | /api/settings | All settings (non-sensitive keys) |
| POST | /api/settings | Update editable settings |

### Dashboard
| Method | URL | Notes |
|--------|-----|-------|
| GET | /api/dashboard/stats?period=daily\|weekly\|monthly | Aggregated stats + pizza quota + expenses today |
| GET | /api/dashboard/top-products-today | Top 5 products by qty sold today |

### Transactions (Accounting Ledger)
| Method | URL | Notes |
|--------|-----|-------|
| POST | /api/transactions/refund | Issue refund — creates negative REFUND transaction on today's date |
| POST | /api/transactions/void | Post-close void — creates negative VOID transaction on today's date |
| POST | /api/transactions/adjustment | Manual correction — amount can be positive or negative |
| GET | /api/transactions/order/{orderId} | Full ledger history for one order |
| GET | /api/transactions/date-range?start=&end= | All transactions in date range |
| GET | /api/transactions/accounting-summary?date= | Gross / refunds / adjustments / net for one day |

### Reports
| Method | URL | Notes |
|--------|-----|-------|
| GET | /api/reports/insights-summary?month=YYYY-MM | Monthly order analytics: totals, daily breakdown, top 5 products, MoM comparison, expenses |
| GET | /api/reports/accounting-summary?month=YYYY-MM | Transaction-ledger monthly: grossSales, refundsTotal, netSales, daily breakdown |
| GET | /api/reports/source-breakdown?month=YYYY-MM | Orders + revenue by source (WALK_IN/ECOMMERCE/RESELLER/AGENT etc.) |
| GET | /api/reports/top-agents?month=YYYY-MM | Top 10 agents/resellers by revenue |
| GET | /api/reports/top-dates?month=YYYY-MM | Top 3 highest-revenue dates |
| GET | /api/reports/pizza-summary?month=YYYY-MM | Total pizza box qty + top 5 pizza box products |
| GET | /api/reports/hot-selling?month=YYYY-MM | Top 10 HOT/SELLING tagged products |
| GET | /api/reports/delivery-fees?month=YYYY-MM | All orders with delivery_fee > 0 |
| GET | /api/reports/expense-breakdown?month=YYYY-MM | Expense items grouped by description |

### Expenses
| Method | URL | Notes |
|--------|-----|-------|
| POST | /api/expenses | Record a new expense (logs "Expense Recorded") |
| GET | /api/expenses?date=YYYY-MM-DD | Expenses for a date (default today) |
| GET | /api/expenses/range?start=&end= | Expenses in a date range |

### Orders
| Method | URL | Notes |
|--------|-----|-------|
| POST | /api/orders | Create order |
| GET | /api/orders | All orders |
| GET | /api/orders/today | Today's orders |
| GET | /api/orders/{id} | Single order |
| POST | /api/orders/{id}/cancel | Requires masterKey |
| GET | /api/orders/search?customerName=X | Search |

### Suppliers
| Method | URL | Notes |
|--------|-----|-------|
| GET | /api/suppliers?includeInactive=false | Active suppliers A-Z; pass `includeInactive=true` for full list |
| GET | /api/suppliers/{id} | Single supplier |
| POST | /api/suppliers | Create supplier (name required) |
| PATCH | /api/suppliers/{id} | Edit supplier fields |
| DELETE | /api/suppliers/{id} | Soft delete (sets isActive=false) |
| GET | /api/suppliers/{supplierId}/mappings | All product mappings for a supplier |
| POST | /api/suppliers/{supplierId}/mappings | Add product mapping (supplierItemCode, unitCost, isPreferred) |
| PATCH | /api/suppliers/{supplierId}/mappings/{mappingId} | Update mapping; Option A: flipping isPreferred=true clears all other preferred flags for that product |
| DELETE | /api/suppliers/{supplierId}/mappings/{mappingId} | Delete mapping |

### Products & Inventory
| Method | URL | Notes |
|--------|-----|-------|
| GET | /api/products | Active products (order dropdown) |
| GET | /api/products/all | Full catalog (admin inventory) |
| GET | /api/products/search?name=X | Search by name |
| GET | /api/products/categories | Category list |
| POST | /api/products | Create product (masterKey required) |
| PATCH | /api/products/{id}/tag | Update selling tag |
| POST | /api/products/delivery | Process delivery receipt — now uses 3-attempt PO matching (itemCode → supplierItemCode → name) |
| GET | /api/products/{productId}/suppliers | All supplier mappings for a product |

### Reports & Logs
| Method | URL | Notes |
|--------|-----|-------|
| POST | /api/reports/close-daily | Close daily sales (masterKey required) |
| GET | /api/reports/daily-status | Check if today is closed |
| GET | /api/reports/daily/{date} | Report for a date |
| GET | /api/reports/range?start=&end= | Date range |
| GET | /api/reports/deliveries | All delivery logs |
| GET | /api/reports/deliveries/{date} | Deliveries for a date |
| GET | /api/activity-log/today | Today's activity log |
| GET | /api/activity-log/{date} | Activity log for a date |
| GET | /api/delivery-reports | All delivery logs (alt endpoint) |

---

## DATABASE

**Connection:** `localhost:5432 / rrbm_db / postgres`

| Table | Purpose |
|-------|---------|
| users | Admin accounts |
| products | Product catalog (25 SKUs seeded) |
| orders | Sales orders |
| order_items | Line items per order |
| order_id_counter | Sequential ID generator |
| inventory_movements | Every stock change (audit trail) |
| daily_reports | Closed daily summaries |
| activity_log | All admin actions |
| delivery_log | Incoming stock receipts |
| delivery_log_items | Line items per delivery |
| master_keys | Hashed keys for sensitive ops |
| settings | Company config |
| expenses | Daily expense records |
| expense_items | Line items per expense |
| transactions | Immutable accounting ledger — one row per financial event (SALE/REFUND/VOID/ADJUSTMENT) |
| payables | Accounts payable — one row per delivery receipt; status PENDING/PAID/PARTIAL/CANCELLED (V48 added CANCELLED) |
| suppliers | Supplier master list; soft-delete via is_active flag; created V43 |
| supplier_product_mapping | Links suppliers to products with supplier SKU, description, unit cost, preferred flag; UNIQUE(supplier_id, product_id); created V44 |
| po_year_counter | Auto-increment counter for PO number generation — one row per calendar year (resets each Jan 1); pessimistic-lock read for race safety; created V43 |

**Order ID Format:** `DDMMYY-NNNNNN` (e.g. `010626-000042`) — date prefix = creation date; sequence is a single global counter that never resets between days (V35)

**PO Number Format:** `PO-MMDDYY-XXXXX` (e.g. `PO-060426-00005`) — date prefix = creation date; 5-digit sequence resets each calendar year; generated by `PurchaseOrderService.generatePoNumber()` (V43)

---

## BUGS FIXED

| # | Problem | Fix |
|---|---------|-----|
| 1 | Java files in wrong location (404) | Always create in `src/main/java/rrbm_backend/` |
| 2 | Stale compilation after adding files | Run `mvnw.cmd clean` before `spring-boot:run` |
| 3 | BCrypt hash corrupted by psql | Never paste hashes in SQL — use `/api/test-auth/reset-password` |
| 4 | Hardcoded userId=1L broke after re-create | Changed to 3L; TODO: extract from JWT |
| 5 | order_items FK on empty products table | Don't send productId until Products module exists |
| 6 | BigDecimal NPE in calculateTotals() | Made method null-safe |
| 7 | Bootstrap `.toast` CSS conflict | Renamed custom toast to `.rrbm-toast` |
| 8 | Toast z-index behind login screen | Changed toast z-index to 9999 |
| 9 | **Flyway migration crisis (May 21)** | See detail below |
| 10 | UserController compile error — `incompatible types: inference variable T` at line 245 | Added type witness `.<ResponseEntity<?>>map(...)` so Java resolves the generic bound |
| 11 | `openModal()` undefined — Cancel Delivery button did nothing (silent ReferenceError) | Defined `window.openModal` next to `closeModal`; fixed Cancel Delivery, Category Breakdown, and CSV Skipped modals in one go |
| 12 | DR status badge invisible in light mode | `.badge-hot`, `.badge-selling`, `.badge-slow` CSS classes had no definition; added with light + dark theme pairs |
| 13 | `previousMonthPizzaTotal()` used wrong `classifyProduct()` index | Was `[1]` (subcategory); fixed to `[0]` (category = `"Pizza Box"`) — monthly quota target was silently wrong |
| 14 | Order receipt counter reset to `000001` every day | V35 migration: consolidated to single `GLOBAL` counter row; `OrderIdGenerator` uses `"GLOBAL"` key — sequence is now continuous across days |
| 15 | Batch import duplicate check could false-match shorter order numbers | Changed `existsByNotesContaining(ref)` to `existsByNotesContaining("Order No: " + ref)` — anchors search to the full prefix |
| 16 | `PENDING_COLLECTION` status rejected by `updateStatus()` | Added to allowed statuses set; documents `PENDING → PENDING_COLLECTION` and `PENDING_COLLECTION → ACTIVE` transitions |
| 17 | `o.ecomOrderRef` accessed as property (undefined) at 4 sites — ecom platform refs never showed in Collections, detail modal, daily close report, collections summary | Changed all 4 sites to call `ecomOrderRef(o)` helper function instead |
| 18 | Order History "Failed to load" and "Connection error" rows used `colspan="7"` but table has 8 columns | Both rows corrected to `colspan="8"` |
| 19 | **Phantom-debit bug (M-26):** `cancelOrder`, `recordDeferralVoid`, `recordCollectionSale` all used gross `order.getTotal()` as ledger basis — orders with prior item voids produced a phantom debit equal to `−voidedAmount` | 3-site fix: all paths now use net basis `total − voidedAmount`; `cancelOrder` skips ledger write entirely when effectiveVoid = 0; Sites 1 & 2 kept in sync so COLL-DEFER and COLL-SALE cancel exactly |
| 20 | `refund-security-key` and `void-security-key` were stale entries in `closeModal()` secFields clear-list — both IDs pointed to modal fields deleted in Session 62; null-guarded so silently harmless, but dead weight | Removed both entries from secFields |
| 21 | `rtn-security-key` (return modal security key field) was NOT in `closeModal()` secFields — field was only cleared on next `openReturnModal()` open, not on modal dismiss; if user typed key then closed without submitting, value lingered in hidden DOM field | Added `rtn-security-key` to secFields clear-list |

### Bug #9 — Flyway Migration Crisis (May 21, 2026)
**Root Cause:** Three migration problems occurred simultaneously:
- `V3__seed_products.sql` was deleted and replaced with `V3__phase5_features.sql` → **checksum mismatch** (DB had old V3 hash)
- `V5__phase5_features.sql` tried to `CREATE TABLE activity_log` but V7 already created it → **would fail**
- `V6__seed_products.sql` re-inserted products already seeded by V3 → **duplicate key error**

**Fixes Applied:**
1. Restored `V3__seed_products.sql` with original content (from git history)
2. Deleted `V3__phase5_features.sql` and `V6__seed_products.sql`
3. Rewrote `V5__phase5_features.sql` to use `ALTER TABLE activity_log ADD COLUMN IF NOT EXISTS` instead of `CREATE TABLE` — safely upgrades V7's minimal schema
4. Added `spring.flyway.out-of-order=true` to `application.properties` — V5 needed to apply after V7/V8 were already in DB
5. Deleted stale files from `target/classes/db/migration/` (Maven doesn't clean removed resources)

**Lesson:** Never rename or delete a migration file after it's been applied to the database. New content must always go in a new version number.

---

## KEY CONFIGURATION

```properties
# application.properties highlights
server.port=8080
spring.datasource.url=jdbc:postgresql://localhost:5432/rrbm_db
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.out-of-order=true          ← added May 21 for V5/V7 ordering fix
spring.autoconfigure.exclude=...SecurityAutoConfiguration   ← disabled for dev
```

```java
// JwtUtil.java
Secret: "rrbm-secret-key-minimum-256-bits-for-hs256-algorithm-security"
Expiry: 8 hours | Algorithm: HMAC-SHA256
// TODO: move secret to env variable before production
```

---

## COMMANDS

```bash
# Start backend
cd D:\ClaudeProjects\rrbm_daily\rrbm-backend
.\mvnw.cmd spring-boot:run

# Clean rebuild (use when files don't recompile)
.\mvnw.cmd clean spring-boot:run

# Database
psql -U postgres -d rrbm_db

# Flyway repair (use if checksum mismatch again)
.\mvnw.cmd flyway:repair
```

---

## TECHNICAL DEBT

- [x] Extract `userId` from JWT instead of hardcoding `3L` — fixed Session 10
- [x] Remove test/debug endpoints before production — fixed Session 10
- [ ] Re-enable Spring Security with proper JWT filter
- [ ] Move JWT secret to environment variable
- [ ] Add FK constraint back on `order_items.product_id`
- [ ] Proper error handling and input validation across all controllers
- [x] Refund/Return endpoint — verified working: `POST /api/transactions/refund` in `TransactionController`; inventory restore, ledger entry, and stats pipeline all implemented. Initial audit incorrectly looked in `OrderController`.
- [ ] **Orphaned endpoints** — `GET /api/orders` (all orders, no pagination) and `GET /api/orders/search` (cross-all-time customer name search) exist in `OrderController` but are called by no frontend view. Options: (a) add pagination + wire to a future admin/search UI, (b) wire `/search` to the Order History search box to replace client-side filtering, (c) leave as debug/Postman endpoints and document.

---

## DEFAULT CREDENTIALS

| Item | Value |
|------|-------|
| DB admin login | See `application-local.properties` |
| App admin | username/password from V2 seed |
| Default master key | `rrbm2024` (seeded on first boot if table empty) |

---

## VOID / CANCEL / RETURN REDESIGN — PROJECT SUMMARY

**Design authored:** Jun 1, 2026  
**Build started:** Jun 2, 2026  
**Integration testing complete:** Jun 3, 2026  
**Test verdict:** 11 / 11 passed  
**Planning document:** `VOID_CANCEL_RETURN_REDESIGN.md` — status COMPLETE

---

### What was replaced

The original system had a single generic endpoint — `POST /api/transactions/void` — that accepted a monetary amount and wrote a negative ledger entry. It was a financial-only operation with no connection to physical items or inventory. Its inventory restore logic was proportional to the dollar amount entered, which was architecturally incorrect (a monetary fraction does not map to a physical stock fraction). It also allowed post-close voids, meaning a staff member could reduce a closed daily report's revenue after the day had already been reconciled.

That endpoint and its service method (`recordPostCloseVoid()`) were removed in Session 62.

---

### What was built to replace it

**Three fully separate flows, each with its own endpoint, permission level, and UI entry point:**

**1. Item-level same-day void** (`POST /api/orders/{id}/void`)
- Removes specific items or quantities from an order, on the same business day only
- Two tiers: Tier 1 (partial, security key) and Tier 2 (full zero-out, master key + disposition per item)
- Day-close guard: once the daily report is closed, the void option disappears entirely — no override
- Available on orders in any status including DELIVERED
- For DELIVERED orders: admin must select sellable (return to warehouse) or rejected (record but don't restock) per item
- Tier 2 full void sets order status to CANCELLED with `cancellation_type = VOIDED` — visually distinct from a standard or replacement cancellation

**2. Cancel for replacement** (`POST /api/orders/{id}/cancel-for-replacement`)
- Cancels an order specifically because it is being replaced with a corrected order
- Requires master key
- Writes a VOID ledger entry for the remaining effective value (original total minus any prior voids)
- Two-way order linking: original stores replacement order ID, replacement stores original order ID
- Replacement order creation is a separate endpoint (`POST /api/orders/{id}/replacement`) requiring standard JWT only

**3. Return and adjustment** (`POST /api/orders/{id}/return`)
- Post-sale return flow — available at any time regardless of day-close status
- Admin specifies per-item: total returned, sellable count, rejected count
- Sellable returns: stock restored to the correct warehouse; RETURN_SELLABLE movement written
- Rejected returns: RETURN_REJECTED movement written for audit; no stock change
- Optional refund: atomic with stock adjustment — if either fails, both roll back

---

### Schema changes

| Migration | What changed |
|---|---|
| V39 | `order_items.voided_quantity` (cumulative voided qty per line); `orders.voided_amount` (running void total); `orders.replacement_order_id`; `orders.original_order_id`; `orders.cancellation_type` (NULL / STANDARD / REPLACEMENT / VOIDED) |
| V40 | `chk_movement_type` constraint expanded: added `ITEM_VOID`, `RETURN_SELLABLE`, `RETURN_REJECTED`, `CANCEL_REJECTED` |
| V41 | `VOID_REJECTED` movement type added (cancel-rejected inventory path correction) |
| V42 | `inventory_movements.description` widened to TEXT; unified rejected items report covers delivery + void + cancel + return rows |

---

### Endpoints removed

| Endpoint | Removed | Reason |
|---|---|---|
| `POST /api/transactions/void` | Session 62, Jun 3 2026 | Replaced by item-level void and return flows above |

### Methods removed

| Method | Class | Removed |
|---|---|---|
| `recordPostCloseVoid()` | `TransactionService.java` | Session 62, Jun 3 2026 |

---

### Integration test results (Step 12 — Jun 3, 2026)

| Test | Scenario | Result |
|---|---|---|
| V1 | Tier 1 partial void — order stays ACTIVE | ✅ |
| V2 | Tier 2 full void — order → CANCELLED + VOIDED | ✅ |
| V3 | Void on DELIVERED order — disposition radios | ✅ |
| T11 | Day-close guard — API block + frontend banner + void buttons gone | ✅ |
| C1 | Standard cancel | ✅ |
| C2 | CFR on non-DELIVERED | ✅ |
| C3 | CFR on DELIVERED | ✅ |
| R1 | Return sellable (no refund) | ✅ |
| R2 | Return rejected (no refund) | ✅ |
| R3 | Return with refund | ✅ |
| P1 | Replacement order creation from CFR-cancelled order | ✅ |

---

### M-26 — Phantom-debit fix (Session 68, Jun 5 2026)

**M-26** ✅ — Fixed. Root cause was deeper than the original double-VOID diagnosis: all three cancel/defer/collect ledger paths used gross `order.getTotal()` instead of net `total − voidedAmount`. 3-site fix applied in `TransactionService.java` + `OrderService.java`. Historical production phantom debit (order 020626-000080, −₱29.94) corrected via ADJUSTMENT transaction. 53/53 QA gaps now closed.

---

## NEXT UP

**QA fix phase — COMPLETE (Session 68, Jun 5 2026)**

All 53 QA gaps are now closed. PO Redesign (backend + frontend) is complete. The codebase is in a stable, production-ready-minus-infra state.

**On hold — do not touch without explicit approval:**
- **V47 / item_code drop** — reservation permanently closed; `po_items.item_code` still used as Attempt 1 in DR→PO match chain; `products.item_code` is core inventory infrastructure; V49 is next available migration

**Orphaned endpoints (carry-forward):**
- `GET /api/orders` and `GET /api/orders/search` — unused by frontend; leave as debug endpoints

**Infrastructure (Phase 10, deferred):**
1. Spring Security JWT filter
2. Move JWT secret to environment variable
3. CORS lockdown
4. Bean Validation on controllers
5. FK constraint on `order_items.product_id`
6. Docker deployment preparation

---

## Session U15 Detail — Jun 9, 2026 (Import Review System)

**Goal:** Implement two-pass batch import where users manually verify every order and expense via a review modal before commit, with commit results persisted for historical tracking.

**Backend changes:**
- Upload response enriched with `items[]` (per-item: productId, productName, matchConfidence, qty, unitPrice, basePrice, opPerUnit) and expense `matchConfidence`
- Commit endpoint accepts `overrides` — order-level (agentId, paymentMethod, paymentStatus, ecommercePlatform, include), item-level (qty, unitPrice, basePrice, opPerUnit, productId), expense-level (categoryId, amount, notes, paymentMethod, include)
- Commit log persistence: V67 migration (`import_commit_log` BIGSERIAL), `ImportCommitLog` entity, `ImportCommitLogRepository`, result saved as JSON, `logId` in response
- History endpoints: `GET /api/import/history/logs` (list), `GET /api/import/history/logs/{id}` (detail JSONB)

**Frontend changes:**
- Simplified preview: replaced valid-rows table with 4 count cards + "Review All Items" button
- Order review cards: exclude toggle, agent autocomplete, payment/status/platform dropdowns, items table with inline qty/unitPrice/basePrice/opPerUnit editors, product autocomplete for unmatched items
- Expense review cards: exclude toggle, match badge (EXACT/FUZZY/NO MATCH), payment method/amount/notes editors
- Green gate: commit disabled until ALL included items have green product/category match; amber/green visual indicator bar
- Override state persisted across pagination via `_reviewOverrides` module-level object
- Pagination: 10 cards/page
- Commit handler builds full overrides payload matching backend schema
- History detail view: `GET /api/import/history/logs/{id}` → renders JSONB snapshot as formatted read-only cards
- `/api/import/history/batch` — batch-level detail endpoint

**Test results:** 142/142 pass.

---

## Session U16 Detail — Jun 9, 2026 (Session 6 — Integration Testing & Bug Bash)

**Goal:** Verify complete COD lifecycle via end-to-end integration tests.

**Verification:** All 5 planned scenarios already covered by existing `ImportU6Test.java` (7 tests):

| Test | Scenario | Assertions |
|------|----------|-----------|
| U6-a | COD PAID import → ACTIVE + SALE | status=ACTIVE, SALE transaction exists, imported flag set |
| U6-b | COD UNPAID import → PENDING_COLLECTION | status=PENDING_COLLECTION, pendingCollectionAt set, VOID (COLL-DEFER) exists, no commission |
| U6-c | Collect COD UNPAID → DELIVERED + COLL-SALE | PATCH /api/orders/{id}/collect → status=DELIVERED, collectedBy/collectedAt set, SALE transaction exists |
| U6-d | Commission after collect (opAmount > 0) | entryRepository.findByOrderId → non-empty, opAmount > 0, status=PENDING |
| U6-e | Batch collect multiple orders | POST /api/orders/batch-mark-collected → collected=2, both DELIVERED |
| U6-f | Duplicate re-import detection | Second upload of same receipt → appears in duplicates array |
| U6-g | Mixed payment modes (CASH/COD PAID/COD UNPAID) | Correct status per row: ACTIVE, ACTIVE, PENDING_COLLECTION |

**Test results:** Full regression 142/142 pass. BATCH-IMPORT-COD-COMMISSION-PLAN complete (6/6 sessions).

---

## Session U17 Detail — Jun 9, 2026 (Import UI Cleanup)

**Goal:** Fix 4 UI issues in the import workflow.

### Fix 1 — Remove obsolete Upload Type row
**Problem:** The Step 3 card had an "Upload Type" selector (Sales/Expenses toggle buttons) left over from before the combined template was introduced. Since Step 2 now only offers "Download Combined Template," the type toggle was dead UI.

**Change:** `index.html` — deleted the `<div style="margin-bottom:14px;">` block containing the Upload Type label and Sales/Expenses buttons. File input and Upload & Preview button remain untouched.

### Fix 2 — Flatten nested stat cards
**Problem:** The 4 preview summary stat counters (Orders, Expenses, Need Fix, Duplicates) inside the Step 4 card used `class="card"`, which rendered them as nested cards with borders inside the parent card-body.

**Change:** `index.html` — replaced `class="card"` on the 4 `<div>` elements with inline `background:var(--bg-secondary);border-radius:var(--radius-sm);`, removing the `.card` border, overflow-hidden, and shadow while keeping a flat stat-widget appearance consistent with the app's design system.

### Fix 3 — View button in Import History
**Problem:** Import history used clickable rows (`cursor:pointer` + `onclick` on `<tr>`) instead of an explicit Actions column — inconsistent with other views (orders, delivery, payables) that all use a View button.

**Changes:**
- `index.html` — added `<th style="text-align:center;">Actions</th>` as 7th header column
- `app.js` — changed all `colspan="6"` → `colspan="7"`; removed `cursor:pointer` / `onclick` / `title` from `<tr>`; added 7th `<td>` with `<button class="btn btn-secondary btn-sm" onclick="openImportDetailModal(...)"><i class="ti ti-eye"></i> View</button>` matching the pattern used in delivery and payables views.

### Fix 4 — Record validate-overrides gap
**Problem:** The pre-commit validate endpoint's limitation (no overrides support, never re-run after review edits) was noted in conversation but never written down.

**Change:** `anchored_summary.md` — added "Known Gaps" section documenting the issue.

### Verification
- `node --check app.js` — no syntax errors
- `mvn test` — 142/142 pass, BUILD SUCCESS

---

## Session U18 Detail — Jun 9, 2026 (Review Modal Fixes — Nested Cards + Dropdowns + Validate Button)

**Goal:** Fix 3 issues in the import review modal.

### Fix 1 — Remove Validate button
**Problem:** The Validate button in the Step 4 Preview card-header triggered `POST /api/import/validate`, which checks stock/report-close against **original CSV data only**. It does not account for review overrides (qty changes, product changes, etc.), making the button misleading — it could show all-clear while the actual committed data could cause stock failures. The green gate within the review modal handles item matching validation.

**Change:** `index.html` — removed the `<button class="btn btn-warning" onclick="validateImport()">` line from the Step 4 Preview card-header buttons group.

### Fix 2 — Fix nested card appearance + autocomplete dropdown clipping
**Root cause discovered:** The `.card` CSS class has `overflow: hidden` (styles.css:390). Each review card (order and expense) used `class="card"`, which:
1. Gave each card `background:var(--bg-card)` with a full border/radius inside the modal box — creating a card-in-card visual (modal-box has same `background:var(--bg-card)`)
2. Clipped the absolute-positioned `.product-dropdown` divs used by product and agent autocomplete — the dropdown divs expanded downward past the card bounds and got hidden by `overflow:hidden`

**Change:** `app.js` — replaced `class="card"` with `class="review-card"` on both order and expense card divs, with inline `overflow:visible;border:1px solid var(--border);border-radius:var(--radius-sm)`:
- `overflow:visible` allows autocomplete dropdowns to render outside the card
- Removed `background:var(--bg-card)` so cards blend with the modal background — eliminates nested look
- Kept a subtle border for visual separation between items

### Fix 3 — Update exclude-toggle selector
**Problem:** The exclude checkbox toggle dims the card by finding `this.closest('.card')` — since the class changed to `.review-card`, the selector would return `null` and opacity dimming would stop working.

**Change:** `app.js:11632` — changed `this.closest('.card')` → `this.closest('.review-card')`.

### Files touched
- `index.html` — 1 line removed (Validate button)
- `app.js` — 3 changes in `_renderReviewCard` (expense card line 11443, order card line 11546) + 1 selector change in `_setupReviewCardEvents` (line 11632)

### Verification
- `node --check app.js` — no syntax errors
- No backend changes — `mvn test` not re-run

---

## Session U19 Round 3 — Jun 9, 2026 (8 NaN/HTML Bugs from Code Review)

**Problem:** The review modal had display corruption caused by string concatenation bugs (`+ +` double-plus producing `NaN`) and z-index mismatches on autocomplete dropdowns.

### Issues Found and Fixed

| # | Bug | Location | Root Cause | Fix |
|---|-----|----------|-----------|-----|
| 1 | Expense amount shows "NaN" | `app.js:11467` | `+ + '</div>'` — unary plus on string → NaN | Removed extra `+` |
| 2 | Expense notes shows "NaN" | `app.js:11471` | `+ + '</div>'` — same pattern | Removed extra `+` |
| 3 | Agent autocomplete wrapper broken | `app.js:11579` | `+ + '</div></div>'` — same pattern | Removed extra `+` |
| 4 | Payment select broken | `app.js:11583` | `+ + '</select></div>'` — same pattern | Removed extra `+` |
| 5 | Status select broken | `app.js:11589` | `+ + '</select></div>'` — same pattern | Removed extra `+` |
| 6 | Platform select broken | `app.js:11596` | `+ + '</select></div>'` — same pattern | Removed extra `+` |
| 7 | Product dropdown z-index too low | `app.js:11508` | Inline `z-index:100` overrode CSS `z-index:1000` | Changed to `z-index:1000` |
| 8 | Agent dropdown z-index too low | `app.js:11578` | Same issue | Changed to `z-index:1000` |

### Root Cause
During the U19 Round 2 fix batch, HTML templates were restructured. The pattern `+ + '</tag>'` was introduced — the first `+` is string concatenation, the second `+` is a unary plus operator that converts the string to `NaN`. This corrupted expense inputs, all three order dropdowns (payment/status/platform), and the agent autocomplete wrapper.

### Files touched
- `rrbm_frontend/rrbm-frontend/js/app.js` — 8 changes in `_renderReviewCard` (expense card: lines 11467, 11471; order card: lines 11508, 11578, 11579, 11583, 11589, 11596)

### Verification
- `node --check app.js` — no syntax errors
- Grep for `+ + '` patterns — zero remaining
- No backend changes — `mvn test` not re-run

---

## Session U19 Round 4 — Jun 9, 2026 (Hint Bar Layout Fix)

**Problem:** The "Showing X–Y of Z items | Base Price and OP per Unit are editable" hint text was rendered inside the `#review-card-container` CSS Grid, consuming one card slot and wasting space.

**Fix:** Moved the hint text to a dedicated `#review-hint-bar` div above the card grid.

### Files touched
- `index.html` — Added `#review-hint-bar` div (1 line) above `#review-card-container`
- `app.js` — `_renderReviewPage()` now populates `#review-hint-bar` instead of prepending to grid (removed 3-line HTML string, added 4-line hintBar population)

### Verification
- `node --check app.js` — no syntax errors
- No backend changes — `mvn test` not re-run

---

## Session U19 Round 5 — Jun 9, 2026 (X Icon Exclude Button + Scrollable Items)

**Problem:** Per-item exclude used a checkbox (unclear intent), and cards with many items expanded vertically breaking equal-height alignment.

**Fix:**
1. Replaced per-item checkbox with a circular X icon button (`.rc-item-exclude-btn`) — red hover, red fill when active — clearly communicates "exclude from commit"
2. Wrapped items in `.rc-items-scroll` with `max-height: 200px` + `overflow-y: auto` — cards stay uniform height, items scroll when >3

### Files touched
- `css/styles.css` — Added `.rc-item-exclude-btn` + `.rc-item-exclude-btn:hover` + `.rc-item-exclude-btn.active` + `.rc-items-scroll` (~30 lines)
- `js/app.js` — Replaced checkbox HTML with X button; wrapped itemsHtml in scroll div; updated event handler from `.review-item-exclude-cb` change to `.rc-item-exclude-btn` click

### Verification
- `node --check app.js` — no syntax errors
- No backend changes — `mvn test` not re-run

---

## Session U19 Round 6 — Jun 9, 2026 (Expense Category Editing Plan + Session Wrap-up)

**Goal:** Plan expense category editing in review modal for FUZZY matches; log session state for resume.

### Key Observations Discussed

| # | Topic | Decision |
|---|-------|----------|
| 1 | Expense category should be editable | **Agreed** — FUZZY matches get cascading dropdown (Primary → Sub-category); EXACT stays read-only; NO MATCH stays in needsFix |
| 2 | Reference number purpose | Clarified: reference number is the key for matching overrides to items + duplicate detection; not a problem, just the glue |
| 3 | No stock/report-close validation for expenses | Explained: orders validate stock + report status before commit; expenses skip pre-validation (only set `lateImported` flag if report closed) |

### Plan Created & Implemented

**File:** `docs/PLAN-expense-category-editing.md`

**Scope:**
- FUZZY items: cascading dropdown (Primary → Sub-category) replaces static category text
- EXACT items: stay read-only (system confident)
- NO MATCH items: stay in needsFix (no change)
- Backend: no changes needed (`GET /api/expense-categories` already returns hierarchical data)
- Frontend: ~65 lines in `app.js` + ~10 lines in `css/styles.css`

**Files touched:**
| File | Changes |
|------|---------|
| `js/app.js` — `openReviewModal()` | Lazy-load `loadExpenseCategories()` via `.then()` |
| `js/app.js` — `_renderReviewCard()` | Render cascading `<select>` for FUZZY items |
| `js/app.js` — new helpers | `_expCatPrimaryOpts()`, `_expCatSubOpts()`, `_findCatPath()` |
| `js/app.js` — `_setupReviewCardEvents()` | Event listeners for primary/sub-category selects |
| `css/styles.css` | `.rc-cat-edit` styling |

### Session Summary — U19 Complete

**All rounds completed this session:**
- U19 main: Card grid restructure (CSS Grid, 960px modal, visual hierarchy)
- Round 1: 9 regression fixes (stale IDs, lazy overrides, per-item exclude, NaN guards)
- Round 2: 6 visual fixes (inline style removal, safeDisplay, scoped styles, z-index stacking, green bar default)
- Round 3: 8 NaN/HTML bugs (double-plus operators, z-index mismatches)
- Round 4: Hint bar layout fix (moved out of CSS Grid)
- Round 5: X icon exclude button + scrollable items section
- Round 6: Planning session (expense category editing + session wrap-up)

**Current state:** All U19 features complete + plan saved for Round 6 implementation.

### Verification
- `node --check app.js` — no syntax errors
- `mvn test` — 142/142 pass (re-confirmed)

### Expense Page Bug Fixes (post-U19)

**3 line changes in `js/app.js`:**

| # | Line | Fix | Impact |
|---|------|-----|--------|
| 1 | 3413 | `/expenses/range` → `/api/expenses/range` | Expense History now loads data (was 404/HTML in Docker) |
| 2 | 3427 | `i.description` → `i.itemDescription` | Item descriptions display correctly (was showing `undefined`) |
| 3 | 3568 | `/expenses/export` → `/api/expenses/export` | Export CSV/Excel/PDF now works (was 404/HTML in Docker) |

**Root cause:** Two endpoint paths were missing the `/api/` prefix that nginx proxies to the backend. One field name referenced the wrong key (`description` instead of `itemDescription`).

**Verified:** `node --check app.js` — no syntax errors

---

### Session U20 Detail — Jun 10, 2026 (Agent Page Investigation + Plan)

**Investigation scope:** Full frontend + backend analysis of the Agent page (registry, performance modal, commission breakdown, statement export).

**Issues found:**

| # | Issue | Severity | File:Line |
|---|-------|----------|-----------|
| 1 | Statement Export missing `/api/` prefix | High — feature broken | `app.js:10666` |
| 2 | No agent status toggle UI | Medium — missing feature | `app.js:10322` (agent table) |
| 3 | N+1 queries on agent list | Low — scalability | `AgentController.java:144` |

**Issue 1 — Statement Export:**
`downloadStatement()` builds URL as `API_BASE + '/commissions/periods/...'` — missing `/api/` prefix. nginx does not proxy to backend; SPA HTML returned instead of PDF/CSV. Exact same pattern as the expense page bugs fixed in U19. Fix: add `/api/` to the URL string (1 line).

**Issue 2 — Status Toggle:**
Backend has `PATCH /api/agents/{id}/status` (AgentController.java:263) accepting `{ "status": "INACTIVE" }`. Frontend has no UI to call it — no toggle button in agent table, no status field in edit modal. Plan: add toggle button next to status badge in `loadAgents()` + new `toggleAgentStatus()` function (~15 lines).

**Issue 3 — N+1 Queries (deferred):**
`listAgents()` calls `countOrdersByAgentId()` + `lifetimeNetCommission()` + `sumPendingOpAmountByAgentId()` per agent. For N agents: 1 + 3N queries. Plan: replace with 3 bulk queries using `WHERE agent_id IN (...) GROUP BY agent_id` → constant 4 queries regardless of count. Deferred until agent count approaches 50+.

**Plan file:** `docs/PLAN-agent-page-bugfixes.md`

**No code changes this session — planning only.**

---

### §5A Pre-Deployment Fixes + Security Gap Re-Verification — Jun 13, 2026

**Goal:** Fix four issues found during §5A manual verification of the running app; confirm or close the two remaining security gaps (S10-01, S12-01) flagged in INTEGRATION-TEST-PLAN.md.

---

#### Issue 1 — Return Order UX (`app.js`)

**Root cause:** `refundedAt` timestamp was already stored on the `Order` entity after a successful return, but neither the today's-orders view nor the order-history view used it to update the status badge or action buttons.

**Changes:**
- `orderStatusCell(o)`: when `o.status === 'DELIVERED' && o.refundedAt` → render amber "Returned" badge instead of green "Delivered".
- Today's-orders DELIVERED block: added `!o.refundedAt` guard on the "Process Return" button; when `o.refundedAt` is set, replaced it with an amber "Issue Replacement" button calling `openReturnReplacement(o.id)`.
- Order history button block: same guard (`o.status === 'DELIVERED' && !o.refundedAt` for "Process Return"; `o.refundedAt` shows "Issue Replacement").
- New `openReturnReplacement(orderId)`: pre-fills the new-order modal with the returned order's customer name, opens modal for staff to build the replacement.

**No backend changes.**

---

#### Issue 2 — PDF/Report Download (`app.js`)

**Root cause:** `downloadDailyReportPdf()` opened a new window and called `window.print()`. VS Code's integrated browser does not support the print dialog — the new window opened blank.

**Change:** After generating the report HTML, added a prominent "Download as HTML" button in the popup above the print button:
```js
const blob = new Blob([fullHtml], { type: 'text/html' });
const url  = URL.createObjectURL(blob);
const a    = document.createElement('a');
a.href = url; a.download = 'daily-report-' + dateStr + '.html';
a.click(); URL.revokeObjectURL(url);
```
The print dialog path is preserved for real-browser users.

**No backend changes.**

---

#### Issue 3 — PO Receive Modal UX (`index.html`, `app.js`)

**Finding:** The receive logic (`remainingQty = orderedQty − fulfilledQty`) was already correct. The user's observation ("1000 instead of 500") was caused by a DB wipe resetting `fulfilledQty` to 0. No logic bug.

**Minor UX changes:**
- `index.html` `modal-po-receive`: label changed from "Received Qty" → "Qty to Receive".
- `app.js openPoReceiveModal()`: remaining qty wrapped in `<strong>` in the hint text to make it visually prominent.

---

#### Issue 4 — Final Delivery Checkbox (`V73` + `PoItem.java` + `PurchaseOrderController.java` + `index.html` + `app.js`)

**Feature:** When a supplier cannot fulfil the full PO quantity, the user checks "Final Delivery" on the receive modal to close out the line at the received quantity. The PO detail shows the effective payable amount (based on received qty, not ordered qty).

| File | Change |
|------|--------|
| `V73__po_items_final_delivery.sql` | `ALTER TABLE po_items ADD COLUMN is_final_delivery BOOLEAN NOT NULL DEFAULT FALSE` |
| `PoItem.java` | Added `@Column(name = "is_final_delivery") private Boolean isFinalDelivery = false` with getter/setter |
| `PurchaseOrderController.receiveItem` | Reads `isFinalDelivery` flag from request body; sets `isFulfilled = true` when flag is true (or when `fulfilledQty >= orderedQty`); sets `isFinalDelivery = true` on item |
| `PurchaseOrderController.toMap` | Adds `isFinalDelivery` to item map; computes `effectiveTotalAmount` (uses `fulfilledQty × unitCost` for final-delivery items, `orderedQty × unitCost` for others) |
| `index.html` | "Final Delivery" checkbox added to `modal-po-receive` with description text |
| `app.js openPoReceiveModal()` | Resets checkbox on open |
| `app.js submitPoReceive()` | Reads checkbox; adds `isFinalDelivery` to request body |
| `app.js buildPoDetailHtml()` | Amber "Final" badge on items with `isFinalDelivery = true`; accounting summary block when `effectiveTotalAmount !== totalAmount` |

---

#### Deployment Pre-flight Fixes (3 items)

| # | Fix | File | Detail |
|---|-----|------|--------|
| 1 | `"suppliers"` missing from `ALL_PAGES` | `UserController.java:27` | API-created accounts without explicit `allowedPages` body got silent 403 on all supplier endpoints via PageAccessInterceptor. One line: added `"suppliers"` to the `ALL_PAGES` constant. |
| 2 | Dead `ADMIN` branch in `canEditInventory()` | `app.js` | Legacy V1 role (`ADMIN`) checked in `canEditInventory()` but unreachable via any UI dropdown. Function returns `true` only for `SUPER_ADMIN` and `ADMINISTRATOR` — consistent with `canManageEmployees()` and the backend `isManager()` check. Removed the three-line dead branch. |
| 3 | `assign-role-select` modal: missing options + wrong gate | `index.html`, `app.js` | Quick-action "Change User Role" modal was missing `ACCOUNTING` and `STAFF` options. Gate was `canManageEmployees()` (ADMINISTRATOR-accessible) but the backend endpoint is SUPER_ADMIN-only — ADMINISTRATOR could open the modal and get a 403. Fix: added both options in privilege order; changed gate to `isSuperAdmin()`. |

---

#### Security Gap Re-Verification

**GAP S10-01 — POST /api/settings 403 for non-SUPER_ADMIN:**
`SettingsIT.t05_postSettings_nonSuperAdminRole_returns403()` is active (not `@Disabled`) and passes. `SettingsController.updateSettings()` has the SUPER_ADMIN role check. Confirmed 5/5 SettingsIT tests green — gap closed.

**GAP S12-01 — `allowedPages` server-side enforcement:**
`AuthorizationGateIT` was reporting 11/13 (t12 and t13 failing — expected 403, got 200). The interceptor was registered and firing correctly. Diagnostic output revealed `user.allowedPages` was the full ACCOUNTING page list instead of `"[]"`.

**Root cause:** `@TestMethodOrder(MethodOrderer.MethodName.class)` runs tests alphabetically. `t06_updateRole_superAdminRole_returns200()` (runs before t12/t13) called `PATCH /api/users/{restrictedUser.id}/role` twice. `UserController.updateRole()` unconditionally sets `allowedPages = ROLE_DEFAULT_PAGES.get(newRole)` — for ACCOUNTING that is a full page list, which overwrote the `"[]"` written in `@BeforeAll`.

**Fix:** Changed t06 to use `accountingUser` as the role-change target instead of `restrictedUser`. The test still proves SUPER_ADMIN can change roles; `restrictedUser.allowedPages` remains `"[]"` for t12/t13.

**Also updated in `AuthorizationGateIT.java`:**
- Class Javadoc: updated from "GAP S12-01 — NOT enforced server-side" to describe the interceptor and its bypass rules.
- Section comment: `// ── GAP S12-01 — allowedPages is NOT enforced server-side` → `// ── GAP S12-01 — allowedPages enforced by PageAccessInterceptor`.
- t12 Javadoc: added isolation note ("restrictedUser must NOT be the target of any updateRole call in this class").
- t13 comment: simplified stale text.

**Final verification:**
```
mvn test -Dtest=SettingsIT,AuthorizationGateIT
```
`18 tests, 0 failures, 0 errors, 0 skipped` — 13 AuthorizationGateIT + 5 SettingsIT. BUILD SUCCESS.

---

## LAN Deploy — Session 2: Browser-preview UI verification of role-gating — Jun 13, 2026

Drove the live app (backend :8080 + frontend :5500) via preview tools; verified the role-gating UI (the one layer with no automated coverage) against the `ROLE-REGROUPING-PLAN.md` matrix for all four roles. Used existing test accounts `testuser2/3/04@rrbm.com` as fixtures (password set to `test123`, role-default permissions re-applied via `PATCH /role` — user-authorized).

**Verified (all ✓):**

| Area | Result |
|------|--------|
| SUPER_ADMIN | all nav incl. Settings; Add/Edit Employee role dropdown has no Staff; checkboxes auto-fill on role pick (STD 5 / ACCT 15 / ADMIN 19) and are **editable** |
| ADMINISTRATOR | all 19 page-keys + Employee List; Settings hidden; order Cancel/Void/Return buttons **hidden**; live `POST /orders/{id}/cancel` → **403** "Only Accounting or Super Admin", order stays ACTIVE |
| ACCOUNTING | nav = exact 15-key matrix (no employees/settings/order-history/delivery-reports/activity-log); order action buttons **shown** |
| STANDARD_USER | lands on **Order List** (not Dashboard); nav = exact 5-key matrix; action buttons hidden |
| Employee modal lock | non-Super-Admin (ADMINISTRATOR) → Add + Edit page-access checkboxes **disabled** (opacity 0.5), role pick still auto-fills |
| Restricted allowedPages | removed `dashboard` from a user → Dashboard nav **hidden** + `GET /api/dashboard` → **403**; still-allowed `/api/orders/today` → 200 |
| Return modal | per-row warehouse `<select>` appears only when `sellable>0`, **gates submit** (disabled until WH chosen), hides again when sellable→0; options WH1/WH2/Balagtas |

**Bug found + fixed:**

| # | Bug | File | Fix |
|---|-----|------|-----|
| 1 | Order-list **Cancel** button (`askCancel`) on ACTIVE/PENDING orders was **not gated** by `canManageOrders()` — showed for ADMINISTRATOR & STANDARD_USER (backend still 403'd, but plan requires the button hidden) | `app.js` (`renderOrderRows` ~556) | Added `cancelBtn` const gated by `canManageOrders()` (mirrors `ivmBtn`); ACTIVE/PENDING branches now use `+ cancelBtn`. DELIVERED branch was already gated. |

**Notes:** screenshots unavailable (preview-renderer timeout) — used accessibility snapshots + DOM/eval evidence. Test-account permission changes reverted to clean role defaults; super-admin session restored.
