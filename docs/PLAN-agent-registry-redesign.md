# Agent Registry Redesign Plan

## Goal
Redesign the Agent Registry page to be less overwhelming and more user-friendly. Replace the table layout with a card grid, and add a slide-out panel for agent details (orders + commissions).

## Status: ALL TASKS COMPLETE ✅

| Session | Status |
|---------|--------|
| S1 — Backend: Orders by Agent Endpoint | ✅ COMPLETE |
| S2 — Backend: Commission Summary Endpoint | ⏭️ SKIPPED |
| S3 — Frontend: Slide-out Panel CSS + HTML | ✅ COMPLETE |
| S4 — Frontend: Agent Card Grid | ✅ COMPLETE |
| S5 — Frontend: Slide-out Panel JS | ✅ COMPLETE |
| S6 — Frontend: Panel Content | ✅ COMPLETE (merged with S5) |
| S7 — Frontend: Orders Tab Period Filter | ✅ COMPLETE |
| S8 — Frontend: Commission Tab + Export | ✅ COMPLETE |
| S9 — Cleanup | ✅ COMPLETE |

**Note:** A commission period gap bug was discovered during testing. See `docs/COMMISSION-PERIOD-BUG-REPORT.md` for details and fix plan.

## Sessions

### S1 — Backend: Orders by Agent Endpoint ✅
- Add `findByAgentIdWithItems()` to `OrderRepository`
- Add `GET /api/agents/{id}/orders?periodId=` endpoint to `AgentController`
- **Status:** COMPLETE — 142/142 tests green

### S2 — Backend: Commission Summary Endpoint ⏭️ SKIPPED
- **Decision:** Reuse existing endpoints instead of creating a redundant one.
- Frontend will use:
  - `GET /api/agents/{id}` — agent info
  - `GET /api/agents/{id}/performance` — period summaries (commissionSummary array)
  - `GET /api/commissions/agents/{id}/commissions/breakdown?periodId=X` — order-level detail
- **Status:** SKIPPED — no new endpoint needed

### S3 — Frontend: Slide-out Panel CSS + HTML ✅
- Added CSS for `.slide-panel`, `.slide-panel-overlay`, `.slide-panel-header`, `.slide-panel-body`, `.slide-panel-tabs`, `.slide-panel-tab`, `.slide-panel-info`, `.slide-panel-stats`
- Added HTML structure for slide-out panel after last modal
- **Status:** COMPLETE

### S4 — Frontend: Agent Card Grid ✅
- Added CSS for `.agent-grid`, `.agent-card`, `.agent-card-top`, `.agent-card-code`, `.agent-card-status`, `.agent-card-name`, `.agent-card-territory`, `.agent-card-stats`
- Replaced `<table>` with `<div class="agent-grid">` in `view-agents`
- Updated `loadAgents()` to render cards instead of table rows
- **Status:** COMPLETE

### S5 — Frontend: Slide-out Panel JS (Open/Close) ✅
- Added `_currentAgentId` and `_currentAgentData` state variables
- Added `openAgentPanel(agentId)` — fetches agent info, populates panel, opens it
- Added `closeAgentPanel()` — closes overlay + panel
- Added `switchAgentTab(tab)` — switches between Orders/Commission tabs
- Added `editCurrentAgent()` — opens edit modal for current agent
- Added `toggleCurrentAgentStatus()` — toggles status for current agent
- Added `loadAgentOrders(agentId, periodId)` — fetches and renders orders
- Added `loadAgentCommission(agentId)` — fetches and renders commission periods
- **Status:** COMPLETE

### S6 — Frontend: Panel Content — Agent Info + Summary Cards ✅
- Already implemented in S5 (openAgentPanel renders agent info + stats)
- **Status:** COMPLETE (merged with S5)

### S7 — Frontend: Orders Tab with Period Filter ✅
- Added `_currentAgentPeriods` state variable
- Modified `openAgentPanel()` to fetch periods from `GET /api/agents/{id}/performance`
- Modified `loadAgentOrders()` to render period dropdown grouped by year
- Period dropdown includes "All Periods" option and grouped `<optgroup>` by year
- Selecting a period filters orders via `periodId` query param
- **Status:** COMPLETE

### S8 — Frontend: Commission Tab + Export ✅
- Added `_currentAgentExportFormat` state variable (default: 'pdf')
- Modified `loadAgentCommission()` to accept `periodId` param
- Added period dropdown grouped by year (same pattern as Orders tab)
- Added export format selector dropdown (PDF/CSV/Excel)
- Added export button per row in commission table
- Added `downloadCommissionStatement(agentId, periodId)` function
- Updated `switchAgentTab()` to pass `null` periodId to `loadAgentCommission()`
- **Status:** COMPLETE

### S9 — Cleanup ✅
- Removed old modals from HTML: `modal-agent-performance`, `modal-commission-breakdown`
- Removed old functions from JS: `openAgentPerformanceModal`, `openCommissionBreakdownModal`, `loadCommissionBreakdown`, `downloadStatement`
- Removed ~193 lines JS + ~25 lines HTML
- **Status:** COMPLETE

## API Endpoints

### Existing
- `GET /api/agents` — list all agents
- `GET /api/agents/{id}` — single agent detail
- `GET /api/agents/{id}/performance` — commission history
- `GET /api/commissions/agents/{id}/commissions/breakdown?periodId=` — order-level commission detail
- `GET /api/commissions/periods` — list all periods
- `GET /api/commissions/periods/{id}/agents/{agentId}/statement/export` — export statement

### New (S1)
- `GET /api/agents/{id}/orders?periodId=` — list orders for agent

### New (S2)
- SKIPPED — reusing existing endpoints:
  - `GET /api/agents/{id}` for agent info
  - `GET /api/agents/{id}/performance` for period summaries
  - `GET /api/commissions/agents/{id}/commissions/breakdown?periodId=` for order-level detail

## Period Handling
Periods are grouped by year in dropdowns:
```
── 2025 ──
  JUN-2025 (Jun 1 - Jun 30)
  MAY-2025 (May 1 - May 31)
── 2024 ──
  DEC-2024 (Dec 1 - Dec 31)
```

## Known Issue — Commission Period Gap

**Discovered:** June 10, 2026 (during testing)

**Problem:** Orders placed before a commission period is opened don't get commission entries.

**Root Cause:** `CommissionService.createEntriesForOrder()` silently drops entries when no OPEN period exists.

**Fix Plan:** See `docs/COMMISSION-PERIOD-BUG-REPORT.md`

**Status:** PLANNED — Ready for next session
