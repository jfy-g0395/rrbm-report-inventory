# RRBM — Expense & Agent Commission Build Plan

A session-by-session plan for Claude Code. Goal: ship reliably, test as we go, and keep token/usage cost low by never re-exploring the whole repo.

---

## Token discipline (read this before every session)

1. **Session 0 writes `docs/BUILD_CONTEXT.md`** — the map of the existing app (real table names, models, roles, where receipts/PDF/accounting live). Every later session starts by reading `docs/BUILD_CONTEXT.md` + this plan, then opens **only** the files its session names. Do not grep or scan the whole repo again.
2. **One vertical slice per session.** A session is "done" only when its tests pass.
3. **End every session** by appending ~5 lines to `docs/PROGRESS.md`: what changed, files touched, any decision made, and the exact starting point for the next session.
4. **Read-before-write:** open only the files listed for the session. Don't open the world "to be safe."
5. **Tests live in the same session** as the code they cover. Nothing is left "to test later."
6. **One session = one Claude Code session.** If a session finishes with budget to spare, you may pull the next session in; never split one slice across two sittings.

## Locked business rules (already decided)

- **Commission periods:** 1st = 28th of prev month → 12th of current; 2nd = 13th → 27th of current.
- **Payment governs the period:** an order's O.P. counts in the period of its **paid date**. Unpaid agent orders go to **Collectibles** and only enter a commission period once paid.
- **O.P. is a distribution, not an expense.** Never counted in the operational expense totals; shown only in commission/cash-flow reports.
- **Backdating window for expenses is configurable** (a setting, not hardcoded).
- **Voids are negative adjustments**, never deletes. **O.P. is stored per unit on the line item**, never as an order-level lump. Do not let these be "simplified."
- Currency: PHP. Payment methods: Cash, Bank Transfer, GCash, Maya, Card.

---

## Session 0 — Recon & plan reconciliation (NO feature code)

- **Goal:** produce `docs/BUILD_CONTEXT.md` documenting the real app.
- **Output must cover:** (a) orders + order line-item tables/models with exact field names, and where `source`/agent currently live; (b) the expenses table/model and current expense flow; (c) the user/role/permission system and the real role names; (d) where receipts and PDF generation are implemented; (e) how the accounting / cash-flow module records money.
- **Also:** append a "Plan Reconciliation" section flagging anywhere this plan's assumed names/tables conflict with reality.
- **Done when:** the doc exists with real file paths and names, and this plan has been reconciled against it.

---

## Expense track (lower risk — build first)

| # | Session | Output | Tests / Done criteria |
|---|---------|--------|------------------------|
| E1 | Expense schema | Migrations for categories + expenses (incl. void/audit columns); seed the system categories & sub-categories | Migration up/down clean; seed rows correct; void & audit columns present |
| E2 | Entry API + form | Create/list expense with all fields; **configurable** backdating setting | Create + validation pass; backdate inside window OK, outside window rejected |
| E3 | Void / return | Void = offsetting negative entry; role-gated; reason required; audit captured | Original preserved; totals exclude voided; only authorized role can void |
| E4 | Daily report + dashboard widget | Today's total + MTD widget; daily report grouped by category | Totals math correct; grouping correct; voided excluded from totals |
| E5 | Monthly report + exports | Monthly report (grand total, category %, adjustments section) + PDF/Excel/CSV export | Grand total & %s correct; adjustments shown; each export opens & matches screen |

---

## Agent track (higher risk — touches live orders; build second)

| # | Session | Output | Tests / Done criteria |
|---|---------|--------|------------------------|
| A1 | Agent registry | Agent schema + CRUD + list view with totals columns | CRUD works; list filters by territory/status |
| A2 | Order integration ⚠️ behind feature flag | Searchable **registered-agent** dropdown (active only); **per-unit O.P.** field; new unit price = base + O.P.; **suppress agent name & O.P. on receipt and PDF** | O.P. stored per unit; receipt/PDF show **only** new unit price; **no** agent name or O.P. anywhere in receipt or PDF output. Test this hard. |
| A3 | Commission engine | Period logic (28→12, 13→27); unpaid → Collectibles; **paid date** sets the period | Boundary dates land in correct period; unpaid order rolls to Collectibles; once paid, lands in the paid-date period |
| A4 | Release workflow + statements | Draft → Approve → Pay states; **1st-half / 2nd-half tables**; itemized per-item statement; commission master table | State transitions correct; period split correct; multi-item order broken out per item |
| A5 | Cash-flow widget | Revenue − expenses − commissions, with commission as a **distribution** | Commission not double-counted in expense totals |

---

## Deferred — Part 3 (Daily Report Backlog)

On hold until E1–E5 and A1–A5 are settled, because its fields mirror the finished expense and order forms. When revisited it becomes "the same fields + a sync/conflict + offline-storage layer," so it stays cheap to add later. Keep as an appendix only.


