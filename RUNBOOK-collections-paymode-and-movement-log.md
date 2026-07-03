# Runbook — Collections payment gate (#1) + Inventory Movement Log (#2)

**Branch:** `fix/collections-paymode-and-inventory-movement-log` (based on latest `main`)
**Audience:** Claude Code (or engineer) applying this on the **live server**
**Severity:** Medium-High — collections wrongly credited cash-on-hand for non-cash payments

> **BE TOKEN- AND USAGE-EFFICIENT.** This branch is written, compiled, and syntax-checked. Do NOT
> re-investigate or re-plan, and do not spawn subagents. Run the steps below in order, read only
> what a step names, keep output short, and stop to report the moment a step fails.
> **Efficiency must NEVER mean skipping a verification step — if in doubt, stop and ask.**

> **No new database migrations in this branch.** But the live DB must already be on the current
> schema. If the app fails to start with a Flyway/Hibernate error, resolve the migration drift
> first (see the previous runbook `RUNBOOK-add-records-and-import-fixes.md`, section 2.3).

---

## 1. What this branch changes

**#1 — Collections payment gate**
- The collect dialog now has a **Payment Method** dropdown, pre-filled with the order's recorded
  mode (COD → Cash), editable before confirming. Only **Cash** adds to cash-on-hand; Bank Transfer
  / GCash / PayMaya are recorded but do not touch the drawer.
- Backend `collectOrder` stamps the chosen method onto the order and only posts cash-on-hand for
  Cash (was already gated; the UI now sends the choice instead of defaulting to Cash).
- Batch collect (`OrderService.batchMarkAsCollected`) now posts cash-on-hand only for orders whose
  own mode is CASH/COD — bank-transfer orders in a batch no longer inflate the drawer.

**#2 — Inventory Movement Log**
- New **Movement Log** sub-tab on the Inventory page (Daily / Weekly), a live report over the
  existing `inventory_movements` audit table. Shows every IN/OUT per day — product, warehouse,
  qty, type, reason, order reference — with totals and green/red cues to surface unexplained
  deductions. New endpoint `GET /api/inventory/movements?start=&end=`. No new table, no migration.

Files: `OrderController.java`, `OrderService.java`, `InventoryMovementController.java` (new),
`InventoryMovementRepository.java`, `index.html`, `js/app.js`.

---

## 2. Deploy
```bash
git fetch && git checkout fix/collections-paymode-and-inventory-movement-log
cd rrbm-backend && ./mvnw -q -DskipTests package
# restart backend via the normal mechanism
# deploy static frontend (index.html + js/app.js), cache-bust
```
If the app won't start due to schema/Flyway drift, fix that first (previous runbook §2.3), then retry.

## 3. Verify
1. **Non-cash collect:** open a bank-transfer order in Collections → Collect; the method defaults to
   Bank Transfer → confirm → cash-on-hand does NOT change; the order shows collected as Bank Transfer.
2. **Cash collect:** collect a COD/cash order with method Cash → cash-on-hand increases by the total.
3. **Batch collect:** select a mix of cash + bank-transfer orders → only the cash ones increase
   cash-on-hand.
4. **Movement Log:** Inventory page → Movement Log tab → Daily shows today's ORDER_OUT / RESTOCK /
   CANCELLED_RETURN rows with product, warehouse, qty, and order ref; Weekly aggregates 7 days;
   totals (in/out/net) reconcile.

## 4. Rollback
Redeploy the previous backend build + previous `index.html`/`app.js`. No schema changes to revert.

---

## Note — issue #3 (mysterious stock deduction) is NOT in this branch
It is deferred for a deeper investigation. The Movement Log shipped here is the tool to inspect the
`inventory_movements` trail for the affected product and confirm the exact cause before fixing.
