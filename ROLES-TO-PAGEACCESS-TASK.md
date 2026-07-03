# ROLES → PAGE-ACCESS TASK (resume doc)

Retire the `REJECT_MANAGEMENT` and `ACCOUNTING_PLUS` **roles** and re-express their powers as
grantable **page-access permissions** (checkboxes in the employee page-access grid).

> **Separate task** from the Delivery Schedule feature. It consumes Flyway **V91**.

## STATUS: code complete + ephemeral-clone VERIFIED — NOT deployed / NOT committed
All edits are on disk in the working tree only. The live app still runs the old JAR + old
frontend (`?v=u7`) and V91 has **not** run, so live behavior is unchanged.

### Ephemeral-clone verification — PASSED (2026-07-03)
Isolated compose project `rrbm_verify` (own volume, no host ports, no `container_name`), restored
from a fresh read-only prod `pg_dump`, backend built from the current working tree:
- **Java compiles** (first-ever compile — no local JDK) and image builds clean.
- Flyway: validated 88 migrations, migrated **V90 → V91 success**, `ddl-auto=validate` passed, app **healthy**.
- Data: 2 ACCOUNTING users (id 3, 4) backfilled with **both** keys; 4 ADMINISTRATORs (id 5–8) + others
  have **neither** (correct); exactly 1 occurrence each (idempotent, no dupes). No
  ACCOUNTING_PLUS / REJECT_MANAGEMENT users exist (matches decision #5).
- Clone torn down (`down -v`), prod dump deleted; prod containers untouched + healthy.
- NOT behaviorally tested via HTTP (needs live user creds); the gate enforcement is covered by the
  fixed ITs (OrderVoidReturnIT t11/t12, OrderCancelIT t06) + code review. Optional: run those ITs on a clone.

### SCOPE DECISION (user, 2026-07-03): ship ROLES ONLY
Do **not** deploy/commit the Delivery Schedule Session-0 work — that feature is not done. The working
tree currently **interleaves both tasks** in shared files (`UserController.java`, `app.js`,
`index.html`) and `ProductController.java` is delivery-only (StockSantan→Balagtas). So the eventual
roles-only commit must **separate the delivery hunks out** (they stay in the tree for the delivery task):
delivery-only hunks = `DELIVERY_MANAGEMENT` role/option/badge/default-pages, `delivery-schedule`
page + nav + view + checkboxes + `loadDeliverySchedule` stub + viewToPageKey/title, and all of
`ProductController.java`. The clone build above included both (inseparable without editing the tree);
nothing was committed or deployed. Do the clone check again before any prod deploy (per the runbook).

## Locked decisions (from Q&A)
1. Both roles removed entirely (VALID_ROLES, dropdowns, badges, ROLE_DEFAULT_PAGES).
2. Two new permission keys, stored in the existing `allowed_pages` JSON array:
   - `add-rejected-items` → add/edit/delete manual rejected items (was REJECT_MANAGEMENT).
   - `void-cancel-orders` → void / cancel / return / correct orders (was the ACCOUNTING role's power).
3. **Agent base pricing** (ACCOUNTING_PLUS's only real power) → **admins only** (SUPER_ADMIN + ADMINISTRATOR). Frontend-gated; no backend enforcement existed.
4. **Defaults preserve today's behavior:** only **ACCOUNTING** gets both new perms by default
   (SUPER_ADMIN bypasses all). ADMINISTRATOR gets **neither** by default (matches today — admins
   currently can't void/cancel or add-rejected) but can be granted via the checkboxes.
5. No live user holds either role (checked latest backup), so no role-remap needed — but V91 has a
   defensive no-op remap anyway.

## How the permission model works (grounding)
- `allowed_pages` = JSON array of page/capability keys per user. Enforced server-side by
  `PageAccessInterceptor` (path→page-key) AND, for these two action-keys, by explicit controller checks.
- Capability rule (both sides): **SUPER_ADMIN or allowed_pages==null → unrestricted; else must contain the key.**
- No whitelist validates `allowed_pages` keys, so the new action-keys store freely. They are NOT in
  `ALL_PAGES`, so admins (ALL_PAGES default) don't get them — matching decision #4.
- Only **Super Admin** can customize a user's `allowed_pages` (create uses role default; `PATCH /{id}/permissions` is Super-Admin-only).

## Files changed
**Backend**
- `User.java` — added `hasPagePermission(String key)` (SUPER_ADMIN + null-pages unrestricted; quoted-token match).
- `UserController.java` — removed both roles from `VALID_ROLES` + `ROLE_DEFAULT_PAGES`; added `void-cancel-orders` + `add-rejected-items` to the ACCOUNTING default.
- `DailyReportController.java` — `canManageManualRejected` → `u.hasPagePermission("add-rejected-items")`; comment updated.
- `OrderController.java` — `isOrderManager` → `u.hasPagePermission("void-cancel-orders")`; 5 FORBIDDEN messages + 1 javadoc updated.
- `Product.java` — comment (Accounting+ → admins).
- `db/migration/V91__rejectmgmt_accountingplus_to_permissions.sql` — **NEW**. Defensive role remap (no-op) + idempotent backfill of both keys onto existing ACCOUNTING users (Clowy id=3, Katherine id=4) so they keep abilities. `chk_role` intentionally left permissive.

**Frontend**
- `js/app.js` — added `hasPagePermission(key)`; `canManageOrders`/`canManageRejected` → permission-based; `canViewAgentPricing` → admins only; removed both from `ROLE_DEFAULT_PAGES` + `roleBadge`; added the two keys to ACCOUNTING default; comments updated. Note: `pricingOnly` (edit-product lock) is now always false (inert, harmless).
- `index.html` — removed both `<option>`s from all 3 role selects (add-emp / edit-emp / assign-role); added "Void & Cancel Orders" + "Add Rejected Items" checkboxes to BOTH page-access grids; "Agent Base Price (Accounting+ only)" → "(Admins only)" ×2; cache-bust **`app.js?v=u7 → u8`**.

**Tests** (skipped at Docker build via `-DskipTests`; fixed for clone runs)
- `OrderVoidReturnIT.java` t11/t12 and `OrderCancelIT.java` t06: seeded admin now gets explicit
  `allowed_pages = ["orders"]` (has Orders page, lacks the capability) so the 403 comes from the
  action gate, not the interceptor. (seedUser leaves pages null = unrestricted, which would
  otherwise let the admin through.)

**Tracker bookkeeping**
- `DELIVERY-SCHEDULE-FEATURE.md` — since this task took V91, its migrations shifted: stock_transfers → **V92**, order_scheduled_delivery → **V93**; "next free" is now V92.

## Verification (ephemeral clone — do NOT test on prod)
1. Restore latest `pg_dump` into an isolated compose project; `docker compose up -d --build`.
2. Confirm Flyway reaches **V91** and app healthy.
3. Accounting users (Clowy/Katherine) can still **void/cancel** + **add rejected items**.
4. An ADMINISTRATOR is **denied** both (they lack the keys) — expect 403.
5. Super Admin can tick either checkbox for any user → ability turns on.
6. Agent base price field hidden for non-admins; visible for SUPER_ADMIN/ADMINISTRATOR.
7. Both roles absent from create/edit/assign dropdowns; regression: normal orders still record.
8. (Optional) run backend ITs on the clone — esp. OrderVoidReturnIT, OrderCancelIT, AuthorizationGateIT.

## Deploy (only after clone passes; this machine IS prod)
1. Back up prod (`pg_dump`).
2. `docker compose up -d --build` — rebuilds backend (V91 auto-applies), redeploy frontend (cache-bust u8).
3. Confirm Flyway at V91, app healthy, spot-check an accounting user can still void/cancel.
4. Commit + push (backend + frontend + this doc + tracker).

## Gotchas
- Deploy is one `compose up` so backend (V91) + frontend (u8) go together; a few-second rollover
  window where an accounting user could see 403 on void/cancel is acceptable.
- `null allowed_pages` = unrestricted (legacy). Real non-super users have explicit arrays, so this
  only matters for test seeds (handled).
- Do not narrow `chk_role`; leaving the retired roles allowed at the DB is harmless (app won't set them).
