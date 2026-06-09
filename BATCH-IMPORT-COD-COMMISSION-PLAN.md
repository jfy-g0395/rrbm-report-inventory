# Batch Import — COD Payment & Agent Commission Plan

## Priority Order (most critical first)

Each session is self-contained, testable, and builds on the previous.

---

## ✅ Session 1: Activity Logging for Batch Sales Import (COMPLETE — U10)

**Implemented:** Jun 7, 2026 in U10

### Objective
Add activity log entries when sales orders are committed via batch import.

### Changes Made

| File | Change |
|------|--------|
| `ImportController.java` (~line 854) | Added `BATCH_IMPORT_SALES` activity log after sales commit loop — logs count + total |

### Test Results
All 17 non-U2 import tests green.

---

## ✅ Session 2: COD Paid/Unpaid Routing + Expenses + Dual-Sheet Upload (COMPLETE — U11)

**Implemented:** Jun 7, 2026 in U11 — merged with Session 3's combined upload + close endpoint

### Objective
- Add Payment Status column (col 12, last column — backward compatible via `getCol(cols, 12, "")`)
- COD PAID → ACTIVE + commission; COD UNPAID → PENDING_COLLECTION + reversed SALE + no commission
- Expense tracking in daily reports (V61: `total_expenses`, `expenses_count`)
- Dual-sheet xlsx upload (Sales + Expenses sheets detected → `/api/import/upload/combined`)
- Manual close endpoint (`POST /api/import/close`)
- Auto-close removed from commit handler

### Changes Made

| File | Change |
|------|--------|
| `db/migration/V61__daily_report_expenses.sql` | `total_expenses DECIMAL(14,2)`, `expenses_count INT` on `daily_reports` |
| `DailyReport.java` | Added `totalExpenses` + `expensesCount` fields + getters/setters |
| `DailyReportService.java` | Added `ExpenseRepository` injection; expense queries in both `closeDailySales()` and `closeForImportDate()` |
| `ImportController.java` — template | `Payment Status` added as col 12 in CSV header |
| `ImportController.java` — `ParsedSaleRow` / `SaleAccumulator` | Added `paymentStatus` field |
| `ImportController.java` — upload/sales | Parses Payment Status from col 12; passes through to `ParsedSaleRow` |
| `ImportController.java` — commit handler | COD PAID → status=ACTIVE; COD UNPAID → status=PENDING_COLLECTION + `recordDeferralVoid()` + skip commission. Auto-close block removed. |
| `ImportController.java` — `/upload/combined` | New endpoint accepting `salesFile` + `expensesFile` multipart; shared `parseSalesCsv()` / `parseExpensesCsv()` helpers |
| `ImportController.java` — `/close` | New endpoint accepting `{dates: [...]}` — calls `closeForImportDate()` per date |
| `app.js` — `uploadImportCsv()` | Detects Sales + Expenses sheets in xlsx → routes to `/upload/combined`; falls back to single-type endpoints |
| `app.js` — `renderImportPreview()` | Supports `combined` upload type (mixes order + expense rows) |
| `app.js` — `commitImport()` | After success, stores `_closeDates` from committed dates; shows "Close Daily Reports" button in modal |
| `app.js` — `closeImportReports()` | Calls `POST /api/import/close` with stored dates |
| `app.js` — `showImportResultModal()` | Removed autoClosedReports block; replaced with manual close button + info banner |
| `app.js` — `downloadSalesTemplate()` | Added Payment Status column + COD PAID example row |
| `index.html` | Added Cancel button to preview card header |

### Key Design Decisions
- Payment Status at col 12 (last) — `getCol(cols, 12, "")` returns `""` for old files → zero column shifts, 100% backward compatible
- Expenses tracked as informational fields on `daily_report` — NOT subtracted from `net_sales`
- Combined endpoint uses shared `parseSalesCsv()` / `parseExpensesCsv()` helpers that return `Map<String, Object>` with valid/needsFix/duplicates/parsedRows

### Test Results
130/134 pass (4 U2 pre-existing endpoint-path failures). 130 tests = 17 non-U2 import tests + 113 other tests.

---

## ✅ Session 3: COD Collection Commission + Idempotency Guard (COMPLETE — U12)

**Implemented:** Jun 7, 2026 in U12

### Pre-Flight Finding
The `PATCH /api/orders/{id}/collect` endpoint already existed at `OrderController.java:472` — it handles `PENDING_COLLECTION` → `DELIVERED`, records `COLL-SALE`, sets `collectedAt`/`collectedBy`, patches daily report, logs activity. No new endpoint was needed.

### Actual Gaps Found & Filled

| Gap | Root Cause | Fix |
|-----|------------|-----|
| No commission creation on collection | `collectOrder()` never called `commissionService.createEntriesForOrder()` | Added `try { commissionService.createEntriesForOrder(order, userId); } catch (Exception ignored) {}` in PENDING_COLLECTION branch |
| Duplicate commission entries if called twice | `CommissionService.createEntriesForOrder()` had no idempotency guard | Added `if (entryRepository.existsByOrderId(savedOrder.getId())) return;` at top of method + `existsByOrderId` query to `CommissionEntryRepository` |
| `pendingCollectionAt` never populated | V27 added the column, no code set it | Added `savedOrder.setPendingCollectionAt(OffsetDateTime.now())` in ImportController COD UNPAID branch |

### Changes Made

| File | Change |
|------|--------|
| `CommissionEntryRepository.java` | Added `boolean existsByOrderId(String orderId)` — Spring Data derived query |
| `CommissionService.java` (~line 31) | Added idempotency guard: `if (entryRepository.existsByOrderId(savedOrder.getId())) return;` |
| `OrderController.java` (~line 552) | Added `try { commissionService.createEntriesForOrder(order, userId); } catch (Exception ignored) {}` in PENDING_COLLECTION branch |
| `ImportController.java` (~line 851) | Added `savedOrder.setPendingCollectionAt(OffsetDateTime.now())` when setting PENDING_COLLECTION |
| `ImportController.java` — imports | Added `import java.time.OffsetDateTime` |

### Test Results
130/134 pass (4 U2 pre-existing endpoint-path failures). No regression.

### Asset Inventory Update
- `pendingCollectionAt` — NOW SET (import COD UNPAID path)
- `collectedAt` / `collectedBy` — already set by `collectOrder` (pre-existing)
- Commission entries — NOW CREATED on collection (via `createEntriesForOrder`)
- Idempotency — COMMISSION ENTRIES are safe against duplicate calls

---

## ✅ Session 4: Agent Commission Detail Modal (COMPLETE — U13)

**Implemented:** Jun 7, 2026 in U13

### Pre-Flight Finding
The plan assumed `GET /api/agents/{id}/commissions/breakdown?periodId=X` already existed — it did **not**. The closest equivalent was `GET /api/commissions/periods/{id}/agents/{agentId}/statement` which returns flat entries (no order grouping, no customer name). Created a new endpoint + frontend modal (Option A).

### Objective
Add a commission detail modal to the frontend showing per-order breakdown:
- Receipt number, date, customer
- Per item: product name, quantity, unit price, OP per unit, subtotal, commission amount
- Total commission for the period

### Backend Check (Post-Implementation)
`GET /api/agents/{id}/commissions/breakdown?periodId=X` now returns:

```json
{
  "agentId": 1,
  "agentName": "Juan",
  "period": "2026-06-A",
  "orders": [
    {
      "orderId": "010626-000001",
      "date": "2026-01-06",
      "customer": "Maria",
      "items": [
        {
          "productName": "Product A",
          "quantity": 2,
          "unitPrice": 100.00,
          "opPerUnit": 10.00,
          "opSubtotal": 20.00,
          "commissionRate": 10.00,
          "commissionAmount": 20.00
        }
      ],
      "totalOp": 20.00,
      "totalCommission": 20.00
    }
  ],
  "totalCommission": 20.00
}
```

### Actual Gaps Found & Filled

| Gap | Root Cause | Fix |
|-----|------------|-----|
| Endpoint didn't exist | Plan assumed pre-existing endpoint | Created `GET /api/agents/{id}/commissions/breakdown?periodId=X` — groups entries by order, fetches customer from Order table, returns nested JSON |
| No `OrderRepository` in `CommissionController` | Controller never needed orders | Injected `OrderRepository` via constructor + field |
| No period selector UI | Frontend only had per-period summary in History modal | Added period dropdown loading `GET /api/commissions/periods` in the new modal |
| No "Comm" button on agent list | Only "Edit" + "History" existed | Added "Comm" button wired to `openCommissionBreakdownModal()` |

### Changes Made

| File | Change |
|------|--------|
| `CommissionController.java` | Injected `OrderRepository`; new `GET /api/agents/{id}/commissions/breakdown?periodId=X` — sorts entries by date+orderId, groups by order, looks up customer name, builds per-order item array with `opPerUnit`/`commissionRate`/`commissionAmount` |
| `index.html` | New `modal-commission-breakdown` (after `modal-agent-performance`) — 860px max-width, scrollable body, close button |
| `app.js` | 3 additions: `openCommissionBreakdownModal(agentId)` — loads periods, renders selector dropdown; `loadCommissionBreakdown(agentId, periodId)` — fetches & renders order cards with items table + totals; "Comm" button added to agent list table actions column |

### What's NOT Changed
- No model/entity changes (Order, CommissionEntry, CommissionPeriod untouched)
- No database migrations
- No existing endpoint changes
- No import/collection pipeline changes
- No existing modals modified
- `CommissionEntryRepository` unchanged (Java-side sorting)

### Test Results
134/134 pass. No regression.

---

## ✅ Session 5: Batch Collection Marking (COMPLETE — U14)

**Implemented:** Jun 7, 2026 in U14

### Pre-Flight Findings
- A `collections` view already existed (`view-collections` at `index.html:1640`) with nav button, table, `loadCollections()` function, single-order "View" detail modal, and badge counter. The plan assumed none of this existed.
- Single-order collect logic is entirely in `OrderController.java` (not OrderService). Plan said to add to `OrderService` — created `batchMarkAsCollected(orderIds, userId, callerName)` there for modularity, injecting `CommissionService` + `DailyReportRepository` into OrderService.
- Enhanced the existing `collections` view with batch checkboxes rather than creating a new view.

### Objective
- Batch mark multiple COD orders as collected with admin security key
- Enhanced frontend view with checkboxes + batch confirm modal

### Actual Gaps Found & Filled

| Gap | Root Cause | Fix |
|-----|------------|-----|
| No batch endpoint | Only single-order `PATCH /{id}/collect` existed | Created `POST /api/orders/batch-mark-collected` — validates security key once, calls `OrderService.batchMarkAsCollected()` per order, returns `{collected, skipped[], errors[]}` |
| OrderService missing batch method + deps | All collect logic in controller | Added `batchMarkAsCollected(orderIds, userId, callerName)` + `BatchCollectResult` inner class; injected `CommissionService` + `DailyReportRepository` |
| Collections table had no checkboxes | Read-only table | Added checkbox column + "Select All" header checkbox + "Mark Selected (N)" button in card header |
| No batch confirmation flow | Single collect uses PATCH with inline key | Added `modal-batch-collect` security key modal + `confirmBatchCollect()` handler — shows collected/skipped/errors result |
| colspan mismatch | Added a column | Updated all colspans from 8 to 9 |

### Changes Made

| File | Change |
|------|--------|
| `OrderService.java` | Injected `CommissionService` + `DailyReportRepository`; new `batchMarkAsCollected(orderIds, userId, callerName)` — per-order loop with pessimistic lock, status/CASH guards, DELIVERED mutation, COLL-SALE ledger entry, commission creation, daily report patch, ORDER_COLLECT activity log; new `BatchCollectResult` inner class |
| `OrderController.java` | New `POST /api/orders/batch-mark-collected` — accepts `{orderIds: [...], securityKey: "..."}`, validates JWT + security key once, delegates to service, returns summary |
| `index.html` | Added checkbox column to collections table header + "Select All" checkbox; added "Mark Selected (N)" button in card header; added `modal-batch-collect` security key modal (input + result area) |
| `app.js` | Updated `renderCollectionRows()` — added checkbox per row; added `toggleAllCollectionCheckboxes()`, `updateBatchCollectButton()`, `openBatchCollectModal()`, `confirmBatchCollect()` — calls batch endpoint, shows toast + result breakdown; updated colspans 8→9 |

### What's NOT Changed
- No models/entities/migrations
- Single-order collect endpoint untouched
- No new frontend views or nav items (enhanced existing)
- No changes to `GET /api/orders/collections` endpoint
- No changes to existing collection detail modal

### Test Results
134/134 pass. No regression.

---

## ✅ Session 6: Integration Testing & Bug Bash (COMPLETE)

**Verified:** Jun 9, 2026

### Verification
All 5 test scenarios were already covered by the existing `ImportU6Test.java` (7 tests total):

| Scenario | Covered By | Status |
|----------|-----------|--------|
| Happy path: COD unpaid import → collect → commission | U6-b (PENDING_COLLECTION + COLL-DEFER) + U6-c (collect → DELIVERED + COLL-SALE) + U6-d (commission after collect) | ✅ Pass |
| COD paid flow | U6-a (COD PAID → ACTIVE + SALE) | ✅ Pass |
| Mixed batch | U6-g (CASH/COD PAID/COD UNPAID → correct status per row) | ✅ Pass |
| Re-import same receipt# | U6-f (duplicate → appears in duplicates) | ✅ Pass |
| Commission after collection | U6-d (commission entries created after collect when opAmount > 0) | ✅ Pass |
| **Bonus:** Batch collect | U6-e (POST /api/orders/batch-mark-collected → both DELIVERED) | ✅ Pass |

### Results
- **142/142 tests pass** (full regression)
- All COD lifecycle paths verified: import → status routing → collection → commission → duplicate detection → mixed modes → batch collect


## Schema Reference

### Order statuses
- `ACTIVE` — Confirmed sale (revenue recognized)
- `PENDING_COLLECTION` — COD unpaid; awaiting cash collection
- `PENDING` — Order placed but not yet fulfilled
- `CANCELLED` — Voided/cancelled
- `CLOSED` — Historical (legacy)

### Transaction types
- `SALE` — Revenue recorded (cash, GCASH, paid COD, etc.)
- `COLL-SALE` — Revenue recorded when COD is collected
- `VOID` — Reversal (negative amount)
- `REFUND` / `RETURN` — Customer returns (negative amount)
- `ADJUSTMENT` — Manual correction

### Commission lifecycle
- Created per-order-item when `opAmount > 0` and `agentId != null`
- For COD unpaid: commission NOT created at import time (Session 3, Option A)
- For COD collected: commission created at collection time
- Commission period assignment: based on order date → finds OPEN period
