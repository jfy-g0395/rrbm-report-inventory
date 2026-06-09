# RRBM — Expense & Agent Commission System Specification

**Version 2.0 · Status: ready for implementation**

This is the *what to build* reference. It pairs with two other documents:

- `BUILD_CONTEXT.md` — the current state of the codebase (real tables, field names, file paths).
- `BUILD_PLAN.md` — the session-by-session build order and tests.

Each build session reads `BUILD_CONTEXT.md`, the one section of this spec it needs, and its row in `BUILD_PLAN.md`. Section numbers here (§1.2, §2.5, etc.) are the references used by the plan.

## Decisions baked into this spec

- **Currency:** PHP. **Payment methods:** Cash, Bank Transfer, GCash, PayMaya, COD (the values the system already uses; no Card).
- **O.P./commission applies to AGENT sources only.** Reseller and Distributor sources are left exactly as they are today (plain-text, no registry, no commission).
- **No historical backfill.** Existing free-text agent names stay as history; a nullable `agent_id` link is added and only new orders use it.
- **Security keys reuse the existing mechanism** (`admin_security_key`). Expense voids and commission releases are authorised with the acting user's own key — no new key system.
- **Voids are reversals, never deletes.** **Over Price is stored per unit on the line item**, never as an order-level lump.
- **Commission period follows the *paid* date**, not the order date and not the ledger's effective date.

---

# PART 1 — Expense Tracking

## 1.1 Philosophy

The expense module is the record of every peso leaving the business. The current module works but stores only a free-text description and an amount per item, with no categories, no void trail, and no backdating control. The redesign keeps entry fast (a common expense should take seconds), categorises by default, makes every entry and reversal auditable, and turns the stored data into daily, weekly, and monthly reports that show patterns rather than just totals.

## 1.2 Expense category schema

**Primary categories** are system-defined and cannot be deleted.

| Code | Name | Covers | Typical frequency |
|------|------|--------|-------------------|
| FACILITY | Facility Costs | Rent and building | Monthly |
| UTILITY | Utilities | Variable monthly bills | Monthly |
| SUPPLY | Supplies | Consumable materials | As-needed |
| INVENTORY | Inventory Replenishment | Packaging materials | Periodic |
| OPERATIONS | Operational | Vehicle, delivery, fuel | Variable |
| PERSONNEL | Personnel Costs | Salaries, allowances | Scheduled |
| SERVICES | Professional Services | Accounting, external | As-needed |
| MISC | Miscellaneous | Petty cash, uncategorised | Variable |

**Sub-categories** (seeded under each primary; admins can add more):

- **Facility:** Monthly Office Rent · Building Maintenance · Parking Fees
- **Utilities:** Electric Bill · Internet Bill (ISP) · Water Utility Bill
- **Supplies:** Office Supplies · Cleaning Supplies · Kitchen Supplies
- **Inventory:** Packaging Tapes · Bubble Wrap · Boxes/Cartons · Stickers/Labels · Shrink Wrap
- **Operational:** Delivery Vehicle Maintenance · Gas Allowance · Delivery Budget · Shipping Fee · Fuel Reimbursement
- **Personnel:** Employee Salary · Food Allowance · Daily Food Expense · Overtime Pay · Bonuses/Incentives
- **Services:** Accounting/Bookkeeping Fees · Professional Services · Legal Fees · Software/Subscriptions
- **Miscellaneous:** Petty Cash · Contingency Fund · Bank Charges · Miscellaneous Expense

**Custom sub-categories** (admin-created) carry: name (≤50 chars), parent category, optional default amount, "requires receipt" toggle, active toggle.

Because the existing `expense_items` table holds only free-text descriptions, the new `category_id` link is **nullable** so historical rows remain valid; new entries require it.

## 1.3 Expense entry

A panel reachable from the dashboard at all times.

| Field | Type | Required | Behaviour |
|-------|------|----------|-----------|
| Date | Date picker | Yes | Defaults to today; backdating allowed up to a configurable number of days (a `settings` value, not hardcoded) |
| Category | Dropdown | Yes | Grouped by primary category |
| Sub-category | Dropdown | Yes | Filtered by the chosen category |
| Amount | Currency | Yes | Numeric, PHP formatting |
| Notes | Textarea | No | ≤500 chars, for "where the money went" |
| Reference / Receipt no. | Text | No | Links a physical receipt |
| Payment method | Dropdown | Yes | Cash, Bank Transfer, GCash, PayMaya, COD |
| Status | Dropdown | Yes | Defaults to Completed |

**Quick-entry** offers buttons for the ten most common expenses (Office Rent, Electric, Internet, Water, Gas, Food, Delivery, Supplies, Shipping, Petty Cash). A button pre-fills category and sub-category, leaving only amount and an optional note before confirming.

## 1.4 Void / return

When recorded money is returned or an entry is wrong, the original is **never deleted**. The system writes an offsetting negative adjustment instead, so totals correct themselves while the history stays intact.

- **Authorisation:** the acting user supplies their own `admin_security_key` (the same key flow already used for Cancel/Void/Force-close). A confirmation step and a reason are required.
- **Reason options:** Returned to Cashbox · Cancelled Purchase · Duplicate Entry · Wrong Category · Other (free text required).
- **Behaviour:** voided entries show struck-through in reports; a separate Adjustments column shows the negative amount; voided entries are excluded from running totals; an audit record captures who voided, when, the reason, and the original entry reference.

## 1.5 Dashboard

A prominent "Today's Expenses" widget shows the running total and transaction count for the day, with shortcuts to a detailed view and to add an expense. Supporting metrics: today's total (large, in the negative colour), today vs. yesterday as a percentage, month-to-date running total, month-to-date by category as a mini bar chart, and a badge for any pending voids.

## 1.6 Reporting

**Daily report** (any chosen date): summary line (total, transaction count, average per transaction), a breakdown by category with sub-category detail and subtotals, and a payment-method breakdown with percentages.

**Weekly report** (auto-generated weekly): week-over-week comparison, day-by-day breakdown, category summaries, and notes flagging unusually high or low days.

**Monthly report** (any chosen month):
- Executive summary: grand total, daily average, highest and lowest day.
- Category performance: amount and percent of total per category, with a total row.
- Daily breakdown: one row per day (amount, transaction count, note).
- Adjustments & voids: each void with its original amount, the negative amount, and reason.
- Net expenses after adjustments.

Reports are computed from queries on demand (no pre-materialised snapshots) until volume makes that necessary.

## 1.7 Export

PDF (formal/printing), Excel (analysis), CSV (import elsewhere). Every export carries the company header, the report's date range, any filters applied, the generation timestamp, and page numbers where multi-page. The app currently prints through the browser's print-to-PDF path; exports follow that same pattern rather than introducing a backend PDF engine.

---

# PART 2 — Agent Commission

## 2.1 Overview

Today an order's Source can be "Agent," which reveals a free-text name field — error-prone and impossible to total per agent. The redesign makes agents first-class records and tracks each agent's **Over Price (O.P.)** — the markup they add on top of the company price, which is their commission. Only the AGENT source uses this; Reseller and Distributor sources are unchanged.

The price the customer pays is `company base price + O.P.`. The customer sees only that combined price. The O.P. portion is stored separately, per unit, purely to total the agent's commission.

## 2.2 Agent registry

| Field | Type | Notes |
|-------|------|-------|
| Agent code | System | Format `AGENT-YYYY-NNNN` |
| Full name | Text, required | Legal name for payment |
| Contact number | Text, required | For commission release |
| Email | Text | Optional |
| Territory / area | Dropdown, required | Area of operations |
| Status | Toggle | Active / Inactive |
| Registration date | System | Auto |
| Notes | Textarea | Special arrangements, bank details |

**List view** columns: agent code, name (searchable), territory, total orders, lifetime O.P., pending commission, status, actions (view / edit / commission history). Filters: territory, status, commission range, registration date.

## 2.3 Order integration

When Source = **Agent**, reveal an agent block:

- A **searchable dropdown of registered agents** (type to filter; active agents only, except when editing an order that already references an inactive one). A "Register new agent" shortcut opens a quick modal. The free-text name field is removed.
- An **Over Price field per line item.** The value entered is the O.P. **per unit**. For example, O.P. 15 on quantity 5 contributes 75 to commission. The displayed new unit price is `base price + O.P. per unit`.

**Storage:** the line item keeps the customer-facing price in its existing `unit_price` (so order subtotal and total math are unchanged), and adds the company `base_price` and the `op_per_unit` alongside it. Commission for a line is `op_per_unit × effective quantity`.

**Receipt and PDF suppression (critical):** the customer-facing receipt and every print/export path show only the new unit price. They must **never** show the agent's name, the company base price, the O.P. figure, or any agent identifier. The current receipt prints the agent name in the Source field — that must be removed (show only "Agent"). All print/export functions must be audited for leaks, not just the order receipt.

## 2.4 Itemised commission breakdown

Commission is tracked **per line item**, not per order, so multi-item orders are fully transparent. An agent's statement lists each qualifying line: order id, date, item, quantity, O.P. per unit, and line O.P., ending in a total. This lets the agent verify earnings and gives the company a clean audit trail.

## 2.5 Commission distribution

**Cut-off periods**

| Period | Date range | Released |
|--------|------------|----------|
| 1st cut-off | 28th of previous month → 12th of current month | 15th of current month |
| 2nd cut-off | 13th → 27th of current month | Last day of current month |

**Paid-date rule (important).** An order's O.P. is assigned to the cut-off period containing its **payment date** — not the order date and not the ledger's effective date. Most agent orders are paid the same day. An unpaid agent order goes to **Collectibles** and earns no commission until paid; once paid, it joins the period of that payment date. (Note for implementation: the system's collection flow records the *sale* on the original order date for ledger immutability, so commission logic must read the payment/collection date explicitly rather than reuse the ledger date.)

**Qualifying orders:** status is Completed/Closed (not cancelled) and the order is fully paid; payment date falls within the cut-off.

**Release workflow**
1. **Draft statement** generated for the period: each agent's order count and O.P. total, all marked Pending.
2. **Review** (optional): an admin may add bonuses or deductions, or exclude specific orders, each with a reason.
3. **Approve and release:** authorised by a user with the **ACCOUNTING role**, who confirms with **their own `admin_security_key`** (single-auth). This finalises the statement and updates each agent's pending balance.
4. **Record payment:** mark paid with method, reference number, and date; updates the agent's released total.

## 2.6 Agent statements

If agents are given portal access they see their own performance (orders and O.P. this month), commissions (pending, released, lifetime), their orders with itemised breakdown, and their monthly statements. If not, the system generates a PDF statement per cut-off — transaction summary, itemised breakdown, and total for verification — to send by email or messaging.

## 2.7 Commission tracking table

`agent_commissions` (master record per agent per period):

| Field | Notes |
|-------|-------|
| Commission id | `COMM-YYYY-NNNN` |
| Agent id | FK |
| Period start / end | Dates |
| Cut-off label | 1st / 2nd |
| Total orders | Count |
| Total O.P. | Sum |
| Bonuses / Deductions | Adjustments |
| Net commission | Total O.P. + bonuses − deductions |
| Status | Draft / Approved / Released / Paid |
| Released date · Payment method · Payment reference | Payment record |
| Approved by · Paid by | Acting users |
| Notes | Adjustment reasons |

Per-line detail backing the statement can be computed on demand for drafts and snapshotted on finalisation so a released statement never changes.

---

# PART 3 — Daily Sales & Expense CSV Upload

## 3.1 Purpose

A single CSV-based channel to record a day's **orders and expenses** in bulk — for catching up after the system was unreachable, or for any day captured in an offline spreadsheet. The governing principle: an uploaded record is **identical in shape to one keyed in live** — same `orders`/`order_items`/`expenses` tables and the same validation — only entered in batch and marked as imported. The feature reuses the existing create and commission paths and adds only an import flag, an upload permission, and the upload+validation pipeline. No manual form, no offline mode, no draft-autosave.

## 3.2 CSV template (three parts)

- **Sales:** Date · Receipt # · Time · Customer (optional) · Source · Agent (only if source = Agent) · Payment Method · Total.
- **Sale Items:** Receipt # · Item Code · Qty · Unit Price · O.P. per Unit (optional; agent orders only).
- **Expenses:** Date · Category · Sub-Category · Amount · Notes · Payment Method · Reference.

A blank template is downloadable from the upload screen.

## 3.3 Validation & conflict resolution

On upload, validate every row before anything is written: item codes against the product list, categories/sub-categories against the seeded set, payment method and source against their enums, agent names against the registry, plus numeric and date formats. Show a preview separating valid rows, rows needing fixes, and duplicates (a receipt # already in the system). For each conflict the operator chooses Skip / Update / Review; only the confirmed set commits. A temporary receipt number (`TEMP-DDMMYY-NNNN`) may be used in the file; on commit a real order id (`DDMMYY-NNNNNN`) is generated and the temp id is kept in the import reference for traceability.

## 3.4 Data behaviour

- Committed sales post to the `transactions` ledger on the row's date (consistent with the existing collection-sale backdating), typed SALE, marked imported.
- Expenses write to `expenses` on the row's date, marked imported.
- Agent O.P. flows into commission through the **existing engine** (not reimplemented), by the order's payment date — so days recorded by CSV keep their commissions in the correct cut-off.
- Each imported record carries who uploaded it and when. Daily and monthly reports include imported entries by their date, with an "imported" indicator/filter to distinguish them from live-entered data.

## 3.5 Permission

Upload edits the financial record (often for a past day), so it is limited to ACCOUNTING/ADMIN, confirmed with the acting user's `admin_security_key`, and may date entries beyond the normal expense backdating window (which this permission overrides).

---

# PART 4 — Integration

## 4.1 Commission vs. expense

Agent O.P. is treated as a **distribution, not an expense**. It is never counted in the operational expense totals; it appears only in commission and cash-flow views. This matches the existing system, where expenses live in their own table and the transaction ledger handles sales and reversals — commissions belong to neither and get their own record.

## 4.2 Cash-flow widget

A month-to-date widget shows Revenue − Expenses − Commissions = Net. Because expenses are stored separately from the transaction ledger, the widget reads the ledger (for revenue/reversals) and the expenses table separately, then subtracts released/forecast commissions as a distinct line so nothing is double-counted.

---

# PART 5 — Build order (reference)

Expense track first (self-contained, nothing customer-facing): E1 schema + role-constraint fix → E2 entry + backdating → E3 void/return → E4 daily report + dashboard widget → E5 monthly report + export. Then agent track (touches live orders): A1 registry → A2 order integration behind a feature flag → A3 commission engine → A4 release workflow + statements → A5 cash-flow widget. Finally the CSV upload track: U1 import schema + permission → U2 CSV template, validation, preview, and commit. (The session-level detail and tests live in `BUILD_PLAN.md`.)

---

# PART 6 — Roles & access

Existing roles: SUPER_ADMIN, ADMIN, ADMINISTRATOR, ACCOUNTING, STAFF, STANDARD_USER. (Note: `ACCOUNTING` must be added to the database role constraint before use — see `BUILD_CONTEXT.md`.) Indicative access:

| Role | Record expense | View expenses | Void | Agent mgmt | Release commission |
|------|----------------|---------------|------|------------|--------------------|
| Staff | Limited categories | Own only | No | No | No |
| Cashier-equivalent | All | Daily only | No | No | No |
| Accounting | All | All | Yes (own key) | Yes | Yes (own key) |
| Manager / Admin | All | All | Yes | Yes | No |
| Super Admin | All | All | Yes | Yes | Yes |

Permission checks are enforced **in the controllers** (JWT + role + security key), consistent with the existing codebase; there is no framework-level role enforcement to rely on.

---

# PART 7 — Schema & API summary

## 7.1 Schema changes (aligned to existing tables)

**New — `expense_categories`:** id, code, name, parent_id (nullable, self-reference), is_system_defined, requires_receipt, is_active, sort_order.

**`expense_items` (existing) — add:** category_id FK (**nullable**, references `expense_categories`).

**`expenses` (existing) — add:** is_voided, voided_at, voided_by (FK users), void_reason.

**`settings` (existing key/value) — add:** an expense backdating-window key.

**`users` (existing) — fix:** add `ACCOUNTING` to the `chk_role` constraint.

**New — `agents`:** id, agent_code, full_name, contact_number, email, territory, status, notes, registered_at, created_by.

**`orders` (existing) — add:** agent_id FK (**nullable**; existing free-text `agent_name` retained for history).

**`order_items` (existing) — add:** base_price (company price), op_per_unit (agent O.P. per unit). The customer price stays in the existing `unit_price`.

**`orders` and `expenses` — add (CSV import):** is_imported (boolean, default false), import_ref (text, nullable — holds the original `TEMP-DDMMYY-NNNN` when one was used). Who/when reuse the existing created_by / created_at.

**New — `agent_commissions`:** as in §2.7, with an optional per-line detail snapshot table backing finalised statements.

## 7.2 API surface (additions)

- **Expenses:** list with filters · create · update · void (with key auth) · daily report · monthly report · category list/manage.
- **Agents:** list · register · update · commission history · agent orders.
- **Commissions:** list · calculate for a period · release (ACCOUNTING role + key) · record payment.
- (Backup endpoints deferred with Part 3.)

---

*End of specification.*
