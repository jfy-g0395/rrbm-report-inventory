# BUILD PLAN — Resellers/Distributors Registry + Employee 201 Records

**Audience:** Claude Code (Sonnet) executing **one session per run**. Sessions S-A1…S-A3 then S-B1…S-B3.
**Branches:** Phase A → `feat/resellers-distributors-registry` · Phase B → `feat/employee-201-records`.

> ### RULE — one feature per branch (added by user)
> Each feature is built and kept in its **own dedicated branch** and its own PR. Never mix the two
> features in a single branch. Phase A code lives only in `feat/resellers-distributors-registry`;
> Phase B code lives only in `feat/employee-201-records`. The shared blueprint (this file) is the only
> thing both may reference.

> ### PRE-FLIGHT RECONCILIATION — recorded 2026-07-09 (supersedes stale numbers below)
> `origin/main` advanced to **`0e51159`** (was `298e971`). New since the plan was drafted: scheduled
> delivery (V93/V95), stock transfers (V94), **5-decimal money precision (V96)**, movement-log running
> total (V97). Therefore:
> - **Flyway head = V97.** Renumber: Resellers migration `V93 → V98`; Employee 201 migration `V94 → V99`
>   (re-run the pre-flight at B's build time — if head moved again, bump accordingly).
> - **Money columns use `numeric(13,5)`** now (V96), NOT `numeric(10,2)`. Every money column in the SQL
>   below (reseller `unit_price`, employee `daily_wage`, benefit `amount`) must be `numeric(13,5)` to stay
>   consistent with `order_items.base_price` etc. — otherwise auto-filled prices round-mismatch.
> - Always trust the **live pre-flight head check** over any number written below.

> **BE TOKEN- AND USAGE-EFFICIENT.** This plan is fully designed — do NOT re-investigate, re-plan, or
> spawn subagents. Read only the files a step names. Keep output short. Stop and report on any failure
> or out-of-scope discovery. **Efficiency must NEVER mean skipping pre-flight or verification.**

> **DO NOT TOUCH:** TransactionService ledger paths, applied Flyway migrations, `closeDaily` logic,
> OrderController void/cancel/collect flows, commission machinery. (See DEPLOYMENT-GUIDE.md.)

---

## PRE-FLIGHT — run at the START of EVERY session (codebase-integrity check)
```bash
git fetch --all --prune
git status -sb          # tree MUST be clean (3 known stray files ok: cashflow.patch,
                        # rrbm_import_template.xlsx, deleted rrbm_import_test2.xlsx — leave them alone)
git log --oneline -3 origin/main
ls rrbm-backend/src/main/resources/db/migration/ | sort -V | tail -3   # actual Flyway head
```
- **S-A1 / S-B1:** branch off **latest `origin/main`**. All later sessions: `git checkout` the existing
  feature branch, `git pull`, and confirm the **prior session's commit** is present (`git log --oneline -3`).
- Migration numbers below assume head = **V92**. If the real head differs, **renumber** (next free number)
  and use that number everywhere in the session.
- Any discrepancy — dirty tree, unpushed/missing commits, unexpected head, migration collision —
  → **STOP and report. Never `reset`, `clean`, or force-push.**
- Sessions are strictly scoped. Out-of-scope discovery → note it in the report, don't fix it.

**Per-session close:** `cd rrbm-backend && ./mvnw -q -DskipTests compile` must pass → commit (message
given per session) → push the feature branch. S-A3/S-B3 open the phase PR.

**Design decisions (locked, do not re-litigate):** one combined page with `type` field · mapped price
auto-fills but stays **editable** · benefits = checklist + optional amount/notes, admin-manageable types ·
201 registration = **tabbed** form, only Personal Info required to save.

---

# PHASE A — Resellers & Distributors

Context: `RESELLER`/`DISTRIBUTOR` are already valid order sources (`OrderService.VALID_SOURCES`, V88
check constraint) but today the name is **free text** stored into `orders.agent_name`. The **Agent
registry is the blueprint** — mirror it, minus all commission machinery.

## S-A1 — Backend (branch `feat/resellers-distributors-registry` off main)

**1. Migration `V93__resellers_registry.sql`** (renumber per pre-flight):
```sql
CREATE TABLE resellers (
  id BIGSERIAL PRIMARY KEY,
  reseller_code VARCHAR(20) UNIQUE NOT NULL,        -- RSL-2026-0001 / DST-2026-0001 by type
  type VARCHAR(15) NOT NULL CHECK (type IN ('RESELLER','DISTRIBUTOR')),
  name VARCHAR(150) NOT NULL,
  contact_person VARCHAR(100) NOT NULL,
  contact_number VARCHAR(50) NOT NULL,
  address TEXT NOT NULL,
  notes TEXT,
  delivery_days VARCHAR(100),                        -- CSV of MON..SUN, optional
  delivery_time_window VARCHAR(50),                  -- e.g. '8:00 AM - 12:00 PM', optional
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE/INACTIVE
  registration_date DATE DEFAULT CURRENT_DATE,
  created_at TIMESTAMPTZ DEFAULT now(),
  created_by BIGINT REFERENCES users(id)
);
CREATE TABLE reseller_product_prices (
  id BIGSERIAL PRIMARY KEY,
  reseller_id BIGINT NOT NULL REFERENCES resellers(id) ON DELETE CASCADE,
  product_id BIGINT NOT NULL REFERENCES products(id),
  unit_price NUMERIC(10,2) NOT NULL CHECK (unit_price >= 0),
  UNIQUE (reseller_id, product_id)
);
ALTER TABLE orders ADD COLUMN reseller_id BIGINT REFERENCES resellers(id);
CREATE INDEX idx_orders_reseller ON orders(reseller_id);
```

**2. New Java files** (package `rrbm_backend`, mirror the named precedents):
- `Reseller.java`, `ResellerProductPrice.java` — mirror `Agent.java` (JPA, `@PrePersist`).
- `ResellerRepository.java` — mirror `AgentRepository.java` incl. a `maxSequenceForYear(prefix)`-style
  native query for code generation (`RSL-`/`DST-` prefix by type).
- `ResellerProductPriceRepository.java` — `findByResellerId`, `deleteByResellerId`.
- `ResellerController.java` (`/api/resellers`), mirror `AgentController.java`:
  - `POST /` create (validate name, contactPerson, contactNumber, address, type; auto-code)
  - `GET /?type=&status=&q=` list; enrich each with `{totalOrders, outstandingCount, outstandingAmount}`
    (outstanding = that reseller's orders with `status='PENDING_COLLECTION'`)
  - `GET /{id}` · `PUT /{id}` (code/type/dates immutable) · `PATCH /{id}/status` (activity-logged)
  - `GET /{id}/prices` · `PUT /{id}/prices` (replace-set) · `GET /{id}/orders?status=`

**3. Edits to existing files:**
- `Order.java`: add `resellerId` (Long, column `reseller_id`).
- `OrderController.java`: add `GET /api/orders/reseller-options?type=` — lightweight `{id,name,resellerCode}`
  of ACTIVE resellers, **mirroring the `agent-options` endpoint next to it** (same gating reason: encoders
  need it without the resellers page key).
- `OrderService.buildOrderFromRequest` (near the existing `agentId` handling): accept `resellerId`; when
  source is RESELLER/DISTRIBUTOR **require** it (free text no longer accepted for these sources); look up,
  reject missing/INACTIVE/type-mismatch; set `order.resellerId` AND `order.agentName = reseller.getName()`
  (keeps every existing display/report path working unchanged).
- `PageAccessInterceptor.java` RULES list: add `new Rule("/api/resellers", "resellers")` (keep
  most-specific-first ordering; `/api/orders/reseller-options` stays under the `orders` rule).
- `UserController.java`: add `"resellers"` to `ALL_PAGES` and to admin-ish entries of `ROLE_DEFAULT_PAGES`.

**Verify:** compile green. **Commit:** `feat(resellers): registry backend — V93 schema, CRUD + price map + order hook`

## S-A2 — Frontend (same branch; index.html + js/app.js only)

Mirror the **agents page** (`view-agents` section in index.html; `loadAgents`, `openAgentPanel`,
`openRegisterAgentModal` in app.js; design doc `docs/PLAN-agent-registry-redesign.md`).

1. **Page** `view-resellers` + sidebar nav "Resellers & Distributors" (`data-view="resellers"`), router
   entry in `navigateTo` + `titles` map, loader `loadResellers()`: card grid, type badge (RESELLER amber /
   DISTRIBUTOR purple), tabs All/Resellers/Distributors + search box.
2. **Slide-out panel** `openResellerPanel(id)`: Details · **Price Mapping** (editable rows: product
   autocomplete + price, saved via `PUT /{id}/prices`) · **Order History** (`GET /{id}/orders`): each row
   date, items preview, total, status, PAID/UNPAID badge (reuse `payStatusBadge`); summary strip on top —
   total orders, outstanding count, ₱ outstanding.
3. **Register/edit modal**: required Name/Contact Person/Contact #/Address; Type select; Notes; delivery
   days as Mon–Sun checkboxes + time-window text; price-mapping editor.
4. **Order form**: in `onSourceChange()`, when source is RESELLER/DISTRIBUTOR replace the free-text
   `#field-reseller` with an autocomplete over `GET /api/orders/reseller-options?type=<source>` + hidden
   `#field-reseller-id` — **copy `setupAgentAutocomplete`/`renderAgentDropdown`/`loadAgentOptions`
   wholesale**. Submit payload adds `resellerId`. On product-line selection with a reseller chosen:
   fetch/cache that reseller's price map; if the product is mapped, **auto-fill unit price (leave
   editable)**; unmapped → normal price.
5. **Collections page**: add a client-side source filter dropdown (All/Reseller/Distributor/Others) to
   the pending list — filter `_collectionsParsed` in `renderCollectionRows()`; no backend change.
6. **Page-access wiring** (all 4 frontend spots): `viewToPageKey` add `'resellers':'resellers'`;
   `ROLE_DEFAULT_PAGES` in app.js; a "Resellers & Distributors" checkbox in BOTH `#add-emp-page-access`
   and `#edit-emp-page-access` blocks in index.html. **Cache-bust:** bump `js/app.js?v=uNN` → `uNN+1`
   in index.html (find the current token first).

**Verify:** compile n/a; load UI if a preview is available, else static review. **Commit:**
`feat(resellers): registry page, order-form autocomplete + price auto-fill, collections source filter`

## S-A3 — Verify + runbook (same branch)

1. On an **isolated Postgres** (NEVER the shared dev DB — `*IT` suites wipe it): boot app → V93 applies.
2. Functional: register reseller + price map → order with source RESELLER shows only registered ACTIVE
   entries; mapped product auto-fills mapped price (editable); force-close unpaid → appears in Collections
   (filter works) and in the reseller panel as outstanding; collect → PAID, outstanding drops. INACTIVE
   reseller vanishes from dropdown and is rejected server-side. No `resellers` page key → page hidden and
   `/api/resellers` 403s.
3. Write `RUNBOOK-resellers-registry.md` (house style: efficiency banner; §0 pre-flight
   `SELECT 1 FROM flyway_schema_history WHERE version='93'` + UI check → STOP if already live; deploy;
   verify; what-NOT-to-do; rollback).
4. Push; open PR to `main` titled `feat(resellers): Resellers & Distributors registry with price mapping`.
   PR must flag: free text no longer accepted for RESELLER/DISTRIBUTOR sources; historical free-text
   orders keep `agent_name` only (no backfill); credit limits/payment terms deliberately excluded.

---

# PHASE B — Employee 201 Records

Branch `feat/employee-201-records` off latest `main` **after Phase A merges** (else off the Phase A branch —
state which in the PR). Migration below assumes head V93 → use **V94** (renumber per pre-flight).

## S-B1 — Backend + rename

**1. Rename "Employee List" → "User List" (display strings ONLY).** Sidebar span in index.html
(`id="nav-emp"` block), the `view-emp` card title, and the `emp` entry in the `titles` map in app.js
(`['Employee List', …]` → `['User List', 'Manage system users']`). Optionally checkbox label "Employees"
→ "Users" in both page-access blocks. **Do NOT rename** the `employees` page key, `emp` view id, element
ids, or `/api/users`.

**2. Migration `V94__employee_201.sql`:**
```sql
CREATE TABLE employees (
  id BIGSERIAL PRIMARY KEY,
  employee_code VARCHAR(20) UNIQUE,                  -- EMP-2026-0001, auto
  last_name VARCHAR(80) NOT NULL, first_name VARCHAR(80) NOT NULL,
  middle_name VARCHAR(80), maiden_name VARCHAR(80),
  birthdate DATE NOT NULL,                           -- age is COMPUTED in responses, never stored
  nationality VARCHAR(50), civil_status VARCHAR(20), gender VARCHAR(20),
  position VARCHAR(100) NOT NULL, date_of_employment DATE NOT NULL,
  email VARCHAR(150), spouse_name VARCHAR(150),
  contact_number VARCHAR(50) NOT NULL, address TEXT,
  sss_number VARCHAR(30), pagibig_number VARCHAR(30), philhealth_number VARCHAR(30),
  photo TEXT,                                        -- base64 data-URL (2x2)
  employment_status VARCHAR(20) NOT NULL DEFAULT 'PROBATIONARY',  -- PROBATIONARY/REGULAR/CONTRACTUAL
  probation_end_date DATE,
  daily_wage NUMERIC(10,2),
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE/RESIGNED/TERMINATED
  created_at TIMESTAMPTZ DEFAULT now(), created_by BIGINT REFERENCES users(id)
);
CREATE TABLE employee_education (
  id BIGSERIAL PRIMARY KEY, employee_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
  level VARCHAR(20) NOT NULL,                        -- PRIMARY/SECONDARY/TERTIARY/VOCATIONAL/GRADUATE
  school_name VARCHAR(150), year_graduated VARCHAR(10)
);
CREATE TABLE employee_work_history (
  id BIGSERIAL PRIMARY KEY, employee_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
  employer_name VARCHAR(150), year_started VARCHAR(10), year_ended VARCHAR(10), position VARCHAR(100)
);
CREATE TABLE benefit_types (
  id BIGSERIAL PRIMARY KEY, name VARCHAR(80) UNIQUE NOT NULL,
  is_government BOOLEAN DEFAULT FALSE, active BOOLEAN DEFAULT TRUE
);
INSERT INTO benefit_types (name, is_government) VALUES
  ('SSS', TRUE), ('PhilHealth', TRUE), ('Pag-IBIG', TRUE), ('HMO', FALSE), ('Food Allowance', FALSE);
CREATE TABLE employee_benefits (
  id BIGSERIAL PRIMARY KEY, employee_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
  benefit_type_id BIGINT NOT NULL REFERENCES benefit_types(id),
  amount NUMERIC(10,2), notes VARCHAR(255), UNIQUE (employee_id, benefit_type_id)
);
CREATE TABLE employee_events (                       -- append-only milestone timeline
  id BIGSERIAL PRIMARY KEY, employee_id BIGINT NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
  event_type VARCHAR(30) NOT NULL,   -- SALARY_CHANGE/POSITION_CHANGE/STATUS_CHANGE/MEMO/ADDENDUM/NOTE
  event_date DATE NOT NULL DEFAULT CURRENT_DATE,
  old_value VARCHAR(150), new_value VARCHAR(150),
  details TEXT,
  created_at TIMESTAMPTZ DEFAULT now(), created_by BIGINT REFERENCES users(id)
);
```

**3. New Java files:** entities + repos for the 6 tables (plain JPA, `Agent.java` style);
`EmployeeController.java` (`/api/employees`):
- `POST /` (only Personal-Info fields required: lastName, firstName, birthdate, position,
  dateOfEmployment, contactNumber; auto `employee_code`)
- `GET /?status=&q=` · `GET /{id}` (full 201: education, workHistory, benefits, events, computed `age`)
- `PUT /{id}` — **auto-append events**: if `daily_wage` / `position` / `employment_status` changed, insert
  the matching `employee_events` row (old→new) in the same transaction
- `PATCH /{id}/status` · `POST /{id}/events` (MEMO/ADDENDUM/NOTE: eventDate, details)
- `GET/PUT /{id}/benefits` · `GET /api/employees/benefit-types` · `POST/PATCH .../benefit-types` (admin roles only)

**4. Access:** `PageAccessInterceptor` add `new Rule("/api/employees", "employee-201")` (no collision
with `/api/users`); `UserController` `ALL_PAGES` + `ROLE_DEFAULT_PAGES` add `"employee-201"`.

**Verify:** compile green. **Commit:** `feat(employee-201): 201-file backend — V94 schema, CRUD, auto milestone events; rename Employee List → User List`

## S-B2 — Frontend: page + tabbed registration

1. **Page** `view-emp201`, nav "Employee 201" (`data-view="emp201"`, page key `employee-201`), router +
   titles entry, `loadEmployees201()`: table/cards — photo thumbnail, code, name, position,
   employment-status badge (Probationary amber / Regular green / Contractual blue), date hired, search.
2. **Registration modal — 4 tabs** (tab switching = simple show/hide divs; only Personal Info blocks save):
   - **Personal Info** (required marks per schema): all personal fields; **Age auto-displays** from
     birthdate (never posted); Spouse field visible only when Civil Status = Married; **2×2 photo** —
     file input `accept="image/*"`, **copy the `previewEmpImage` base64 pattern**, and downscale via
     canvas to ≤400×400 before storing (nginx default 1 MB body limit).
   - **Education**: 5 fixed rows (Primary/Secondary/Tertiary/Vocational/Graduate School) × School Name +
     Year Graduated, all optional.
   - **Employment History**: add/remove row repeater (Employer, Year Started, Year Ended, Position).
   - **Compensation & Benefits**: Daily Wage; Employment Status select (+ Probation End Date shown when
     Probationary); benefits checklist rendered from `GET benefit-types` — checking reveals amount +
     notes inputs; small admin-only "+ new benefit type" affordance.
3. **Page-access wiring** (4 frontend spots, same as S-A2 step 6) + **cache-bust bump**.

**Commit:** `feat(employee-201): 201 page + tabbed registration with photo, education, work history, benefits`

## S-B3 — Timeline + verify + runbook

1. **Detail/edit view**: same 4 tabs pre-filled + **Timeline** tab — newest-first `employee_events` with
   type badges, old→new rendering, details; buttons "Add Memo" / "Add Addendum" / "Add Note" → `POST /{id}/events`.
2. Functional verify (isolated Postgres): V94 applies; register via Personal-only → saves; photo renders;
   age correct; edit wage 610→650 → SALARY_CHANGE event appears; position change → event; status
   Probationary→Regular → event; memo posts; benefits amounts persist; new benefit type appears without
   code change; page hidden + API 403 without the `employee-201` key; "User List" label correct and old
   users page fully functional.
3. Write `RUNBOOK-employee-201.md` (house style; §0 pre-flight `version='94'` + UI check → STOP if live).
4. Push; open PR `feat(employee-201): Employee 201 records with milestone timeline`. Flag: photo stored
   as base64 in DB (watch body size); payroll/leave/document-attachments deliberately excluded.

---

## Out of scope — BOTH phases (do not do, even if tempting)
- No commission logic for resellers; no credit limits/payment terms (fast-follow candidates).
- No payroll computation, leave tracking, or file attachments beyond the 2×2 photo.
- No backfill of `reseller_id` onto historical free-text orders.
- No changes to ledger, closeDaily, void/cancel/collect, applied migrations.
