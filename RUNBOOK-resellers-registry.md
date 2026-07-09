# Runbook — Resellers & Distributors registry (Phase A)

**Branch:** `feat/resellers-distributors-registry` (off `main`)
**Audience:** Claude Code (or engineer) on the **live server**
**Type:** New feature — deploy + verify (schema V98 + backend + frontend).

> **BE TOKEN- AND USAGE-EFFICIENT.** Code is written and compiles. Don't re-investigate or re-plan, no
> subagents, read only what a step names, short output, stop-and-report on failure. **Efficiency must
> NEVER skip the pre-flight or a verification step.**

> **DO NOT TOUCH:** ledger paths, applied migrations, closeDaily, void/cancel/collect flows.

---

## 0. PRE-FLIGHT (run FIRST, every session) — is this already live?
```sql
SELECT 1 FROM flyway_schema_history WHERE version = '98';   -- migration applied?
```
and confirm the sidebar shows **"Resellers & Distributors"**.
- **Both true → STOP.** Already deployed; report "already applied", do nothing.
- Also confirm the working tree is clean and `origin/main` is the expected base before merging/deploying.
  Any discrepancy → **STOP and report; never reset/clean/force.**

## 1. What this delivers
A registry of reseller/distributor partners (mirrors the Agent registry), with per-reseller product
price mapping and order/payment tracking.
- **V98** — `resellers` + `reseller_product_prices` (money `numeric(13,5)` per V96) + `orders.reseller_id` FK.
- **API** `/api/resellers` — CRUD, ACTIVE/INACTIVE toggle, price map get/replace, `/{id}/orders`
  (outstanding-collection totals). `GET /api/orders/reseller-options` feeds the order-form picker.
- **Order form** — source RESELLER/DISTRIBUTOR now loads **registered ACTIVE** entries only; a mapped
  product auto-fills its negotiated price (still editable). `resellerId` saved on the order.
- **Page** "Resellers & Distributors" (page key `resellers`) — cards, price-map editor, order history +
  outstanding ₱. **Collections** page gains a source filter.

Files: `V98__resellers_registry.sql`, `Reseller*.java`, `ResellerController.java`, edits to
`Order.java`/`OrderService`/`OrderController`/`OrderRepository`/`PageAccessInterceptor`/`UserController`,
`index.html` + `js/app.js` (cache-bust `u22`).

## 2. Deploy
```bash
git fetch && git checkout main && git pull --ff-only        # after PR merge
cd rrbm-backend && ./mvnw -q -DskipTests package             # Flyway applies V98 on boot
# restart backend; redeploy static index.html + js/app.js (cache-bust u22 — hard-refresh)
```

## 3. Verify (on an isolated/staging Postgres first — NEVER the shared dev DB)
1. Migration V98 applied (flyway_schema_history) and backend boots clean.
2. Register a reseller + set a price map for 1–2 products.
3. New Order → source **Reseller** → picker lists only registered ACTIVE resellers; pick one; add a
   mapped product → unit price auto-fills the negotiated price (editable); unmapped product → normal price.
4. Force-close that order unpaid → it appears in **Collections** (source filter → Resellers shows it)
   AND in the reseller panel's Order History as outstanding (count + ₱). Collect it → panel shows it
   collected and outstanding drops.
5. Set the reseller INACTIVE → gone from the order-form picker; creating an order with its id is rejected.
6. A user WITHOUT the `resellers` page key: the page is hidden and `GET /api/resellers` returns 403.

## 4. What NOT to do
- ❌ Don't re-apply if the pre-flight says it's live.
- ❌ Don't backfill `reseller_id` onto historical free-text orders — out of scope.
- ❌ Don't touch the Agent flow, ledger, or applied migrations.

## 5. Known scope notes (for sign-off)
- Free-text reseller names are still accepted at the **API** level (CSV import back-compat); the **UI**
  restricts to registered ACTIVE resellers.
- Replacement-order prefill shows the reseller name but does not re-select the FK — re-pick in the form.
- Credit limits / payment terms deliberately excluded (possible fast-follow).

## 6. Rollback
Revert the branch merge + redeploy previous `index.html`/`app.js`. V98 only adds tables + a nullable
FK column; to fully revert, drop `reseller_product_prices`, `resellers`, and `orders.reseller_id`
(no data loss to existing order/finance tables).
