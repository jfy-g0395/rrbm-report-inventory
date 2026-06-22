# Cash Flow (Cash on Hand) — Deployment & Initiation Runbook

> **For Claude Code:** When the operator says something like *"initiate the cash flow feature"*,
> *"deploy cash flow"*, or *"set up cash on hand"*, follow this runbook **in order**. Do the
> backup before anything else, confirm each gate, and never skip the `pg_dump`. Stop and ask if a
> step's expected output doesn't match.
>
> **For a human operator:** same steps, run them yourself on the LAN host.

This feature adds a **Cash Flow** page that tracks physical cash on hand via an append-only
`cash_ledger` table. Cash on hand persists independently of the daily close — it only changes when
cash physically moves (cash orders in, cash expenses out, bank deposits out, manual add/adjust).

---

## 0. Environment facts (live deploy)

- **Office LAN only**, one Windows host. Stack runs via **Docker Compose**: containers
  `rrbm_db` (Postgres, db `rrbm_db`, user `postgres`), `rrbm_backend` (Spring Boot, port 8080),
  `rrbm_frontend` (nginx on port 80, proxies `/api/` → `backend:8080`).
- The **frontend is baked into the nginx image**, so any frontend change requires a
  `docker compose build` (copying files is not enough).
- DB migrations (**Flyway**) run automatically when `rrbm_backend` boots. This feature ships
  **`V80__cash_ledger.sql`**, which is additive + idempotent (`CREATE TABLE / ADD COLUMN IF NOT
  EXISTS`) — it does not alter or drop existing data.
- Repo on the host is updated with `git pull`; secrets live in the host-only `.env` (never in git).

> If the stack is **not** Docker on this machine (e.g. you're testing on a dev box), substitute the
> equivalent: `pg_dump` directly, `git pull`/merge, then rebuild & restart the backend (`mvnw
> spring-boot:run`) and serve the frontend. The *order and intent* of the steps are identical.

---

## 1. Pre-flight

1. Confirm you are on the **LAN host** (the machine running the containers), not a dev box.
   - `docker ps` should list `rrbm_db`, `rrbm_backend`, `rrbm_frontend` as **Up**.
2. Confirm the merged code is what you intend to deploy (the cash-flow work lands on `main` via
   the `feature/cash-flow` branch / `cashflow.patch`).

---

## 2. Back up the live database (MANDATORY — this is the rollback)

```powershell
# Preferred: the existing backup script (online pg_dump -Fc, copies off-machine, 14-day retention)
& "C:\rrbm-backups\rrbm-backup.ps1"

# OR, a one-off manual dump:
docker exec rrbm_db pg_dump -U postgres -F c -d rrbm_db > "C:\rrbm-backups\rrbm_db_before_cashflow.dump"
```

Verify the dump file exists and is non-trivial in size before continuing. **Do not proceed without
a good backup.**

---

## 3. Get the code onto the host

```powershell
cd <repo-root-on-host>      # e.g. C:\rrbm or wherever the repo was cloned
git fetch origin
git checkout main
git pull origin main        # main must already contain the merged cash-flow work
git log --oneline -3        # confirm the cash-flow commit is present
```

> If deploying from the branch/patch instead of merged `main`, either merge `feature/cash-flow`
> into `main` first, or `git apply cashflow.patch` on `main` (run `git apply --check cashflow.patch`
> first — it validates without changing anything).

---

## 4. Rebuild and restart the stack

```powershell
docker compose config                 # all ${VARS} resolve from .env, no warnings
docker compose build                  # rebuilds BOTH backend and the nginx frontend image
docker compose up -d                  # recreates changed containers
```

---

## 5. Verify the deploy

```powershell
# Flyway must report it is now at version 80 (cash ledger), no migration errors:
docker compose logs backend | Select-String "Migrating schema|now at version|cash ledger|Started RrbmBackendApplication"

# Containers healthy:
docker ps                             # rrbm_db, rrbm_backend, rrbm_frontend all Up

# API reachable (returns JSON with cashOnHand once authenticated; 401 unauthenticated is fine):
curl http://localhost/api/cash-flow
```

Expected: log shows `Migrating schema "public" to version "80 - cash ledger"` then
`Started RrbmBackendApplication`. If Flyway errors or the backend won't start, **stop and
investigate** — do not run app traffic against a half-migrated DB.

---

## 6. Initiate the feature in the app (the actual business start)

This is the one manual, in-app step. **It is required exactly once.**

1. Open the app (`http://<LAN-IP>/`) and log in as a Super Admin.
2. Confirm the **Cash Flow** tab appears in the sidebar, directly under **Order List**.
3. Open it → click **Set Opening Balance** (this button only shows while the ledger is empty).
4. Enter the **actual cash physically in the drawer right now**, plus your **admin security key**,
   and save. That becomes the starting balance.

From this point, cash movements track automatically:
- Cash-paid orders **add** (incl. COD collected in cash); cancel / return / item-void **reverse**.
- Cash-paid expenses **deduct**; editing or voiding a cash expense reconciles automatically.
- **Add Cash** (bank withdrawal into the drawer) and **Adjustment** (returned change / count fixes)
  require the **admin security key**.
- **Deposit** (cash taken to the bank) requires the **master key**.

> **No history is backfilled.** Cash orders/expenses recorded *before* the opening balance are not
> pulled in — the opening balance already accounts for them. Set it to your true drawer count on
> go-live day.

### Page access for non-Super-Admins
Super Admins see the tab immediately. For any other role that should use it, grant the **`cash-flow`**
page: **Employee List → edit user → page permissions → enable Cash Flow**. Otherwise that user gets
"You do not have access to this page."

---

## 7. Smoke test (optional but recommended)

1. Create a small **CASH** order → Cash Flow balance increases by the order total; a `CASH_SALE`
   row appears in the history.
2. Create a **GCASH** order → balance unchanged.
3. Record a **CASH** expense → balance decreases.
4. Cancel the cash order → balance reverses (a `CASH_SALE` reversal row appears).
5. **Close Daily Sales**, open that day's report → it shows a **"Cash on Hand (at close)"** line
   that stays fixed afterward.

---

## 8. Daily report behavior (what to expect)

On each daily close, the **current** cash on hand is snapshotted into that day's report
(`daily_reports.cash_on_hand`) and rendered as **"Cash on Hand (at close)"** in the Accounting
section. It is a point-in-time figure — it does **not** reset daily sales, and reopening a past
report shows the value unchanged. The Cash Flow page itself always shows the live current balance.

---

## 9. Rollback

If something is wrong after deploy:

```powershell
# 1. Revert the code on the host
cd <repo-root-on-host>
git checkout <previous-known-good-commit>

# 2. Restore the DB from the backup taken in step 2
docker exec -i rrbm_db pg_restore -U postgres -d rrbm_db --clean --if-exists < "C:\rrbm-backups\rrbm_db_before_cashflow.dump"

# 3. Rebuild & restart
docker compose build
docker compose up -d
```

The new `cash_ledger` table and `daily_reports.cash_on_hand` column are inert if the old code
doesn't reference them, so a code-only rollback (without DB restore) is also safe — the extra
schema simply goes unused.

---

## File reference (what this feature touches)

- **Migration:** `rrbm-backend/src/main/resources/db/migration/V80__cash_ledger.sql`
- **Backend:** `CashLedgerEntry.java`, `CashLedgerRepository.java`, `CashLedgerService.java`
  (single write choke point), `CashFlowController.java` (`/api/cash-flow`), plus hooks in
  `OrderService.java`, `OrderController.java`, `ExpenseController.java`, `DailyReportService.java`,
  and a page rule in `PageAccessInterceptor.java`.
- **Frontend:** `rrbm_frontend/rrbm-frontend/index.html` (nav tab, view, 4 modals) and
  `js/app.js` (`loadCashFlow`, render, modal submit handlers, daily-report cash line).
