# Runbook — clean-up-code (tomorrow's live-server session)

**Audience:** Claude Code (or engineer) on the **live server** (Windows host `192.168.0.234`, Docker Compose, nginx :80).
**Goal:** Get live onto the latest `main`, run the required tests, deploy, verify. Everything below is
**already written and merged** — this is a *deploy + verify* job, not a build job.

> **BE TOKEN- AND USAGE-EFFICIENT.** Don't re-investigate, re-plan, or spawn subagents. Read only what a
> step names, keep output short, and **stop and report** on any failure. **Efficiency must NEVER mean
> skipping a verification step or the tests — if in doubt, stop and ask.**

> ⚠️ Includes core inventory/money logic that could **not** be run on the local dev machine (Flyway drift).
> You **MUST** get a green test run + the manual checks before trusting it in production.

---

## 0. PRE-FLIGHT — confirm GitHub is the source of truth (do FIRST, before anything else)
The dev machine's changes may not all have been pushed. **Before syncing or deploying, verify nothing is
out of sync — do NOT overwrite or delete anything until these pass.**

```bash
git fetch --all --prune
git status -sb        # working tree MUST be clean. Uncommitted/untracked files on the host → STOP & report
git for-each-ref --format='%(refname:short) %(upstream:track)' refs/heads   # any '[ahead N]' = unpushed → STOP
```
Then confirm the two expected PRs are **merged into `origin/main`** (otherwise the deploy ships stale code):
- PR: `docs/clean-up-code-runbook` (this runbook)
- PR: `feat/daily-report-pizza-boxes-dispatched` (pizza-boxes = dispatched — see its own runbook,
  `RUNBOOK-daily-report-pizza-boxes-dispatched.md`)
```bash
gh pr list --state open --base main      # both should be MERGED (i.e. NOT still listed) before deploy
git log --oneline origin/main -8         # confirm both merge commits are present
```
- **Any discrepancy (dirty tree, unpushed branch, unmerged PR) → STOP and report to the user.** Do not
  guess, do not `reset`/`clean`, do not proceed to §2.
- Reference (dev machine at time of writing): no unpushed commits; only 3 harmless stray files
  (`cashflow.patch`, `rrbm_import_test2.xlsx` deletion, `rrbm_import_template.xlsx`) — none are lost work.

## 1. What is already done (on `origin/main`)
No code to write — deploy what's on `main` once §0 passes. `main` (plus the two PRs above) contains:
1. **Cancel/void warehouse-symmetric restore** + concurrency safety + cancel "breathing time" + the
   LazyInit eager-load fix. (details: `RUNBOOK-cancel-restore-warehouse-symmetry.md`)
2. **Collection payment-method gate** + **Inventory Movement Log** viewer.
3. **Collection-date at collect** + **Collections History** tab.
4. Roles `ACCOUNTING_PLUS` / `REJECT_MANAGEMENT` retired → page-access permissions.
5. **Daily report: pizza boxes = DISPATCHED, not sold** (V92 re-backfill; label "Pizza Boxes Dispatched").
   Follow its dedicated runbook `RUNBOOK-daily-report-pizza-boxes-dispatched.md` (has its own pre-flight).
6. Frontend cache-bust (`app.js?v=…`) bumped — load the latest and hard-refresh if an old cache shows.

## 2. What is LEFT to do — do these in order
1. **Sync to latest main** (do NOT stay on the old feature branch):
   ```bash
   git fetch origin
   git checkout main
   git pull --ff-only origin main      # expect HEAD = 298e971 or newer
   ```
2. **Run the required regression test on a real Postgres** — an **isolated/staging DB**, NOT the shared
   dev DB (the `*IT` suite **wipes** products/orders/transactions):
   ```bash
   cd rrbm-backend && ./mvnw -Dtest=CancelRestoreWarehouseIT test   # must be GREEN
   ```
   If it fails → **stop, report, do not deploy.**
3. **Deploy** (LAN Docker stack):
   ```bash
   docker compose build
   docker compose up -d
   docker compose logs -f backend   # confirm "Started RrbmBackendApplication" + Flyway OK
   ```
   Confirm the browser loads the latest `app.js?v=…` from `index.html` (hard-refresh if an old cache shows).
4. **Verify** — §3 below.
5. **Tidy the working tree** (only if these stray files are present and untracked/ignored):
   `cashflow.patch`, `rrbm_import_template.xlsx`, `rrbm_import_test2.xlsx`. Do **not** delete anything
   tracked or anything you did not create — list them and ask first.

## 3. Verify (REQUIRED — core stock logic)
1. **Split-warehouse cancel:** order a SET pulling one component from two warehouses; cancel → each
   warehouse returns to its exact pre-order count (check Inventory table + Movement Log).
2. **Rapid cancels:** cancel several orders of the same product quickly → totals stay correct; the
   Cancel button disables mid-request.
3. **Non-delivered cancel:** no restock-warehouse picker; stock auto-returns to origin. Delivered
   return still shows disposition + warehouse picker.
4. **Collect flow:** collecting now requires a payment method and records the collection date; the
   collection appears in the **Collections History** tab.

## 4. What NOT to do
- ❌ Do **not** run the full `*IT` suite against the live/shared DB — it zeroes real data.
- ❌ Do **not** bulk-fix pre-existing skewed per-warehouse stock. This fix stops *new* drift only;
  repair old discrepancies one-by-one with the manual stock-adjustment tool. No mass script.
- ❌ Do **not** touch anything under DEPLOYMENT-GUIDE.md "DO NOT TOUCH" (ledger paths, applied Flyway
  migrations, close-daily immutability).
- ❌ Do **not** re-plan, refactor, or "improve" the merged code. Deploy what's on `main`.

## 5. Rollback
Redeploy the previous image / previous `main` commit (`git checkout <prev> && docker compose up -d --build`).
No schema to revert in this batch.
