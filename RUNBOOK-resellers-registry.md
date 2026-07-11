# Runbook — Resellers & Distributors registry (Phase A)

## ✅ STATUS: DEPLOYED — 2026-07-11
Merged into `main` via PR #5 (merge commit `dff8b60`), pushed to `origin/main`, and deployed via
`docker compose up -d --build`. `flyway_schema_history` confirms **V98** (`resellers registry`) applied
successfully on the live DB (2026-07-11 02:40, schema now at v99). Validated on an isolated Postgres clone
before merge (§3 all green). During validation a 500-on-create bug in `ResellerRepository.outstandingForReseller`
result mapping was found and fixed (commit `3c5b36c`). Live `GET /api/resellers` returns 200; frontend
cache-bust is `u27` (bumped past `main`'s `u25`). **Do not re-run §2 deploy steps — this is done.**
Everything below is kept for reference/rollback only.

<!-- superseded build-status note below -->
### 🟡 (historical) BUILT — in PR #5, NOT yet tested / merged / deployed (updated 2026-07-09)
All three build sessions are complete and pushed to `feat/resellers-distributors-registry`:
- **S-A1** backend — `da142fa` (V98 schema, `/api/resellers` CRUD + price map + order hook). Backend
  `./mvnw compile` is **green**.
- **S-A2** frontend — `61070de` (page, order-form picker + price auto-fill, collections source filter,
  access wiring, cache-bust `u22`). `node --check js/app.js` **passes**.
- **S-A3** runbook — `c132124` (this file). Plan reconciled at `14cedd7`.
- **PR:** https://github.com/jfy-g0395/rrbm-report-inventory/pull/5 (**open**).

**Pre-flight reconciliation (why V98):** built off `origin/main` `0e51159`; head was **V97** (not the plan's
V92), so the migration was renumbered `V93 → V98` and all money columns set to `numeric(13,5)` per V96.
No collisions.

**Done:** code written, committed, pushed; compiles; JS syntax valid.
**NOT done (in order):**
1. ⏳ **Functional test** on an isolated/staging Postgres (§3) — nothing has touched a database yet.
2. ⏳ **Review + merge PR #5** into `main` (human gate).
3. ⏳ **Deploy to live** (§2) — back up the live DB before applying V98.

Do **not** deploy until 1–2 are done. Follow §0 pre-flight every session. This block is the source of
truth for progress — update it as stages complete (mark ✅ DEPLOYED with the merge commit + Flyway
confirmation, like `RUNBOOK-daily-report-pizza-boxes-dispatched.md`, once live).

---

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
