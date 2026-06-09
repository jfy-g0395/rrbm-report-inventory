# RRBM — Expense & Agent Commission Build Plan (v2, post Session 0)

Session-by-session plan for Claude Code. Ship reliably, test as we go, keep token cost low by never re-exploring the whole repo.

## How the three docs fit together
- **`docs/SPEC.md`** — the full specification (the long design doc): every field table, report layout, receipt mockup. The "WHAT to build." Save the earlier spec document here.
- **`docs/BUILD_CONTEXT.md`** — Session 0 recon: real tables, field names, file paths, role bug, receipt leak line. The "what ALREADY exists."
- **`docs/BUILD_PLAN.md`** (this file) — order, chunking, tests. The "WHEN and in what order."

Each session reads: `BUILD_CONTEXT.md` + the **one** SPEC section it needs + its row below. Nothing more.

---

## Token discipline (read before every session)
1. Start by reading `BUILD_CONTEXT.md` + this file + the cited SPEC section. Open only the files the session names. No repo-wide grep.
2. One vertical slice per session. "Done" = its tests pass.
3. End each session: append ~5 lines to `docs/PROGRESS.md` (changed / files touched / decision / next start point) AND draft the next session's kickoff prompt at the bottom of `PROGRESS.md`.
4. Tests live in the same session as the code.
5. One slice = one sitting. Spare budget → pull the next session in; never split a slice.

## Per-session loop (what the operator does each time)
1. Start a **fresh / `/clear`ed** Claude Code session — never continue the previous one (a long session resends its whole transcript every turn = runaway tokens).
2. Paste the session's kickoff prompt (U1's is at the bottom of this file; later ones are pre-drafted at the bottom of `PROGRESS.md` by the previous session).
3. Let it implement + write the tests for that slice only.
4. Verify: run the tests, glance at the diff.
5. Have it append the `PROGRESS.md` entry **and** draft the next kickoff prompt.
6. `git commit` the green slice.
7. Quit / `/clear`. Repeat.

Avoid: "review the whole codebase" / "make sure everything is consistent" (re-reads everything — name specific files instead); stopping a slice half-done (resuming forces a full context reload — always finish to green).

## Reusable kickoff template (fill from the session's row)
> Read `docs/BUILD_CONTEXT.md` and `docs/SPEC.md` §[SPEC ref], then open only the files BUILD_CONTEXT names for this area. Implement session **[ID]** only: [paste the Output cell]. Write tests covering: [paste the Tests/Done cell]. Do not touch anything outside this slice. End by appending the [ID] entry to `docs/PROGRESS.md` and drafting the next session's kickoff prompt at the bottom of `PROGRESS.md`.

## Locked rules (decided)
- **Commission periods:** 1st = 28th prev → 12th current; 2nd = 13th → 27th current.
- **Paid date governs the period.** Unpaid agent orders → Collectibles; enter a period only when paid. *A3 must use the payment/collection date, NOT the ledger `effective_date` — collected orders backdate their SALE to the original order date in the ledger, which would otherwise mis-assign the period.*
- **O.P. is a distribution, not an expense.** Never in expense totals; shown only in commission/cash-flow reports.
- **Expense backdating window is a `settings` row** (the `settings` key/value table already exists), enforced in `ExpenseController`.
- **Voids are negative adjustments, never deletes. O.P. stored per unit on the line item, never order-level.**
- **Security key reuse:** expense void/return is authorised by the caller's existing `admin_security_key` (same pattern as Cancel/Void). Commission release is authorised by the ACCOUNTING-role user's own `admin_security_key` (single-auth). No new key mechanism.
- **All new permission checks are enforced in the controller** (JWT + role + security key). Backend `SecurityConfig` is permit-all; do not rely on Spring Security roles.
- Payment methods (existing): `CASH`, `BANK_TRANSFER`, `GCASH`, `PAYMAYA`, `COD`. (No `CARD`.)

## Resolved decisions
1. **O.P./commission is AGENT-only.** Reseller/Distributor stay as plain-text sources, unchanged — no registry, no O.P.
2. **No backfill.** Historical `agent_name` stays as text; add nullable `agent_id` FK; only new orders link to the registry.
3. **Commission release = single-auth by the ACCOUNTING role.** Any ACCOUNTING-role user releases using their own `admin_security_key` (same mechanism as expense void). No SUPER_ADMIN second key.

---

## Completed ✅
- **Session 0** — recon → `BUILD_CONTEXT.md`. Stack: Spring Boot 3.5 / Java 21 / Postgres / Flyway. Vanilla-JS frontend, print via `window.print()`.
- **Expense track E1–E5** — categories + schema + role fix, entry + backdating, void/return, daily report + dashboard, monthly report + exports.
- **Agent track A1–A5** — registry, order integration + per-unit O.P. (receipt suppression), commission engine, release workflow + statements, cash-flow widget.

## CSV Upload track (remaining work)

Record a day's orders and expenses in bulk via CSV. **Upload-only** — no manual form, no offline, no draft-autosave. Reuses the finished create and commission paths; adds only an import flag, an upload permission, and the validation pipeline.

| # | Session | SPEC § | Output | Tests / Done |
|---|---------|--------|--------|--------------|
| U1 | Import schema + permission | 3.1, 3.4, 3.5 | `is_imported` + `import_ref` on `orders` & `expenses`; upload permission (ACCOUNTING/ADMIN + own `admin_security_key`) that may date entries beyond the backdating window; "imported" indicator/filter surfaced in existing daily/monthly reports | Migrations clean; import flag persists; non-ACCOUNTING or wrong key blocked; reports show & filter imported entries |
| U2 | CSV upload pipeline | 3.2, 3.3, 3.4 | Downloadable 3-part template (Sales / Sale Items / Expenses, with optional O.P.-per-unit); upload → validate (item codes, categories, payment + source enums, agent names, numeric/date) → preview (valid / needs-fix / duplicate) → conflict resolve (skip/update/review) → commit; `TEMP-DDMMYY-NNNN` → real `DDMMYY-NNNNNN` with ref retained; **agent O.P. routed through the existing commission engine, not reimplemented** | Valid rows commit; bad rows flagged, not committed; duplicate receipt handled per choice; temp ids converted & referenced; agent O.P. lands in the correct commission period |

## Resolved decisions (CSV upload)
1. **CSV-only.** No manual catch-up form, no offline mode, no draft-autosave.
2. **Captures agent O.P.** — optional O.P.-per-unit column, routed through the existing commission engine by payment date.
3. **Permission = ACCOUNTING/ADMIN + own `admin_security_key`**, may date entries beyond the normal backdating window.

---

## Kickoff prompt for Session U1 (paste into Claude Code)
> Read `docs/BUILD_CONTEXT.md` and `docs/SPEC.md` §3.1 + §3.4 + §3.5, then open only the order and expense migration/entity files plus the daily/monthly report query files named in BUILD_CONTEXT. Implement session U1 only:
> 1. New migration: add `is_imported` (boolean, default false) and `import_ref` (text, nullable) to `orders` and `expenses`.
> 2. Add an upload-permission check enforced in the controller (ACCOUNTING/ADMIN role + the acting user's `admin_security_key`), allowed to date entries beyond the expense backdating window.
> 3. Surface an "imported" indicator and filter in the existing daily and monthly reports.
> 4. Tests: migrations up/down clean; the import flag persists; a non-ACCOUNTING user or wrong key is blocked; reports can show and filter imported entries.
> Do not build CSV parsing yet (that is U2). End by appending the U1 entry to `docs/PROGRESS.md` and drafting the U2 kickoff prompt at the bottom of `PROGRESS.md`.
