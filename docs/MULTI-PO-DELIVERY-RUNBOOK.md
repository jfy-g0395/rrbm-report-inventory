# Multi-PO Delivery (per-line PO tagging) — Deployment & Usage Runbook

> **For Claude Code:** When the operator says something like *"apply the multi-PO delivery feature"*,
> *"deploy per-line PO"*, or *"initiate multi-PO receiving"*, follow this runbook **in order**. Take
> the backup first, confirm each gate, and stop and ask if a step's output doesn't match.
>
> **For a human operator:** same steps, run them yourself on the LAN host.

## What this feature does
On the **Receive Stock** page, each delivery line can now be tagged with a **specific Purchase
Order**. This lets **one Delivery Receipt (DR) fulfil items across multiple POs** — including the
**same product appearing on two different POs** (e.g. Product A: 5,000 on PO-1 and 3,000 on PO-2
arriving together under one DR). Previously the DR was tied to a single PO, so a shared DR was
blocked or misattributed.

## Important: one small additive database change
> **Updated when merged to `main` (2026-06-29):** the original branch was code-only, but `main` had
> since centralized all delivery side-effects into `DeliveryStockService` (used by receive **and**
> the new delivery edit/cancel re-sync). To make the per-line PO tag survive a later **edit** of a
> multi-PO delivery, the tag is now **persisted** on the delivery line.

This adds **one additive, reversible Flyway migration** — `V86__delivery_line_po_tag.sql` — which
adds two nullable columns (`po_item_id`, `po_number`) to `delivery_log_items`. No data is rewritten;
existing rows keep `NULL` and behave exactly as before. The per-line tag is resolved in
`DeliveryStockService.applyEffects` (and rolled back precisely in `reverseEffects`). It remains
**fully backward compatible**: the single-PO ("linked PO" dropdown) flow and the manual no-PO flow
behave exactly as before, and the DR number stays **globally unique** (one physical receipt = one
record).

---

## 0. Environment facts (live deploy)
- Office LAN, one Windows host. **Docker Compose**: `rrbm_db` (Postgres), `rrbm_backend`
  (Spring Boot :8080), `rrbm_frontend` (nginx :80, proxies `/api/` → backend).
- The **frontend is baked into the nginx image**, so the frontend change requires a
  `docker compose build` (copying files is not enough).
- Repo on the host is updated with `git pull`; secrets live in the host-only `.env`.

---

## 1. Pre-flight
1. Confirm you are on the **LAN host** (`docker ps` shows `rrbm_db`, `rrbm_backend`,
   `rrbm_frontend` as **Up**).
2. Confirm the feature is merged into `main` (this lands via branch `feature/multi-po-delivery`).

## 2. Back up the database (precaution)
There is now one additive migration (V86, adds two nullable columns), so take a backup before deploy:
```powershell
& "C:\rrbm-backups\rrbm-backup.ps1"
# OR:  docker exec rrbm_db pg_dump -U postgres -F c -d rrbm_db > "C:\rrbm-backups\rrbm_db_before_multipo.dump"
```

## 3. Get the code on the host
```powershell
cd <repo-root-on-host>
git fetch origin
git checkout main
git pull origin main          # main must already contain the merged feature
git log --oneline -3          # confirm the multi-PO commit is present
```

## 4. Rebuild and restart
```powershell
docker compose config         # vars resolve, no warnings
docker compose build          # rebuilds BOTH backend and the nginx frontend image
docker compose up -d
```
On backend start, Flyway applies `V86__delivery_line_po_tag.sql` automatically (additive; adds two
nullable columns to `delivery_log_items`). Confirm it in the logs at step 5.

## 5. Verify the deploy
```powershell
docker ps                                 # all three containers Up
docker compose logs backend | Select-String "Started RrbmBackendApplication"
```
Then open the app (`http://<LAN-IP>/`) → **Receive Stock**. Add a line and confirm each line now
has a **"PO (optional)"** dropdown next to the Product field.

---

## 6. How to use it (the overlapping-PO scenario)
Example: 5,000 of Product A on **PO-1** and 3,000 of Product A on **PO-2**, delivered under one DR.

1. Open **Receive Stock**. Leave the top **"Linked PO"** dropdown on *"No linked PO (manual entry)"*.
2. Enter the **DR number once** at the top, plus supplier/receiver.
3. **Row 1:** Product A · Received **5,000** · in the row's **PO** dropdown pick **PO-1**.
4. Click **Add Line**. **Row 2:** Product A · Received **3,000** · pick **PO-2** in that row's PO dropdown.
5. Submit **once**.

Result: one DR record; Product A stock increases by 8,000; **PO-1's line shows 5,000 received** and
**PO-2's line shows 3,000 received**, each marked fulfilled — because each row was tagged to its PO.

Notes:
- The per-line **PO dropdown is optional**. Leave it blank to keep the old behaviour (manual stock-in
  or global auto-match).
- The top **"Linked PO"** dropdown still works as before for single-PO deliveries.

---

## 7. Smoke test (recommended)
1. Two POs containing the same product, one DR, two rows each tagged to a different PO → both POs
   fulfilled, stock = sum, one delivery record.
2. A plain manual delivery (no PO tag) → stock still updates (unchanged).
3. A single-PO delivery via the top dropdown → still fulfils that PO (unchanged).
4. Re-submitting an existing DR number → still rejected ("already processed").

---

## 8. Rollback
Rollback is **code-only** — no DB restore needed. The V86 columns are nullable and additive, so
leaving them in place after reverting the code is harmless (older code simply ignores them):
```powershell
cd <repo-root-on-host>
git checkout <previous-known-good-commit>
docker compose build
docker compose up -d
```
(The backup from step 2 is just a safety net.)

---

## File reference (what this feature touches)
- **DB:** `V86__delivery_line_po_tag.sql` — additive; adds nullable `po_item_id` + `po_number` to
  `delivery_log_items`.
- **Backend:** `dto/DeliveryRequest.java` (optional `poItemId` + `poNumber` per line),
  `DeliveryLogItem.java` (persists the tag), `ProductController.processDelivery` (copies the tag
  onto each persisted line, then delegates to the service), and `DeliveryStockService` —
  `applyEffects` resolves each line **explicit PO item → per-line PO → request-level PO → global
  FIFO** (helpers `matchOpenPoItem` / `fifoMatchOpenPoItem`), and `reverseEffects` rolls back the
  exact tagged line first (so edits/cancels of multi-PO deliveries stay correct).
- **Frontend:** `rrbm_frontend/rrbm-frontend/js/app.js` (`addDeliveryLineRow` per-line PO dropdown,
  `_deliveryPoOptionsHtml`, `_resolvePoItemIdForRow`, and `submitDeliveryReceipt` sends the per-line
  PO tag).
- **One additive migration (V86). Backward compatible.**
