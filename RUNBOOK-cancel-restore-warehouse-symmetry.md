# Runbook — Fix #3: cancel/void warehouse-symmetric restore + concurrency + breathing time

**Branch:** `fix/cancel-restore-warehouse-symmetry` (based on latest `main`)
**Audience:** Claude Code (or engineer) on the **live server**
**Severity:** High — core inventory correctness (phantom per-warehouse stock loss on cancel)

> **BE TOKEN- AND USAGE-EFFICIENT.** This branch is written and compiles. Don't re-investigate or
> re-plan, don't spawn subagents, read only what a step names, keep output short, stop and report on
> any failure. **Efficiency must NEVER mean skipping a verification step or the tests — if in doubt,
> stop and ask.**

> ⚠️ **This changes core inventory/money logic and could NOT be run locally** (the dev DB can't
> start the app — Flyway drift). **You MUST run the tests on a proper environment before trusting
> this** (see §4). Do not deploy to production without a green test run + the manual checks.

---

## 1. What this branch changes
1. **Warehouse-symmetric restore.** Cancel/void now returns stock to the exact warehouse(s) and
   quantities still outstanding, by replaying the order's `inventory_movements` ledger
   (`InventoryService.restoreOutstandingFromMovements`). Fixes: SET deductions split across
   warehouses being dumped into one on cancel, and double-restoring units a prior void already
   returned. Non-delivered cancel and the non-delivered branch of cancel-for-replacement both use it.
2. **Concurrency (rapid cancels can't lose stock).** Restores use **atomic in-DB increments**
   (`ProductRepository.addStockWhN`); deductions take a **pessimistic row lock**
   (`findByIdForUpdate`) so overlapping stock changes on the same product serialize instead of
   overwriting each other (lost update).
3. **Breathing-time UI guard.** The Cancel dialog ignores rapid double-submits and disables the
   button while a request is in flight (`confirmCancel`).
4. **Non-delivered cancel no longer asks for a restock warehouse** (stock auto-returns to origin);
   the picker + its validation are removed for that case. Delivered returns keep the
   SELLABLE/REJECTED choice and the warehouse picker.

No DB migration. Deduction math is otherwise unchanged.

Files: `InventoryService.java`, `ProductRepository.java`, `OrderService.java` (cancel-for-replacement
validation), `index.html`/`app.js` (cancel dialog), new test `CancelRestoreWarehouseIT.java`.

> Depends on the Inventory Movement Log (`fix/collections-paymode-and-inventory-movement-log`) for
> diagnosis/verification — deploy or merge that too so you can inspect movements.

## 2. Step 0 — confirm the reported bug on real data (do FIRST)
Inspect the orders the user reported cancelled on **2026-07-02**:

    020726-000521, 020726-000518, 020726-000514, 020726-000513

Via the Movement Log tab (filter those dates/products) or:
```sql
SELECT product_id, warehouse, movement_type, quantity, created_at
FROM inventory_movements
WHERE reference_id IN ('020726-000521','020726-000518','020726-000514','020726-000513')
ORDER BY product_id, warehouse, created_at;
```
For each product+warehouse compare `ORDER_OUT` (out) vs `CANCELLED_RETURN`/`ITEM_VOID` (back in).
Record what you find (e.g. WH split on deduct, single-WH return, or missing return rows = lost
update). This is the ground truth the fix is meant to correct.

## 3. Deploy
```bash
git fetch && git checkout fix/cancel-restore-warehouse-symmetry
cd rrbm-backend && ./mvnw -q -DskipTests package   # (run tests first per §4)
# restart backend; deploy static index.html + js/app.js (cache-bust)
```

## 4. Verify (REQUIRED — this is core stock logic)
1. **Run the regression test** on a real Postgres (isolated/staging DB — NOT the shared dev DB that
   the `*IT` suite wipes):
   `./mvnw -Dtest=CancelRestoreWarehouseIT test` → must pass (split-warehouse SET cancel returns
   each warehouse to its exact pre-order level).
2. **Manual — split warehouse:** a SET whose component has stock in two warehouses; order enough to
   pull from both; cancel → each warehouse returns to its original count (check the Inventory table
   and Movement Log).
3. **Manual — rapid cancels:** cancel several orders of the same product quickly; totals stay
   correct (no lost updates); the Cancel button is disabled mid-request.
4. **Manual — non-delivered cancel:** the restock-warehouse picker is gone and stock returns to
   origin automatically. Delivered return still shows disposition + warehouse picker.

## 5. Existing skewed data (do NOT auto-fix)
This fix prevents new occurrences; it does not repair past discrepancies. Use the Movement Log to
find products whose per-warehouse history doesn't reconcile and correct them with the manual
stock-adjustment tool. No bulk data script — too risky.

## 6. Rollback
Redeploy the previous backend build + previous `index.html`/`app.js`. No schema to revert.
