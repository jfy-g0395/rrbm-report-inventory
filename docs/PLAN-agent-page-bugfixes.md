# Plan: Agent Page Bug Fixes + Performance Optimization

**Session:** U20 (completed)
**Date:** Jun 10, 2026
**Status:** COMPLETE

## Goal
Fix two bugs on the Agent page and plan a performance optimization for the agent list endpoint.

## Issues Summary

| # | Issue | Severity | Type |
|---|-------|----------|------|
| 1 | Statement Export missing `/api/` prefix | **High** — feature broken | Bug |
| 2 | No agent status toggle UI | **Medium** — missing feature | Gap |
| 3 | N+1 queries on agent list | **Low** — scalability concern | Performance |

---

## Issue 1: Statement Export Missing `/api/` Prefix

### What's happening
When a user opens the Agent Performance modal and clicks the download button for a commission statement, the frontend calls:

```
/commissions/periods/{periodId}/agents/{agentId}/statement/export?format=pdf
```

But the backend expects:

```
/api/commissions/periods/{periodId}/agents/{agentId}/statement/export?format=pdf
```

Because the `/api/` prefix is missing, nginx does not proxy the request to the Spring Boot backend. Instead, the `try_files $uri /index.html` fallback returns the SPA HTML. The frontend receives HTML instead of PDF/CSV and the export fails.

### Root cause
`downloadStatement()` at `app.js:10666` builds the URL as:

```javascript
var url = API_BASE + '/commissions/periods/' + periodId + '/agents/' + agentId +
          '/statement/export?format=' + encodeURIComponent(fmt);
```

`API_BASE` is typically empty or `/` in Docker (the frontend is served from the same origin). The correct path must include `/api/` before `commissions`.

### Backend reference
- `CommissionController.java:26` — `@RequestMapping("/api/commissions")`
- `CommissionController.java:757` — `@GetMapping("/periods/{id}/agents/{agentId}/statement/export")`
- Full expected path: `/api/commissions/periods/{id}/agents/{agentId}/statement/export`

### Fix

**File:** `js/app.js` — line 10666

Change:
```javascript
var url = API_BASE + '/commissions/periods/' + periodId + '/agents/' + agentId +
          '/statement/export?format=' + encodeURIComponent(fmt);
```

To:
```javascript
var url = API_BASE + '/api/commissions/periods/' + periodId + '/agents/' + agentId +
          '/statement/export?format=' + encodeURIComponent(fmt);
```

**1 line change.** Same pattern as the expense page fixes (Session U19 post-round).

### Verification
- `node --check app.js` — no syntax errors
- Browser test: open Agent page → click History → click download button → statement opens/downloads

### Status: ✅ FIXED (U20)

---

## Issue 2: No Agent Status Toggle UI

### What's happening
The backend has a fully working endpoint to change an agent's status:

```
PATCH /api/agents/{id}/status
Body: { "status": "INACTIVE" }
```

But the frontend has no UI to call it:

- Agent list table: Edit, History, Comm buttons — no status toggle
- Edit Agent modal: name, contact, territory, email, notes — no status field
- No activate/deactivate button anywhere

If an agent leaves or is created by mistake, there is no way to deactivate them from the UI.

### Backend reference
- `AgentController.java:263` — `PATCH /api/agents/{id}/status` accepts `Map<String,String>` body with `status` key
- Validates status is ACTIVE or INACTIVE
- Returns updated agent as JSON

### Fix — Option A: Status toggle in the agent table (Implemented)

Added a toggle button next to the status badge in each agent row. Clicking it calls `PATCH /api/agents/{id}/status` to flip between ACTIVE ↔ INACTIVE.

**File:** `js/app.js` — `loadAgents()` (~line 10318)

After the status badge `<span>`, added a small toggle button:

```javascript
'<button class="btn btn-sm btn-outline rc-agent-toggle" onclick="toggleAgentStatus(' + a.id + ', \'' + a.status + '\')" title="Toggle status">' +
  '<i class="ti ti-toggle-' + (a.status === 'ACTIVE' ? 'right' : 'left') + '"></i>' +
'</button>'
```

**New function** in `app.js`:

```javascript
window.toggleAgentStatus = async function (agentId, currentStatus) {
  var newStatus = currentStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
  var confirmMsg = newStatus === 'INACTIVE'
    ? 'Deactivate this agent? They will not appear in the order form agent dropdown.'
    : 'Reactivate this agent?';
  if (!confirm(confirmMsg)) return;
  try {
    var res = await fetch(API_BASE + '/api/agents/' + agentId + '/status', {
      method: 'PATCH',
      headers: Object.assign({}, authHeaders(), { 'Content-Type': 'application/json' }),
      body: JSON.stringify({ status: newStatus })
    });
    if (!res.ok) { showToast('Failed to update status (' + res.status + ')', 'error'); return; }
    showToast('Agent ' + (newStatus === 'ACTIVE' ? 'activated' : 'deactivated'), 'success');
    loadAgents();
  } catch (err) {
    console.error('toggleAgentStatus', err);
    showToast('Error updating status', 'error');
  }
};
```

**CSS:** Added `.rc-agent-toggle` style in `styles.css`:

```css
.rc-agent-toggle {
  font-size: 10px;
  padding: 2px 6px;
  margin-left: 4px;
  vertical-align: middle;
}
```

**Scope:** ~20 lines JS + 6 lines CSS. No backend changes.

### Verification
- `node --check app.js` — no syntax errors
- `mvn test` — 142/142 green, BUILD SUCCESS
- Browser test: Agent page → click toggle → confirm dialog → status badge updates → refresh persists

### Status: ✅ FIXED (U20)

---

## Issue 3: N+1 Queries on Agent List (Future Optimization)

### What's happening
When the Agents page loads, `AgentController.listAgents()` runs:

1. 1 query to fetch all agents (filtered/sorted)
2. **Per agent:** 3 additional queries:
   - `countOrdersByAgentId(agentId)` — total orders
   - `lifetimeNetCommission(agentId)` — lifetime commission (JPQL sum)
   - `sumPendingOpAmountByAgentId(agentId)` — pending balance

**Query count formula:** `1 + (N × 3)` where N = number of agents

| Agents | Queries |
|--------|---------|
| 10 | 31 |
| 50 | 151 |
| 100 | 301 |
| 200 | 601 |

### Impact
- With <50 agents: negligible (sub-100ms)
- With 100+ agents: noticeable slowdown on page load
- Not a bug — just a scalability concern

### Planned fix (future session)

Replace per-agent queries with bulk queries:

1. **`countOrdersByAgentIds(List<Long> ids)`** — single query with `WHERE agent_id IN (...)` + `GROUP BY agent_id`, returns `Map<Long, Long>`
2. **`lifetimeNetCommissionByAgentIds(List<Long> ids)`** — single JPQL sum with `GROUP BY agent_id`, returns `Map<Long, BigDecimal>`
3. **`sumPendingByAgentIds(List<Long> ids)`** — single query with `WHERE agent_id IN (...)` + `GROUP BY agent_id`, returns `Map<Long, BigDecimal>`

Then in `listAgents()`:

```java
List<Long> agentIds = agents.stream().map(Agent::getId).collect(Collectors.toList());
Map<Long, Long> orderCounts = agentRepository.countOrdersByAgentIds(agentIds);
Map<Long, BigDecimal> lifetimeComms = commissionEntryRepository.lifetimeNetCommissionByAgentIds(agentIds);
Map<Long, BigDecimal> pendingAmounts = commissionEntryRepository.sumPendingByAgentIds(agentIds);

List<Map<String, Object>> result = agents.stream()
    .map(a -> toMap(a,
        orderCounts.getOrDefault(a.getId(), 0L),
        lifetimeComms.getOrDefault(a.getId(), BigDecimal.ZERO),
        pendingAmounts.getOrDefault(a.getId(), BigDecimal.ZERO)))
    .collect(Collectors.toList());
```

**New `toMap` overload** accepts pre-fetched values instead of re-querying.

**Query count after fix:** 4 (1 agents + 3 bulk) regardless of agent count.

### Files to modify (future)
- `AgentRepository.java` — add 3 bulk query methods
- `CommissionEntryRepository.java` — add bulk sum methods (or merge into AgentRepository via `@Query`)
- `AgentController.java` — refactor `listAgents()` to use bulk queries, add new `toMap` overload

### Priority
Low — defer until agent count approaches 50+. Current N+1 is acceptable for typical restaurant operations (5–20 agents).

---

## Implementation Order

1. **Issue 1** (statement export) — 1 line fix ✅
2. **Issue 2** (status toggle) — ~20 lines ✅
3. **Issue 3** (N+1) — defer, document only

## Files Modified

| File | Changes |
|------|---------|
| `js/app.js` | Fixed `downloadStatement()` URL prefix (line 10666); added `toggleAgentStatus()` function; modified `loadAgents()` to render toggle button |
| `css/styles.css` | Added `.rc-agent-toggle` style |

## Verification

- `node --check app.js` — no syntax errors ✅
- `mvn test` — 142/142 green, BUILD SUCCESS ✅

---

## Issue 4: Pending Commission Shows ₱0.00 on Agent Cards

### What's happening
Agent list cards display ₱0.00 for Pending Commission despite agents having orders in an OPEN period. The Commission tab inside the agent panel shows correct amounts.

### Root cause
Stale deployment — the running Spring Boot server was using outdated compiled classes. Spring DevTools did not trigger a restart when `target/classes` was updated. The code and database were correct throughout.

### Investigation
1. **DB verified** — 6 PENDING entries: Agent 833 (₱830.00), Agent 939 (₱217.50)
2. **Query verified** — `sumPendingOpAmountByAgentId()` filters `status = 'PENDING'` correctly
3. **Controller verified** — `toMap()` returns `pendingCommission` field correctly
4. **Frontend verified** — `app.js:10312` reads `a.pendingCommission || 0` correctly

### Two different queries (by design)
- **Card** → `sumPendingOpAmountByAgentId()` — filters `status = 'PENDING'`
- **Commission tab** → `sumByPeriodIdAndAgentId()` — no status filter

### Resolution
User restarted the backend server. Pending Commission now displays correctly.

### Status: ✅ RESOLVED (U32)

---

## Remaining Issues

### Lifetime Commission discrepancy
Panel header "Lifetime Commission" sums `AgentCommission` table (only RELEASED periods). Commission tab sums ALL periods from `commission_entries`. User confirmed this is confusing. Needs UX decision on which value to show.

### Release button location
Release button is only in the Commission Period Management Modal (opened via "Periods" button on Agent Registry page header), not accessible from the agent panel itself.
