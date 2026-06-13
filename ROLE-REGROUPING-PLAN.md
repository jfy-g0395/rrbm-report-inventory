# Role Regrouping & Order-Action Authorization — Build Plan

> **Scope lock.** This document defines the *entire* scope of this work. Implement **only** what is
> listed here. No drive-by refactors, no extra endpoints, no renames outside the named files. Each
> session is independently buildable, testable, and self-contained. Do one session per chat to keep
> context small.

## Confirmed decisions
- Merge **STAFF → STANDARD_USER** (one role).
- Accounting **includes** Payables.
- Standard User lands on **Order List** (no Dashboard).
- **Only Accounting + Super Admin** may void/cancel/refund (Administrators lose it).
- Picking a role **auto-fills** page checkboxes; **Super Admin can customize**, others are locked.

## Role → default page matrix (single source of truth)
Page keys (19): `dashboard, orders, order-history, daily-reports, inventory, purchase-orders,
receive-stocks, rejected-items, reports, delivery-reports, activity-log, employees, expenses,
payables, suppliers, collections, ledger, agents, import`
("New Order" + "Order List" share `orders`. Settings = Super-Admin-only, not a key.)

| Role | Default pages |
|------|---------------|
| **STANDARD_USER** | `orders, rejected-items, receive-stocks, inventory, delivery-reports` |
| **ACCOUNTING** | `dashboard, orders, daily-reports, inventory, purchase-orders, receive-stocks, rejected-items, reports, expenses, payables, suppliers, collections, ledger, agents, import` |
| **ADMINISTRATOR** | all 19 keys |
| **SUPER_ADMIN** | bypass (sees all incl. Settings) |

---

## Session 1 — Remove the STAFF role ✅ DONE (2026-06-13)
**Goal:** STAFF no longer exists; existing STAFF users become STANDARD_USER.

**Backend**
- `db/migration/V71__remove_staff_role.sql` (new): `UPDATE users SET role='STANDARD_USER' WHERE role='STAFF';`
  then drop/recreate `chk_role` as `CHECK (role IN ('SUPER_ADMIN','ADMIN','ADMINISTRATOR','ACCOUNTING','STANDARD_USER'))`;
  `ALTER TABLE users ALTER COLUMN role SET DEFAULT 'STANDARD_USER';`
- `User.java:27` — field default `"STAFF"` → `"STANDARD_USER"`.
- `UserController.java:24` — remove `"STAFF"` from `VALID_ROLES`.

**Frontend**
- `index.html` — remove `<option value="STAFF">` from `add-emp-role` (~3286), `edit-emp-role` (~3441),
  `assign-role-select` (~3582).
- `app.js` `roleBadge` (~184) — leave the STAFF entry as a harmless legacy fallback (no change needed).

**Acceptance:** app boots on schema **v71**; no Staff in any dropdown; an existing STAFF account now
shows as Standard User. Keep `ADMIN` legacy alias untouched.

**Delivered:** V71 migration applied; `User.java` default → `STANDARD_USER`; `VALID_ROLES` cleaned;
`index.html` Staff options removed from all 3 dropdowns. `roleBadge` left as legacy fallback.
60 smoke-test assertions green.

---

## Session 2 — Make Dashboard/Collections/Ledger/Agents/Import restrictable (plumbing only) ✅ DONE (2026-06-13)
**Goal:** the five currently-open-to-all pages become enforceable keys. No role defaults yet.

**Backend**
- `PageAccessInterceptor.java` `RULES` — add (specific prefixes first):
  `/api/dashboard`→`dashboard`, `/api/import`→`import`, `/api/transactions`→`ledger`,
  `/api/orders/collections`→`collections`, `/api/orders/batch-mark-collected`→`collections`
  (both **before** `/api/orders`→`orders`); change `/api/agents` from `employees`→`agents`.
- `WebMvcConfig.java` — remove `"/api/dashboard/**"` from `excludePathPatterns`. Keep all other
  exclusions (incl. `/api/users/*/change-password`).

**Frontend**
- `app.js` `viewToPageKey` (~326) — add `'dash':'dashboard'`, `'collections':'collections'`,
  `'transactions':'ledger'`, `'agents':'agents'`, `'import':'import'`. **Remove** the
  `'agents':'employees'` line added earlier this session.
- `index.html` — add five checkboxes (**Dashboard, Collections, Ledger, Agents, Import**) to **both**
  `#add-emp-page-access` (~3330) and `#edit-emp-page-access` (~3485) grids.

**Acceptance:** with a hand-set `allowedPages` lacking `dashboard`, `GET /api/dashboard` → 403 and the
Dashboard nav item hides. Existing Super-Admin/Administrator (defaults/bypass) unaffected.

**Delivered:** `PageAccessInterceptor` RULES updated (5 new keys, agents remapped); `WebMvcConfig`
dashboard exclusion removed; `app.js` `viewToPageKey` updated; `index.html` 5 checkboxes added to
both access grids. Stale "STAFF" reference in `AuthorizationGateIT.t06` fixed → "STANDARD_USER".
39 assertions green (DashboardIT 15, AuthorizationGateIT 13, CollectionsIT 11).

---

## Session 3 — Role-default auto-fill + Super-Admin customization ✅ DONE (2026-06-13)
**Goal:** picking a role fills the checkboxes; non-Super-Admin is locked to the role default.
Depends on Session 2 keys existing.

**Backend** (`UserController.java`)
- Add `ROLE_DEFAULT_PAGES` (the matrix above) as JSON strings; keep an `ALL_PAGES` = Administrator set.
- **Create POST (~107–144):** caller not SUPER_ADMIN → force `allowedPages = ROLE_DEFAULT_PAGES[role]`
  (ignore body). Caller is SUPER_ADMIN → honor body, else role default.
- **Role PATCH `/{id}/role` (238):** after `setRole`, also set `allowedPages = ROLE_DEFAULT_PAGES[newRole]`.
- **Edit PUT `/{id}` (206–215):** if role changes and caller not SUPER_ADMIN, reset `allowedPages` to
  new role default. `/permissions` PATCH stays Super-Admin-only (explicit custom) — unchanged.

**Frontend** (`app.js` + `index.html`)
- `ROLE_DEFAULT_PAGES` map (mirror backend) + `applyRoleDefaultPages(prefix, role)` ticking
  `#<prefix>-emp-page-access` boxes; `onRoleSelectChange(prefix)` wired to both role selects
  (`onchange` added in `index.html`) and called on modal open.
- Non-Super-Admin caller → checkboxes **disabled** (reuse edit-form disable at ~4138; replicate for
  add form). Disabled-but-checked boxes are still read by `:checked`, so the role default saves.
- Login landing (~5966): `navigateTo(canAccessPage('dash') ? 'dash' : 'list')`; guard post-login
  `renderDashboard()/renderTopProductsToday()/loadProductAnalytics()` with `canAccessPage('dash')` and
  `updateCollectionsBadge()` with `canAccessPage('collections')`.

**Delivered:** `ALL_PAGES` updated to 19 keys (was 14, missing Session 2 additions); `ROLE_DEFAULT_PAGES`
map added; POST/PATCH-role/PUT handlers enforce role defaults per caller; frontend `ROLE_DEFAULT_PAGES`
+ `applyRoleDefaultPages` + `onRoleSelectChange` wired to both role selects; add-modal pre-fills and
locks checkboxes for non-Super-Admin; login landing guards dashboard route and data loads.
Two stale STAFF references in `UserCreateUpdateGateTest` + `ImportU1Test` updated to STANDARD_USER.
159 assertions green.

---

## Session 4 — Order void/cancel/refund → Accounting + Super Admin only ✅ DONE (2026-06-13)
**Goal:** add a role gate on top of the existing key gate. Independent of S1–S3.

**Backend** (`OrderController.java`)
- Helper `isOrderManager(User)` → `SUPER_ADMIN || ACCOUNTING`.
- In `cancelOrder` (377), `voidOrderItems` (671), `processReturn` (796),
  `cancelOrderForReplacement` (957): after resolving caller, **before** the key check, return **403**
  if not `isOrderManager`. Existing security-key / master-key checks stay.

**Frontend** (`app.js`)
- `canManageOrders()` (~173) → `['SUPER_ADMIN','ACCOUNTING']` (drop ADMINISTRATOR + ADMIN).

**Tests** (`rrbm-backend`)
- `mvn test -Dtest=OrderCancelIT,OrderVoidReturnIT,CollectionsIT` — confirm green (seed users are
  Accounting/Super-Admin-eligible).
- Add one case each to `OrderCancelIT` + `OrderVoidReturnIT`: an **ADMINISTRATOR** caller → **403** on
  cancel/void/return, no state change.

**Acceptance:** Accounting + Super Admin can void/cancel/refund; Administrator gets 403 and the UI
buttons are hidden.

**Delivered:** `isOrderManager` helper added; role gate inserted in all 4 endpoints (`cancelOrder`,
`voidOrderItems` — caller loaded early to avoid double DB hit in TIER_1 branch, `processReturn`,
`cancelForReplacement`); `canManageOrders()` narrowed to `['SUPER_ADMIN','ACCOUNTING']`.
`t06` added to `OrderCancelIT`; `t11`+`t12` added to `OrderVoidReturnIT`.
29 assertions green (CollectionsIT 11, OrderCancelIT 6, OrderVoidReturnIT 12).

---

## Per-session run/verify
- Backend boot/migrate: `cd rrbm-backend && mvn spring-boot:run` (schema must reach **v71** after S1).
- Frontend served at `http://localhost:5500` (CORS-allowed origin), not `file://`.
- Touch tests only where named above; do **not** broaden the suite in scope of this plan.
