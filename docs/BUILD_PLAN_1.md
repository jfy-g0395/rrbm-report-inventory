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
2. Paste the session's kickoff prompt (E1's is below; later ones are pre-drafted at the bottom of `PROGRESS.md` by the previous session).
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

## Session 0 — Recon ✅ DONE
Produced `BUILD_CONTEXT.md`. Stack: Spring Boot 3.5 / Java 21 / Postgres / Flyway, next migration **V49**. Vanilla-JS frontend, print via `window.print()`.

## Expense track (build first — low risk, no customer-facing changes)

| # | Session | SPEC § | Output | Tests / Done |
|---|---------|--------|--------|--------------|
| E1 | Schema + role fix | 1.2, 7.1 | `expense_categories` table; `category_id` FK on `expense_items` (**nullable** — historical free-text rows stay valid); void/audit cols on `expenses` (`voided_at/by`, `void_reason`, `is_voided`); seed system categories; **migration adding `ACCOUNTING` to `chk_role`** | Migrate up/down clean; seed correct; historical rows still load; ACCOUNTING user inserts OK |
| E2 | Entry API + form | 1.3 | Category/sub-category on entry; backdating window seeded in `settings` + enforced | Create + validation; backdate inside window OK, outside rejected |
| E3 | Void / return | 1.4 | Void = offsetting negative entry; **caller `admin_security_key` required**; reason required; audit captured; controller-enforced role | Original preserved; totals exclude voided; wrong/empty key rejected; unauthorised role blocked |
| E4 | Daily report + dashboard widget | 1.5, 1.6 | Today + MTD widget; daily report grouped by category | Totals correct; grouping correct; voided excluded |
| E5 | Monthly report + exports | 1.6, 1.7 | Monthly report (grand total, %s, adjustments) + export via existing print pattern | Grand total & %s correct; adjustments shown; export matches screen |

## Agent track (build second — touches live orders/receipts)

| # | Session | SPEC § | Output | Tests / Done |
|---|---------|--------|--------|--------------|
| A1 | Agent registry | 2.2 | `agents` table (AGENT only); CRUD + list w/ totals; nullable `agent_id` FK added to `orders` | CRUD works; list filters; existing text `agent_name` and reseller/distributor sources untouched |
| A2 | Order integration ⚠️ feature-flag | 2.3 | Searchable registered-agent dropdown (active only); **per-unit O.P.** col on `order_items`; new unit price = base + O.P.; **patch `printOrderReceipt` (app.js:726) to drop agent name; audit ALL 8 print/export fns for agent/O.P. leaks** | O.P. stored per unit; receipt/PDF show only new unit price; **no** agent name or O.P. in any print output |
| A3 | Commission engine | 2.4, 2.5 | Period logic (28→12, 13→27); unpaid→Collectibles; **period from paid/collection date, not ledger effective_date** | Boundary dates correct; unpaid rolls to Collectibles; paid-late lands in paid-date period |
| A4 | Release workflow + statements | 2.5–2.7 | Draft→Approve→Pay states; 1st/2nd-half tables; itemised per-item statement; `agent_commissions` master table; **release authorised by ACCOUNTING-role user's own security key** | State transitions; period split; multi-item broken out per item; release blocked for non-ACCOUNTING role or wrong key |
| A5 | Cash-flow widget | 4.2 | Revenue − Expenses − Commissions; **joins `transactions` ledger + `expenses` table separately** (expenses are not in the ledger); commission as distribution | Commission not double-counted; numbers reconcile |

## Deferred — Part 3 (Daily Report Backlog)
On hold until E + A settle (its fields mirror the finished forms). Appendix only.

