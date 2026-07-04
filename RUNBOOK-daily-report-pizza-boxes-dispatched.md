# Runbook — Daily report: pizza boxes = "dispatched" not "sold"

## ✅ STATUS: DEPLOYED — 2026-07-04
Merged into `main` (merge commit `c90e5b6`), pushed to `origin/main`, and deployed via
`docker compose up -d --build`. `flyway_schema_history` confirms **V92** applied successfully
(`daily report pizza boxes dispatched`, success=t, 2026-07-04 06:46:12). Daily-report card verified
reading "Pizza Boxes Dispatched"; Pizza Quota tile confirmed untouched (still "Pizza Boxes Sold
Today" / 5,000 pcs quota, separate feature per §4). **Do not re-run §2 deploy steps — this is done.**
Everything below is kept for reference/rollback only.

**Branch:** `feat/daily-report-pizza-boxes-dispatched` (based on latest `main`)
**Audience:** Claude Code (or engineer) on the **live server**
**Type:** Reporting definition change — deploy + verify (no build work; code is written).

> **BE TOKEN- AND USAGE-EFFICIENT.** This branch is written and compiles. Don't re-investigate or
> re-plan, don't spawn subagents, read only what a step names, keep output short, stop and report on
> any failure. **Efficiency must NEVER mean skipping the pre-flight or a verification step — if in
> doubt, stop and ask.**

---

## 0. PRE-FLIGHT (run FIRST, every session) — is this fix already live?
This fix is **idempotent-once**; never apply it twice. Check both:
```sql
SELECT 1 FROM flyway_schema_history WHERE version = '92';   -- migration applied?
```
and confirm the daily-report summary card already reads **"Pizza Boxes Dispatched"** (not "…Sold").

- **Both true → STOP.** The fix is already deployed. Report "already applied" and do nothing else.
- Otherwise → continue to §2.

## 1. What this changes
The daily report's pizza-box figure (`total_pizza_boxes`) now counts **all pizza boxes dispatched in
the day** — including `PENDING` and `PENDING_COLLECTION` orders — and excludes a box **only** when its
order is cancelled/voided (a full void sets `orders.status = 'CANCELLED'`).
- SQL rule: `o.status <> 'CANCELLED'` (was `NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')`).
- `V92` re-backfills every already-closed report with the new rule (consistent history).
- UI label: "Pizza Boxes Sold" → "Pizza Boxes Dispatched" (`app.js`, cache-bust → `v=u12`).

Files: `DailyReportService.java` (two snapshot builders), `V92__daily_report_pizza_boxes_dispatched.sql`,
`index.html` + `js/app.js`.

## 2. Deploy
```bash
git fetch && git checkout main && git pull --ff-only    # after the PR is merged
cd rrbm-backend && ./mvnw -q -DskipTests package         # (run tests first per §3)
# restart backend (Flyway auto-applies V92) + redeploy static index.html + js/app.js (cache-bust v=u12)
```

## 3. Verify
1. **Migration applied:** `SELECT 1 FROM flyway_schema_history WHERE version='92';` returns a row, and
   backend logs show V92 succeeded.
2. **Number went up where expected:** pick a day that had pizza-box orders left `PENDING` /
   `PENDING_COLLECTION`; its `total_pizza_boxes` now includes them. Spot-check:
   ```sql
   SELECT report_date, total_pizza_boxes FROM daily_reports WHERE report_date = 'YYYY-MM-DD';
   -- vs manual: SUM(oi.quantity) over that day's Pizza Box items where o.status <> 'CANCELLED'
   ```
3. **Functional:** create pizza-box orders in PENDING and PENDING_COLLECTION for a test day → count
   includes them; cancel/void one → it drops out.
4. **UI:** open the daily report + export PDF → card reads **"Pizza Boxes Dispatched"** with the new
   figure (hard-refresh if the old cache shows).

## 4. What NOT to do
- ❌ Don't re-apply if the pre-flight says it's already live.
- ❌ Don't touch the Pizza **Quota** KPI, dashboard pizza tiles, or `reports/pizza-summary` — separate
  features, unchanged.
- ❌ Don't change "Items Sold", order counts, or any financial/ledger totals — those stay sold-only.
- ❌ Don't rename the `total_pizza_boxes` column/field — semantic change only.
- ❌ Don't run the V92 UPDATE by hand — Flyway owns it. Don't hand-edit closed reports' other fields.

## 5. Note for exec sign-off
A **partial** void/return leaves the order `DELIVERED`, so its boxes still count (only a *full* void →
`CANCELLED` drops out). This matches "dispatched" (the box left the door), not "net delivered".

## 6. Rollback
Revert the branch commit + redeploy the previous `index.html`/`app.js`. To undo the backfill, re-run the
old-rule UPDATE (`status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')`). Reporting column only —
low risk, no schema to revert.
