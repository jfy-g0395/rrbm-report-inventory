# Batch Import Rework — Dedicated "Add Records" (Backdated Entry) Page

**Status:** Proposed
**Supersedes:** CSV batch import *input* flow (history viewing retained)
**Author:** Engineering
**Related:** Recording-only imports (V84), Cash Ledger (V80), Daily Report close pipeline

---

## 1. Why

The CSV batch import is not trusted for accuracy. It re-derives **payment mode, COD, order type, and payment status** from spreadsheet columns and gets them wrong, and CSV formatting (date formats, product-code typos) invites the same wrong-product / wrong-quantity mistakes we hardened against on the delivery side. It is also heavy for the common real case — "the system was down yesterday, here are a handful of orders/expenses to record."

We are **retiring the CSV input UI** and replacing it with a dedicated **Add Records** page where a user adds backdated **orders and expenses** one at a time into a session list and submits them together. Each entry carries its own **date** and a **Recording-only** toggle.

Two accuracy goals drive the rework:

1. **Order input is full parity with the live New Order screen** — the form and the server-side creation logic are *reused*, not re-implemented, so payment mode / COD / agent commissions / multi-item / **unpaid → collections** all behave exactly like a normal order. The only additions are the **date** and the **Recording-only** toggle.
2. **Late records actually appear in that day's report.** Today a backdated record never updates an already-closed day: `GET /reports/daily/{date}` returns a stored snapshot and `closeForImportDate` is idempotent. We add a gated **recompute** that re-snapshots a closed date from the ledger and marks it **amended** (who/when).

---

## 2. Decisions (locked)

| Question | Decision |
| --- | --- |
| CSV vs page | **Replace** the CSV input with a page. |
| CSV removal scope | **Retire the input UI; keep import history + all previously imported records** (audit, reversible). |
| Record types | **Orders + expenses.** |
| Multi-record entry | **Build a session list, submit together.** |
| Order input behavior | **Full parity with live New Order** — source, agents, payment mode, COD, multiple items, and **unpaid/pending → recorded for collection (Collections page)**. |
| Daily report for closed dates | **Gated recompute + mark "amended"** (who/when). |
| Per-entry controls | **Date** + **Recording-only** toggle per entry. |

Live production system → all DB changes are **additive and reversible** (Flyway, `ddl-auto=validate`, never wipe data).

---

## 3. Scope

**In scope**
- New **Add Records** page (Order tab + Expense tab + session list + submit-all).
- Backend backdated-commit endpoint reusing live build + creation logic.
- Gated daily-report recompute with an `amended` marker.
- Retiring the CSV input UI (frontend), keeping import-history endpoints + data.

**Out of scope**
- Removing `ImportController` or any import-history data.
- Changing the live New Order / Expense flows (only *extracting* reused helpers; behavior unchanged).
- Changing how the Collections page or `/collect` works (we reuse it).

---

## 4. The Add Records page (frontend)

Reuse the existing `view-import` container, nav item `nav-import`, and page key `'import'` (so **no role/permission migration** is needed). Relabel nav + title to **"Add Records"**.

**Retire (CSV input):** the authorize / download-template / upload / preview / commit blocks, the review modal, and the recording-only checkbox previously added to the preview. Remove the CSV JS (`uploadImportCsv`, `validateImport`, `commitImport`, `downloadCombinedTemplate`, preview/review helpers).

**Keep:** the Import History card (`import-history-tbody`) and its functions `loadImportHistory()` / `openImportDetailModal()`.

**New UI — two tabs, both reusing existing form machinery:**

- **Order tab** — mirrors the live New Order form (`addOrder()`):
  - Source selector (WALK_IN / AGENT / RESELLER / DISTRIBUTOR / ECOMMERCE / FACEBOOK_PAGE) with the same conditional fields (agent autocomplete + O.P./base price, reseller/distributor name, FB page, e-commerce platform + shop order id).
  - Payment mode (CASH/GCASH/PAYMAYA/BANK_TRANSFER/COD/…), order type, address, discount, delivery fee, notes.
  - **Payment status**: Paid vs **Unpaid/Pending** (drives the collections routing below).
  - **Multiple line items** via the existing product autocomplete (`appState.cachedProducts`) — product, qty, unit price, warehouse, and agent O.P. fields.
  - **Date** picker + **Recording-only** toggle.
- **Expense tab** — mirrors the live Expense form (`submitExpense()`): category / sub-category, amount, payment method, notes, reference, items, plus **Date** + **Recording-only**.

**Session list + submit:** "Add to list" stages each entry into a client-side table (date, type, summary, Paid/Collection badge, mode badge) with edit/remove. A session-level default date pre-fills new entries (each entry keeps its own date). "Submit All" POSTs `{ orders:[…], expenses:[…] }` to `/api/backdated/commit`. The result view shows committed counts, per-row errors, which orders went to **Collections**, and the **daily reports that were amended**.

---

## 5. Backend — order parity + backdated commit

### 5.1 Reuse the live build logic

Extract the order-building block from `OrderController.createOrder` (validation, agent linking, multi-item + commission `opAmount` setup) into a shared:

```
OrderService.buildOrderFromRequest(CreateOrderRequest req, Long userId) : Order
```

Used by **both** the live `POST /api/orders` and the new backdated path. Identical build = identical source / agent / payment / multi-item handling — this is the core accuracy fix.

### 5.2 Backdated order creation + payment-status routing

```
OrderService.createBackdatedOrder(CreateOrderRequest req, String paymentStatus,
                                  Long userId, LocalDate date, boolean recordingOnly)
```

1. `Order o = buildOrderFromRequest(req, userId)`
2. `createOrderAtDate(o, userId, date, /*affectStock*/ !recordingOnly)` — backdates the order-ID prefix, `createdAt`, and the **SALE ledger entry** to `date`; skips inventory deduction when recording-only.
3. **Payment-status routing — same behavior as the live + import paths:**

| Payment status | Status set | Ledger | Commission | Cash on hand | Appears in Collections? |
| --- | --- | --- | --- | --- | --- |
| **PAID** | `ACTIVE` | SALE on `date` | created (if `agentId`) | cash-in if `!recordingOnly` && CASH | No |
| **UNPAID / pending** | `PENDING_COLLECTION` (+ `pendingCollectionAt`) | SALE on `date` then `recordDeferralVoid` (net not inflated until collected) | **deferred** until collected | none until collected | **Yes** |
| **COD (unpaid)** | `PENDING` | SALE on `date` (deferred at close) | at collection | at collection | **Yes** (PENDING + non-cash) |

Unpaid / COD orders are **recorded for collection** and surface on the existing **Collections page** (`GET /api/orders/collections`). They are settled later through the existing `PATCH /api/orders/{id}/collect`, which already posts the SALE on the original date and bumps that day's report — no new collection logic needed.

> This is exactly how a normal order behaves — just backdated, with the inventory/cash **Recording-only** toggle layered on top. Reuses the routing in `ImportController.commitImport` and the live COD path.

### 5.3 Backdated expenses

Reuse the live expense create path. Add `createBackdatedExpense(...)` that builds the Expense from the same body shape, sets the chosen `date` (no ±7-day backdate-window restriction — this page *is* the backdated tool), sets `recordingOnly`, saves, and reconciles cash **only when `!recordingOnly`** (`cashLedgerService.reconcileExpenseCash`). Add `recordingOnly` to the expense `toMap()` serializer.

### 5.4 New endpoint

`BackdatedEntryController` → `POST /api/backdated/commit`, body `{ orders:[{date, recordingOnly, paymentStatus, …CreateOrderRequest}], expenses:[{date, recordingOnly, …}] }`:

- Admin-security-key gated (reuse the existing `checkKey` pattern).
- Each entry in its own try/catch (per-row errors collected, like `commitImport`); tag `imported=true`, `lateImported=true` when that date's report is already closed.
- Collect the **distinct affected dates**, then trigger the daily-report recompute (§6).
- Return a result summary (committed orders/expenses, collection routing, errors, amended dates).

`ImportController` and its history endpoints remain **untouched and functional**; only the frontend CSV input UI is retired.

---

## 6. Daily-report recompute + "amended" marker

**Migration `V85__daily_report_amended.sql`** (additive):

```sql
ALTER TABLE daily_reports ADD COLUMN IF NOT EXISTS amended    BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE daily_reports ADD COLUMN IF NOT EXISTS amended_at TIMESTAMPTZ;
ALTER TABLE daily_reports ADD COLUMN IF NOT EXISTS amended_by BIGINT;
```

Add `amended` / `amendedAt` / `amendedBy` to `DailyReport`.

**`DailyReportService.recomputeForDate(userId, userName, date)`** — modeled on the snapshot block of `closeForImportDate`, but operating on the **existing** row: re-run the operational + ledger + expense queries and `cashOnHand = getCashOnHandAsOf(date)`, overwrite the snapshot fields, **preserve** the original `closedBy` / `closedAt`, set `amended=true` / `amendedAt=now` / `amendedBy=userId`, save, and log `AMEND_DAILY_REPORT`. Factor the shared query block into a private `populateSnapshot(report, date)` reused by `closeForImportDate` and `recomputeForDate` (leave `closeDailySales` untouched to minimize risk to the live close path).

**Trigger + cash cascade:** after a backdated commit, for each affected date whose report already exists (closed), call `recomputeForDate`. Because `cashOnHand` is a running as-of balance, a **non-recording-only** (cash-affecting) entry backdated before today also shifts later days' frozen snapshots — so additionally cascade-recompute already-closed reports from the earliest cash-affecting date through today. Recording-only entries (no cash) need only their own date. In practice the cascade is short: "deduct" mode is for recent gap-fill; "recording-only" (no cascade) is for old data. Every touched report is marked `amended` + logged.

**Consistency note:** recording-only sales still post to the SALE ledger, so they correctly appear in `grossSales`/`netSales` for that date; their cash is intentionally excluded from `cashOnHand`. Inventory is untouched. This matches the intended "record the history without disturbing present actuals" semantics.

---

## 7. Files

**New**
- `docs/BATCH-IMPORT-REWORK.md` (this document)
- `rrbm-backend/src/main/resources/db/migration/V85__daily_report_amended.sql`
- `rrbm-backend/src/main/java/rrbm_backend/BackdatedEntryController.java`

**Backend (edit)**
- `OrderService.java` — extract `buildOrderFromRequest`, add `createBackdatedOrder` (with payment-status routing)
- `OrderController.java` — use the extracted builder (no behavior change)
- `ExpenseController.java` — add backdated create + `recordingOnly` in `toMap`
- `DailyReport.java` — `amended` / `amendedAt` / `amendedBy`
- `DailyReportService.java` — `populateSnapshot` + `recomputeForDate`

**Frontend (edit)**
- `rrbm_frontend/rrbm-frontend/index.html` — retire CSV input blocks; build Add Records tabs + session list; keep history
- `rrbm_frontend/rrbm-frontend/js/app.js` — remove CSV-input functions; add entry/staging/submit logic; relabel nav/title

**Tests**
- `BackdatedEntryIT` (real-DB, `*IT` + `ITSupport` pattern)

---

## 8. Verification

1. **Build / migrate / boot** against an **isolated throwaway Postgres** (never the live `rrbm_*` containers): Flyway applies V85, Hibernate `validate` passes, context loads, routes map.
2. **`BackdatedEntryIT`** (real DB via `ITSupport`):
   - Close a past date's report. Submit a backdated **PAID cash** order for that date → order has a date-prefixed ID, SALE ledger on that date, and the **daily report is recomputed** (`grossSales` up, `amended=true`, cash-on-hand reflects it).
   - **Unpaid** backdated order → status `PENDING_COLLECTION`, appears in `GET /api/orders/collections`, no commission yet; `/collect` settles it on the original date.
   - **COD** backdated order → status `PENDING`, shows in Collections.
   - **Recording-only** order → sales counted in the report, but inventory + cash-on-hand unchanged.
   - **Multi-item** order → all items recorded with correct totals/commission.
   - Backdated **expense** (recording-only vs deduct) → cash skipped vs reconciled; report `totalExpenses` refreshed.
3. **Manual UI smoke**: stage an order + an expense for a past closed date, submit, confirm the result lists the amended report and the day's report now includes them; confirm an Unpaid order lands on Collections; confirm Import History still loads.
4. **Regression**: live order + live expense unchanged (same `buildOrderFromRequest` path); existing closed reports untouched unless explicitly amended.

---

## 9. Rollout & safety

- Additive migration only; no destructive DDL. Existing imported orders/expenses and import history are preserved.
- The live New Order / Expense / Collections / close flows are unchanged — the page rides on top of them via extracted shared helpers.
- `recompute` preserves original close metadata and stamps `amended` + an activity-log entry, keeping a clear audit trail of which historical reports were refreshed and by whom.

---

## 10. Session plan (pre-flight, locked 2026-06-27)

Built session-by-session; each is independently shippable and testable, dependencies flow forward. **Every backend session (S1–S3) must build/boot against an isolated throwaway Postgres — never the live `rrbm_*` containers.**

**Pre-flight findings (verified against code):**
- V84 recording-only foundation is already done (uncommitted): columns, entity fields, `createOrderAtDate(…, affectStock)` overload at `OrderService.java:168`, ImportController cash gating.
- COD → PENDING is already set in the backdated path (`OrderService.java:188`) — no new COD code needed; routing only adds PAID/UNPAID/blank (already in `commitImport` at `ImportController.java:1079`).
- Order-build block to extract: `OrderController.java:128-185`. Validation block: `:104-126`. Commission *entries* created post-save at `:182-185`.
- Snapshot block to factor into `populateSnapshot`: `DailyReportService.java:287-388`. `closeDailySales` is separate — leave untouched.
- Expense backdate window = configurable `expense_backdating_days` (default 7) at `ExpenseController.java:93`; backdated path skips only that guard.

**Decisions locked:**
- `buildOrderFromRequest` **includes the validation block** (qty/price/productId existence), so live + backdated paths share identical guards. Commission *entries* stay a post-save call per caller.
- Frontend split into **S4a + S4b**.

**Correction (found during S2):** §5.2's table says a COD order "Appears in Collections? **Yes**". That is **inaccurate** vs current live behavior. `OrderRepository.findPendingCollections()` returns **only `PENDING_COLLECTION`** orders — PENDING (COD) orders are deliberately excluded (the M-7 fix, to avoid a double-SALE on collect). The stale "PENDING + non-CASH" comment in `OrderController` is misleading. So a backdated COD order is created as **PENDING** and is **collectable via `PATCH /collect`**, but is **not listed** on the Collections page — exactly like a live COD order. The backdated path correctly mirrors live behavior; no code change needed, but S4's result UI should not promise COD orders will appear in Collections.

| # | Session | Depends on | Key open items to confirm at start |
| --- | --- | --- | --- |
| **S1 ✅** | Order-parity refactor (no behavior change): extract `buildOrderFromRequest` (build + validation), rewire live `createOrder`. **DONE** — verified by OrderCreateValidationIT (10), OrderReadIT (14), AgentA2Test (4). Parity confirmed: reseller/distributor name rides `agentName`; shop-order-id rides `notes` (no first-class field). | — | ✅ resolved |
| **S2 ✅** | Backdated commit backend: `createBackdatedOrder` (payment routing), `createBackdatedExpense`, `BackdatedEntryController` + `POST /api/backdated/commit`, `recordingOnly` in expense `toMap`. No recompute trigger yet. **DONE** — `BackdatedEntryIT` (10) green + expense regression (25) green. | S1 | ✅ resolved (`checkKey` replicated; collections/collect confirmed — see COD correction above) |
| **S3 ✅** | Daily-report recompute + amended: V85 migration, `DailyReport` fields, `populateSnapshot` refactor, `recomputeForDate` + `recomputeAffected` cash cascade, wire trigger into S2 controller. **DONE** — `BackdatedEntryIT` now 13 green (added t11 amend+closedBy-preserved, t12 cash cascade to later closed day, t13 recording-only = own-date-only/no cascade); ForceCloseIT 3 green. | S2 | ✅ Cascade is precise: cash-affecting = non-recording-only CASH order (not deferred) / CASH expense only. Edge cases handled (no closed report → skip; future date → no cascade). |
| **S4a ✅** | Frontend: retire CSV input UI (keep history), scaffold Add Records tabs, Expense tab, session list, Submit-All plumbing, result view, relabel nav. **DONE** — verified client-side in preview (nav/title relabeled, tab toggle, expense staging → list with date/type/summary/flags, submit enable/disable, result renderer wired to `/api/backdated/commit`). `node --check` clean. | S2/S3 | ⚠️ Old CSV-input JS (`authorizeImport`/`uploadImportCsv`/`commitImport`/`downloadCombinedTemplate`/`openReviewModal` + preview/review helpers) left as **dead code** (no longer referenced by the view) to keep S4a low-risk — see cleanup task. Order tab is a placeholder until S4b. |
| **S4b ✅** | Frontend: full Order-tab parity (source-conditional fields, multi-item, agent O.P./base price). Stage orders into the same session list. **DONE** — Order tab built with `addrec-ord-*` IDs mirroring the live New Order form; `onAddRecOrderSourceChange` (all 6 sources), agent autocomplete (`/api/orders/agent-options`), per-row product autocomplete (`appState.cachedProducts`), per-item Base/Over price rows for AGENT, `stageAddRecOrder` → `_addRecList`. Verified client-side in Docker nginx preview: all source conditionals show/hide correctly, captured POST body matches `CreateOrderRequest` + top-level `date`/`recordingOnly`/`paymentStatus` exactly (items use `quantity`/`unitPrice`/`opPerUnit`), RESELLER→`agentName`, ECOMMERCE shop-id→`notes` "Order No:" prefix, net total calc correct, no console errors. `node --check` clean. | S4a | — |

**Verification harness note:** this machine has no working local Node/Python, so browser preview is served via a Docker nginx config added to `.claude/launch.json` (`frontend-docker`). `API_BASE` resolves to `http://localhost:8080` (prod) from the preview, so S4 verification is **client-side only** — never click Submit All against the preview.

---

## 11. S4b — DONE (logged 2026-06-29)

**All sessions complete: S1–S3 (backend) ✅, S4a + S4b (frontend) ✅. The Add Records page is feature-complete and verified client-side.**

**Remaining (optional cleanup, not blocking):**
- `task_79a11e75` — remove the dead CSV batch-import JS from `app.js` (`authorizeImport`, `uploadImportCsv`, `commitImport`, `downloadCombinedTemplate`, `openReviewModal`, preview/review helpers). KEEP `loadImportHistory`/`openImportDetailModal`, the Add Records functions, and the separate CSV-import block (~lines 3200–3274 region).
- `task_5cbb138d` — fix pre-existing DailyCloseIT `product_code` seed overflow (unrelated to this feature).
- **End-to-end prod smoke test** still pending: the whole flow has only been verified client-side (preview `API_BASE`→prod, so Submit All was never fired against the server). When deploying, do a real Submit All against a backdated closed date and confirm the daily report is amended + Collections routing works.

### Original S4b spec (now implemented) — kept for reference

### What S4a left behind

- The Order tab in the Add Records page (`addrec-tab-order`) is a **placeholder div** — no form fields yet.
- The submit plumbing (`submitBackdated`) and result renderer (`renderBackdatedResult`) already handle `orders[]` correctly (they were written generically in S4a).
- The Expense tab is fully functional: staging, session list, flags (recording-only, date).
- Dead code: the old CSV-import JS functions (`authorizeImport`, `uploadImportCsv`, `commitImport`, `downloadCombinedTemplate`, `openReviewModal`, preview/review helpers) are still present in `app.js` but no longer called. Cleanup is deferred (see spawn_task `task_79a11e75`).

### What S4b must build (Order tab)

Replace the placeholder in `addrec-tab-order` with a form that mirrors the live New Order form, using `addrec-ord-*` IDs to avoid collisions:

1. **Source selector** (`addrec-ord-source`) — options: WALK_IN / AGENT / RESELLER / DISTRIBUTOR / ECOMMERCE / FACEBOOK_PAGE  
   Conditional blocks shown/hidden on change (`onAddRecSourceChange`):
   - AGENT → agent autocomplete (`addrec-ord-agent`) + per-item O.P. fields
   - RESELLER / DISTRIBUTOR → name field (`addrec-ord-reseller-name`, stored in `agentName`)
   - FACEBOOK_PAGE → FB page field (`addrec-ord-fb-page`)
   - ECOMMERCE → platform field (`addrec-ord-ecommerce-platform`) + shop order ID (`addrec-ord-shop-order-id`, stored in `notes`)
2. **Payment mode** (`addrec-ord-payment-mode`) — CASH/GCASH/PAYMAYA/BANK_TRANSFER/COD/…
3. **Order type** (`addrec-ord-order-type`) — REGULAR / DELIVERY / PICKUP
4. **Address** (shown when DELIVERY)
5. **Payment status** (`addrec-ord-payment-status`) — Paid / Unpaid (blank = COD/default)
6. **Multi-item rows** — product autocomplete, qty, unit price, warehouse, + agent base price/OP fields when source=AGENT (mirror `addItemRow` from the live form)
7. **Discount**, **delivery fee**, **notes**
8. **Date** (`addrec-ord-date`, defaults from `addrec-default-date`) + **Recording-only** toggle

`stageAddRecOrder()` validates required fields, pushes to `_addRecList` with `kind:'order'` and the payload shape `BackdatedEntryController` expects:
```json
{ "date":"YYYY-MM-DD", "recordingOnly":false, "paymentStatus":"PAID",
  "customerName":"…", "source":"AGENT", "agentId":5, "paymentMode":"CASH",
  "orderType":"REGULAR", "items":[{"productId":1,"qty":2,"unitPrice":100,"warehouse":"MAIN","basePrice":80,"opPerUnit":20}],
  "discount":0, "deliveryFee":0, "notes":"" }
```

### First steps for S4b

1. Read the live New Order form in `index.html` (search for `id="order-source"` or `addOrder`) to find exact field IDs and conditional block structure.
2. Read `onOrderSourceChange` (or equivalent) in `app.js` to understand the show/hide logic for agent/reseller/ecommerce/FB conditional fields and per-item O.P. columns.
3. Read the `addItemRow` / product-autocomplete JS to understand how live multi-item rows work so S4b can mirror them with `addrec-` prefixes.
4. Write the Order tab HTML into `index.html` (replace the placeholder `<div id="addrec-tab-order">`) and add `stageAddRecOrder` + `onAddRecSourceChange` + `addAddRecOrderRow` / `removeAddRecOrderRow` to `app.js`.
5. Run `node --check rrbm_frontend/rrbm-frontend/js/app.js` inside `node:20-alpine` container to verify no syntax errors.
6. Start the `frontend-docker` preview config and visually verify the Order tab — all conditional fields show/hide correctly, "Add to list" stages an entry with the correct summary, session list updates, Submit All button enables. **Do NOT click Submit All against prod.**
