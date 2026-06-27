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

## Important: no database change
This feature is **code-only** — there is **no Flyway migration and no schema change**. It adds an
optional per-line PO tag to the delivery request and resolves it in the matcher. It is **fully
backward compatible**: the existing single-PO ("linked PO" dropdown) flow and the manual no-PO flow
behave exactly as before. The DR number stays **globally unique** (one physical receipt = one
record) — that protection is unchanged.

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
Even though there is no schema change, take a backup before any deploy:
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
No Flyway/migration step is required for this feature.

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
Because there is **no schema change**, rollback is **code-only** — no DB restore needed:
```powershell
cd <repo-root-on-host>
git checkout <previous-known-good-commit>
docker compose build
docker compose up -d
```
(The backup from step 2 is just a safety net.)

---

## File reference (what this feature touches)
- **Backend:** `rrbm-backend/src/main/java/rrbm_backend/dto/DeliveryRequest.java` (adds optional
  `poItemId` + `poNumber` per line) and `ProductController.java` (`processDelivery` resolves each
  line: explicit PO item → per-line PO → request-level PO → global FIFO; helpers `matchOpenPoItem`
  and `fifoMatchOpenPoItem`).
- **Frontend:** `rrbm_frontend/rrbm-frontend/js/app.js` (`addDeliveryLineRow` per-line PO dropdown,
  `_deliveryPoOptionsHtml`, `_resolvePoItemIdForRow`, and `submitDeliveryReceipt` sends the per-line
  PO tag).
- **No migration. No schema change.**
