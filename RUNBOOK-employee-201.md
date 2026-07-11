# Runbook — Employee 201 records (Phase B)

## ✅ STATUS: DEPLOYED — 2026-07-11
Merged into `main` via PR #6 (merge commit `ee7122b`), pushed to `origin/main`, and deployed via
`docker compose up -d --build` **after Phase A / V98** (migration order preserved). `flyway_schema_history`
confirms **V99** (`employee 201`) applied successfully on the live DB (2026-07-11 02:40, after V98).
Validated on an isolated clone stacked on V98 (§3 all green: employee create + age compute + auto
milestone events (SALARY/POSITION/STATUS) + memo + benefit types + `employee-201` 403 gate; `/api/users`
"User List" rename intact). Live `GET /api/employees` returns 200; frontend cache-bust `u27`.
**Do not re-run §2 deploy steps — this is done.** Everything below is kept for reference/rollback only.

<!-- superseded build-status note below -->
### 🟡 (historical) BUILT — PR #6 open, NOT yet tested / merged / deployed (updated 2026-07-10)
Branch `feat/employee-201-records`, built off `main` (`0e51159`, head V97). **All 3 sessions complete:**
- **S-B1** backend — `2767be2` — V99 schema, entities/repos, `EmployeeController`, access wiring,
  "Employee List → User List" rename. `./mvnw compile` **green**.
- **S-B2** frontend — `407c04d` — Employee 201 page + 5-tab register/edit modal + timeline. `node --check` **passes**.
- **S-B3** — `311d2fd` — this runbook.
- **PR:** https://github.com/jfy-g0395/rrbm-report-inventory/pull/6 (**open**).

> ⚠️ **MIGRATION ORDER:** this feature is **V99**. Phase A (resellers) is **V98** and is on a separate
> unmerged branch (PR #5). **Phase A (V98) must be applied to any shared/live DB BEFORE this (V99)** or
> Flyway will fault on out-of-order migrations. Deploy/merge order: **A → B.**

**NOT done (in order):** ① functional test on an isolated/staging Postgres · ② review + merge PR ·
③ deploy to live (after Phase A, with a DB backup first). Flip to ✅ DEPLOYED once live.

### ▶ NEXT STEP for both branches (test → merge → deploy, A before B)
The two features live in their own branches on purpose (one-branch-per-feature) and stay unmerged until
each is validated on a **Postgres clone** (can't be done from the dev box). Do them **in order**:
1. **Phase A (PR #5, V98)** — clone the live DB → check out `feat/resellers-distributors-registry` →
   boot against the clone (V98 applies) → run `RUNBOOK-resellers-registry.md` §3 → if green, merge PR #5.
2. **Phase B (PR #6, V99, THIS)** — clone again (now including V98) → check out `feat/employee-201-records`
   → boot (V99 applies **after** V98) → run §3 below → if green, merge PR #6.
3. **Deploy live A → B** (each runbook's §2, back up first). Never deploy B before A.
Any failure → **STOP and report; never reset/clean/force.**

> **BE TOKEN- AND USAGE-EFFICIENT.** Code is written and compiles. No re-plan, no subagents, read only
> what a step names, stop-and-report on failure. **Never skip the pre-flight or a verification step.**

> **DO NOT TOUCH:** ledger paths, applied migrations, closeDaily, void/cancel/collect, `/api/users`.

---

## 0. PRE-FLIGHT (run FIRST, every session)
```sql
SELECT 1 FROM flyway_schema_history WHERE version = '99';   -- already applied?
```
and confirm the sidebar shows **"Employee 201"** and **V98 (Phase A) is already applied**.
- V99 present + page shows → **STOP**, already live; report and do nothing.
- V98 NOT applied yet → **STOP**; deploy Phase A first (migration order).
- Working tree must be clean; any discrepancy → **STOP and report; never reset/clean/force.**

## 1. What this delivers
An HR "201 file" module, separate from system users.
- **V99** — `employees` + `employee_education` / `employee_work_history` / `benefit_types` (seeded) /
  `employee_benefits` / `employee_events`. Money `numeric(13,5)`.
- **API** `/api/employees` — create (Personal Info required; auto `EMP-YYYY-NNNN`), list, full detail
  (education, work history, benefits, events, computed age), update with **auto milestone events** on
  wage/position/status change, status PATCH, manual memo/addendum/note events, benefit-types catalog.
- **Page** "Employee 201" (page key `employee-201`): list + 5-tab register/edit (Personal / Education /
  Employment History / Compensation & Benefits / Timeline), 2×2 photo (base64, downscaled ≤400px).
- The old "Employee List" page is renamed **"User List"** (display only; page key/`/api/users` unchanged).

Files: `V99__employee_201.sql`, `Employee*.java` (6 entities + 6 repos), `EmployeeController.java`, edits
to `PageAccessInterceptor`/`UserController`, `index.html` + `js/app.js` (cache-bust `u22`).

## 2. Deploy (AFTER Phase A / V98)
```bash
git fetch && git checkout main && git pull --ff-only        # after PR merge (Phase A first)
cd rrbm-backend && ./mvnw -q -DskipTests package             # Flyway applies V99 on boot
# restart backend; redeploy static index.html + js/app.js (cache-bust u22 — hard-refresh)
```

## 3. Verify (isolated/staging Postgres first — NEVER the shared dev DB)
1. V99 applied; backend boots clean. Sidebar shows "User List" (old users page works) + "Employee 201".
2. Register an employee via Personal Info only → saves; photo shows; age auto-computes; spouse field
   appears only when Civil Status = Married.
3. Reopen → edit daily wage (e.g. 610 → 650) → a SALARY_CHANGE milestone appears in Timeline. Change
   position → POSITION_CHANGE. Change status Probationary → Regular → STATUS_CHANGE.
4. Add a Memo in Timeline → it appears. Benefits checklist saves amounts/notes. "New Type" adds a
   benefit type without code changes.
5. A user WITHOUT the `employee-201` key: page hidden; `GET /api/employees` returns 403.

## 4. What NOT to do
- ❌ Don't deploy before Phase A (V98) — migration order.
- ❌ Don't rename the `employees` page key, `emp` view, or `/api/users` — only display strings changed.
- ❌ Don't touch ledger/ closeDaily/ applied migrations.

## 5. Scope notes (for sign-off)
- Photo stored as base64 in the DB (downscaled to keep well under nginx's default 1 MB body limit).
- Age is computed on read, never stored. Benefit types are admin-manageable.
- Excluded (possible fast-follows): payroll computation, leave tracking, document attachments beyond the photo.

## 6. Rollback
Revert the branch merge + redeploy previous `index.html`/`app.js`. V99 only adds tables; to fully revert,
drop the six new tables. No existing table is modified (the rename is frontend-only).
