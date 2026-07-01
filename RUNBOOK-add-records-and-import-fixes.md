# Runbook — Add-Records fixes, Expense Log, IN_HOUSE source, import hotfix

**Branch:** `hotfix/add-records-and-import-fixes` (based on latest `main`)
**Audience:** Claude Code (or engineer) applying this on the **live server**
**Severity:** High — records were silently not saved; in-house orders could not be recorded

> READ THIS WHOLE FILE FIRST. Do the pre-flight (Section 2) before anything else.
> If any pre-flight check fails, STOP and report — do not deploy.

> **BE TOKEN- AND USAGE-EFFICIENT.** This branch is already written, compiled, and tested — do
> NOT re-investigate the codebase, re-plan, or re-read files beyond what a step here names. Do
> not spawn subagents. Run the exact commands in Sections 2–4 in order, read only the specific
> files/queries a step calls for, and stop to report the moment something fails instead of
> exploring. Keep output short. The goal is: verify drift → migrate → deploy → verify → done.
>
> **Efficiency must NEVER mean skipping the migration-drift fix (Section 2.3) or any verification
> step (Sections 2.4 and 4). If in doubt, STOP and ask — do not guess to save time.**

---

## 1. What this branch changes

1. **Add Records — expenses now save reliably & failures are visible.**
   - `createBackdatedExpense` is transactional (expense + cash-ledger commit/rollback together).
   - Every failed row is now logged server-side (`BackdatedEntryController.rowError`), and the UI
     toast + result card show the real per-row reason.
2. **"Create daily report" checkbox on Add Records.** When ticked (default on), after submit the
   server creates a daily report for each entered date that has none yet
   (`DailyReportService.closeForImportDate`, idempotent) and returns them as `createdReports`.
3. **Daily Expense Log (persisted snapshot at close).** New table `daily_expense_logs`
   (migration **V89**), populated on every daily close / import close / amend, plus a new card
   on the Expenses page (`GET /api/expenses/log/days`, `GET /api/expenses/log/daily?date=`).
4. **IN_HOUSE is now a valid order source.** The app + UI already accepted it; migration **V88**
   widens the `orders.chk_source` constraint so in-house orders can actually be saved.
   (In-house counts in total orders / net sales, like RESELLER/DISTRIBUTOR; it is not a separate
   headline bucket in the daily report.)
5. **Import product-code inventory gap re-fixed.** An unmatched (non-blank) Product Code is again
   a hard `needsFix` error, and the import commit rejects any line with no matched product, so a
   sale can never be recorded without deducting stock. Regression test `ImportProductCodeGapTest`.

New migrations added: **V88** (chk_source + IN_HOUSE), **V89** (daily_expense_logs).

---

## 2. PRE-FLIGHT (do first, in order)

### 2.1 Confirm the branch is clean
```bash
git fetch --all
git checkout hotfix/add-records-and-import-fixes
git log --oneline -1
git diff --stat main...HEAD    # review the changed files; nothing unrelated
```

### 2.2 Back up the database
```bash
pg_dump rrbm_db > rrbm_db_backup_$(date +%Y%m%d_%H%M).sql
```

### 2.3 Resolve the Flyway migration drift — CRITICAL
The DB has historically been **stuck**: a checksum mismatch on **V5** aborts Flyway, so newer
migrations never applied (schema was frozen at V74). `spring.jpa.hibernate.ddl-auto=validate`,
so the app will NOT start until the schema matches the code. Check and fix:
```bash
# Inspect what Flyway has actually applied
psql -d rrbm_db -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```
- If the latest applied version is **below V89** or any row shows `success = f`, or startup logs
  show `Migration checksum mismatch for migration version 5`:
  1. Run Flyway **repair** to realign checksums (data-safe — only fixes the history table):
     `./mvnw -q flyway:repair` (or the project's Flyway command / start the app once so
     `spring.flyway.repair`-equivalent runs — confirm the exact mechanism before running).
  2. Then let the app start / run migrate so **V75 → V89** all apply.
- Do **not** blindly repair a production DB without the backup from 2.2 and without confirming the
  drift is only a checksum mismatch (not diverged data). If unsure, STOP and escalate.

### 2.4 Verify schema is current
```bash
psql -d rrbm_db -c "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"   # expect 89
psql -d rrbm_db -c "\d daily_expense_logs"                                                             # table exists
psql -d rrbm_db -c "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='chk_source';"   # includes IN_HOUSE
```

---

## 3. DEPLOY
Standard backend rebuild + restart (Java + 2 migrations, no manual SQL beyond Flyway):
```bash
cd rrbm-backend
./mvnw -q -DskipTests package
# restart the backend service via the project's normal mechanism
```
Frontend is static (`rrbm_frontend/rrbm-frontend/`): deploy `index.html` + `js/app.js` as usual
(hard-refresh / cache-bust so clients get the new JS).

> If the app fails to start with a Hibernate `validate` error naming a missing column/table, the
> migrations did not fully apply — go back to 2.3.

---

## 4. POST-DEPLOY VERIFICATION
1. **Expenses save:** Add Records → add 2–3 expenses for a past date → Submit. They must appear as
   committed; if any fail, the toast/result card shows the reason (no silent "0").
2. **Create-report checkbox:** submit with it checked → result card lists "Daily reports created";
   the dates appear in the daily-reports list.
3. **Expense Log tab:** open Expenses page → "Daily Expense Log" card lists closed days → click one
   → full expense log + totals show.
4. **IN_HOUSE:** create a New Order and an Add-Records order with source IN_HOUSE → both save with
   no `chk_source` error; they count in the daily report totals.
5. **Import hotfix:** upload a sales CSV with a bogus Product Code → it lands in Needs Fix (not
   committed); a valid code still deducts stock.

## 5. ROLLBACK
- Code: redeploy the previous backend build + previous `app.js`/`index.html`.
- DB: V88/V89 are additive (new table + widened constraint). They don't need reverting; if you must,
  restore the 2.2 backup. Data recorded before the fix (e.g. import lines with `product_name IS NULL`
  and non-zero total) is a separate manual cleanup — this branch only prevents new occurrences.
