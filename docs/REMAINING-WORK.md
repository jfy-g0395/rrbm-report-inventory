# RRBM — Remaining Work

> Last updated: Jun 11, 2026
> Use this file as the session-by-session checklist. Mark items `✅` when done.

---

## Section 1 — Active Fixes

These are the next items to work on, in priority order.

| # | Status | Item | Priority | Est. Effort |
|---|--------|------|----------|-------------|
| 1 | ✅ | **Lifetime Commission discrepancy** — panel header "Lifetime Commission" reads from `agent_commissions` table (released periods only). Change `AgentController.toMap()` to use `commission_entries` directly (same source as the Commission tab), eliminating the two-number confusion. | Medium | ~15 min |
| 2 | ✅ | **Release button per period in agent panel** — the Commission tab shows period rows but has no Release action. Add a Release button on `CLOSED` period rows that prompts for the admin security key and calls `POST /api/commissions/periods/{id}/release`. | Medium | ~30 min |
| 3 | ✅ | **Frontend backfill toast** — when `POST /api/commissions/periods` returns `backfill.ordersProcessed > 0`, show a toast: "Backfilled N order(s) from X agent(s) — Y commission entries created". | Low | ~10 min |

---

## Section 2 — Uncommitted Changes (housekeeping)

Sessions U20–U32 have working, test-passing changes that have not been committed yet. Commit these after Section 1 items are done.

**Backend files with uncommitted changes:**
- `AgentController.java`
- `CommissionController.java`
- `CommissionEntryRepository.java`
- `CommissionService.java`
- `ImportController.java`
- `OrderController.java`
- `OrderRepository.java`
- `OrderService.java`

**Frontend files with uncommitted changes:**
- `rrbm_frontend/rrbm-frontend/js/app.js`
- `rrbm_frontend/rrbm-frontend/css/styles.css`
- `rrbm_frontend/rrbm-frontend/index.html`

**Docs:**
- `docs/PLAN-agent-page-bugfixes.md`
- `docs/PROGRESS.md`

---

## Section 3 — Performance Optimization (defer)

**N+1 queries on agent list** — `AgentController.listAgents()` runs `1 + (N × 3)` queries per page load (orders count, lifetime commission, pending balance — each per agent).

| Agents | Queries |
|--------|---------|
| 10 | 31 |
| 50 | 151 |
| 100 | 301 |

**Status: ✅ Done (Session U36, Jun 11 2026).** Fixed ahead of schedule — bulk IN queries implemented, flat 4 queries per page load.

**Fix plan:** Already documented in `docs/PLAN-agent-page-bugfixes.md` § Issue 3. Replace per-agent calls with 3 bulk `IN (...)` queries.

**Files to touch:** `AgentRepository.java`, `CommissionEntryRepository.java`, `AgentController.java`

---

## Section 4 — Tech Debt (no timeline)

Known gaps that are non-blocking. Address only if they cause a specific problem.

| Item | Detail | Risk |
|------|--------|------|
| No pagination on `GET /api/orders` | Debug/Postman endpoint only — no frontend caller | Low |
| No Spring Security role-based rules | `anyRequest().permitAll()`, auth is JWT-filter only | Medium (post-launch) |
| JWT secret in `application.properties` | Not in an env var | Medium (post-launch) |
| No FK constraint on `order_items.product_id` | Schema gap — orphaned items possible in edge cases | Low |
| No `@Valid` input validation on most controllers | Manual checks only — some invalid inputs may slip through | Low |
| Suppliers page DB permission gate missing for existing users | Grant access per-user via Edit Employee modal as workaround | Low |

---

## Reference

| Document | Purpose |
|----------|---------|
| `docs/PROGRESS.md` | Full session-by-session build log |
| `docs/PLAN-agent-page-bugfixes.md` | Agent page bug investigation + fix details (U20, U32) |
| `docs/PLAN-agent-registry-redesign.md` | Agent Registry redesign sessions S1–S9 |
| `docs/COMMISSION-PERIOD-BUG-REPORT.md` | Commission period gap bug root cause + fix plan |
| `docs/superpowers/plans/2026-06-10-commission-period-backfill.md` | Backfill implementation checklist (U29–U31) |
| `memory/project_state.md` | Tech stack, Flyway migration state, accounting rules, key patterns |
