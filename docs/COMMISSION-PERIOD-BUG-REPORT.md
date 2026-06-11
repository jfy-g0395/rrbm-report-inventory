# Commission Period Gap — Bug Report & Fix Plan

> **Date:** June 10, 2026
> **Reported by:** User (during Agent Registry Redesign testing)
> **Status:** Confirmed Bug — Fix Planned

---

## Executive Summary

A critical bug was discovered in the commission period system: **orders placed before a commission period is opened do not receive commission entries**, causing agents to miss commissions on those orders. This document details the investigation findings, root cause analysis, and implementation plan for the fix.

---

## Part 1: Investigation Report

### 1.1 Problem Description

When testing the new Agent Registry UI, the user reported:

> "I don't want to open a period then accumulate the orders that is going in there. It will make an error or inaccuracy. What if there are batch imports for that agent and it was dated on later date? The agent will miss that order commission. Because right now, I opened a period and the previous orders that were recorded before I opened the period was not getting included in that period of time."

### 1.2 Investigation Scope

The investigation examined:
- How commission periods are created (start/end dates)
- How orders are associated with periods
- How batch imports handle period association
- Potential gaps where orders could be missed

### 1.3 Findings

#### How Commission Periods Work

| Aspect | Current Behavior |
|--------|------------------|
| **Period Creation** | Admin manually specifies `startDate` and `endDate` via `POST /api/commissions/periods` |
| **Period Lifecycle** | `OPEN` → `CLOSED` → `RELEASED` |
| **Overlap Check** | System rejects new periods that overlap with existing OPEN periods |
| **Period Code** | Auto-generated as `YYYY-MM-X` (e.g., `2026-06-A`) |

#### How Orders Link to Periods

**Key Finding:** Orders are NOT directly linked to periods. Instead, the link is established through `CommissionEntry` records created at order creation time.

**The Flow:**
```
Order Created
    ↓
CommissionService.createEntriesForOrder() runs
    ↓
System checks: Is there an OPEN period covering this order's date?
    ↓
    ├── YES → CommissionEntry created in that period ✅
    └── NO → Entry silently dropped — NO commission recorded ❌
```

#### The Critical Gap

When a period is opened **after** orders were placed:

```
June 1-14:  Orders placed (NO period exists)
June 15:    Admin opens "JUN-2026" period (June 1 - June 30)
June 15+:   Orders placed (period exists, entries created)

RESULT: Orders from June 1-14 have NO commission entries
```

#### Batch Import Behavior

- Orders are backdated to CSV date via `orderService.createOrderAtDate()`
- Commission entries are created after order save
- **Same issue applies:** If no OPEN period exists at import time, entries are dropped
- **Silent failure:** Import catches exceptions and ignores them (`catch (Exception ignored)`)

### 1.4 Root Cause Analysis

| Root Cause | Description |
|------------|-------------|
| **No Backfill Mechanism** | When a new period is opened, existing orders are NOT retroactively processed |
| **Time-of-Creation Dependency** | Commission entries are only created when the order is placed, not when the period is opened |
| **Silent Failure** | Orders without an OPEN period are dropped with no warning, error log, or queue |
| **No Reprocessing** | There's no mechanism to reprocess orders that were missed |

### 1.5 Impact Assessment

| Impact | Severity |
|--------|----------|
| **Agent Missed Commissions** | HIGH — Agents lose earnings on orders placed before period opening |
| **Data Inaccuracy** | HIGH — Commission reports don't reflect actual sales |
| **Admin Confusion** | MEDIUM — Admins don't realize orders were missed |
| **Batch Import Risk** | HIGH — Large imports can miss many orders if period isn't open |

### 1.6 Evidence from Code

#### CommissionService.java (lines 27-77)

```java
@Transactional
public void createEntriesForOrder(Order savedOrder, Long userId) {
    if (savedOrder.getAgentId() == null) return;
    if (entryRepository.existsByOrderId(savedOrder.getId())) return;

    LocalDate orderDate = savedOrder.getCreatedAt().toLocalDate();

    // Find an OPEN period covering the order date
    List<CommissionPeriod> coveringOpen = periodRepository
            .findByStartDateLessThanEqualAndEndDateGreaterThanEqual(orderDate, orderDate)
            .stream()
            .filter(p -> "OPEN".equals(p.getStatus()))
            .toList();

    CommissionPeriod period;
    if (!coveringOpen.isEmpty()) {
        period = coveringOpen.get(0);  // Normal assignment
    } else {
        // Late import fallback — find earliest OPEN period
        period = periodRepository
                .findByStatusOrderByStartDateDesc("OPEN")
                .stream()
                .min(Comparator.comparing(CommissionPeriod::getStartDate))
                .orElse(null);
        if (period == null) return;    // NO OPEN PERIOD — SILENTLY DROPS ENTRY
    }

    // Create entries...
}
```

**Line 57 is the critical issue:** `if (period == null) return;` — When no OPEN period exists, the method returns without creating any entries, with no warning or logging.

#### ImportController.java (lines 1073-1074)

```java
try {
    commissionService.createEntriesForOrder(savedOrder, userId);
} catch (Exception ignored) {}  // Silent failure on import
```

---

## Part 2: Implementation Plan

### 2.1 Solution Overview

**Approach:** Add backfill logic that automatically creates commission entries for existing orders when a new period is opened.

**Key Changes:**
1. Add `backfillEntriesForPeriod()` method to `CommissionService`
2. Call backfill after period creation in `CommissionController`
3. Return backfill statistics in API response
4. Show toast notification in frontend

### 2.2 File Changes

| File | Change Type | Description |
|------|-------------|-------------|
| `CommissionService.java` | Modify | Add `backfillEntriesForPeriod()` method |
| `CommissionController.java` | Modify | Call backfill after period creation |
| `OrderRepository.java` | Modify | Add query to find orders without commission entries |
| `CommissionEntryRepository.java` | No change | `existsByOrderId()` already exists |
| `CommissionBackfillTest.java` | Create | Integration tests for backfill logic |
| `app.js` | Modify | Show backfill stats in toast notification |

### 2.3 Detailed Tasks

#### Task 1: Add Repository Query Methods

**OrderRepository.java** — Add two new queries:

```java
// Find orders for an agent in a date range that don't have commission entries yet
@Query("SELECT o FROM Order o WHERE o.agentId = :agentId " +
       "AND CAST(o.createdAt AS date) BETWEEN :start AND :end " +
       "AND o.id NOT IN (SELECT e.orderId FROM CommissionEntry e WHERE e.agentId = :agentId) " +
       "ORDER BY o.createdAt ASC")
List<Order> findOrdersWithoutCommissionEntries(
    @Param("agentId") Long agentId,
    @Param("start") LocalDate start,
    @Param("end") LocalDate end);

// Find distinct agent IDs that have orders in a date range
@Query("SELECT DISTINCT o.agentId FROM Order o " +
       "WHERE o.agentId IS NOT NULL " +
       "AND CAST(o.createdAt AS date) BETWEEN :start AND :end")
List<Long> findAgentIdsWithOrdersInRange(
    @Param("start") LocalDate start,
    @Param("end") LocalDate end);
```

#### Task 2: Add Backfill Method to CommissionService

```java
@Transactional
public Map<String, Object> backfillEntriesForPeriod(CommissionPeriod period) {
    LocalDate startDate = period.getStartDate();
    LocalDate endDate = period.getEndDate();
    Long periodId = period.getId();

    int agentsProcessed = 0;
    int ordersProcessed = 0;
    int entriesCreated = 0;

    // Find all agents with orders in the date range
    List<Long> agentIds = orderRepository.findAgentIdsWithOrdersInRange(startDate, endDate);

    for (Long agentId : agentIds) {
        agentsProcessed++;

        // Find orders without commission entries
        List<Order> orders = orderRepository.findOrdersWithoutCommissionEntries(
            agentId, startDate, endDate);

        for (Order order : orders) {
            if (entryRepository.existsByOrderId(order.getId())) continue;

            LocalDate orderDate = order.getCreatedAt().toLocalDate();

            for (OrderItem item : order.getItems()) {
                if (item.getOpAmount() == null) continue;

                CommissionEntry entry = new CommissionEntry();
                entry.setPeriodId(periodId);
                entry.setAgentId(agentId);
                entry.setOrderId(order.getId());
                entry.setOrderItemId(item.getId());
                entry.setOrderDate(orderDate);
                entry.setProductName(item.getProductName());
                entry.setQuantity(item.getQuantity());
                entry.setBasePrice(item.getBasePrice());
                entry.setOpRate(item.getOpRate());
                entry.setOpPerUnit(item.getOpPerUnit());
                entry.setOpAmount(item.getOpAmount());
                entryRepository.save(entry);
                entriesCreated++;
            }
            ordersProcessed++;
        }
    }

    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("agentsProcessed", agentsProcessed);
    stats.put("ordersProcessed", ordersProcessed);
    stats.put("entriesCreated", entriesCreated);
    return stats;
}
```

#### Task 3: Call Backfill from CommissionController

In `createPeriod()` method, after saving the period:

```java
CommissionPeriod saved = periodRepository.save(period);

// Backfill commission entries for existing orders
Map<String, Object> backfillStats = commissionService.backfillEntriesForPeriod(saved);

Map<String, Object> response = periodToMap(saved, true);
response.put("backfill", backfillStats);
return ResponseEntity.status(HttpStatus.CREATED).body(response);
```

#### Task 4: Write Integration Tests

- Test backfill creates entries for existing orders
- Test backfill returns correct statistics
- Test backfill is idempotent (no duplicates)
- Test backfill handles no orders gracefully

#### Task 5: Update Frontend

Show toast notification with backfill stats:

```javascript
if (response.backfill && response.backfill.ordersProcessed > 0) {
    showToast(
        'Backfilled ' + response.backfill.ordersProcessed + ' order(s) — ' +
        response.backfill.entriesCreated + ' commission entry/entries created',
        'success'
    );
}
```

### 2.4 Expected API Response

After fix, `POST /api/commissions/periods` returns:

```json
{
  "periodId": 123,
  "periodCode": "2026-06-A",
  "startDate": "2026-06-01",
  "endDate": "2026-06-30",
  "status": "OPEN",
  "backfill": {
    "agentsProcessed": 5,
    "ordersProcessed": 23,
    "entriesCreated": 47
  }
}
```

### 2.5 Edge Cases Handled

| Edge Case | Handling |
|-----------|----------|
| No orders in date range | Returns zeros, no entries created |
| Orders already have entries | Skipped (idempotent) |
| No agents with orders | Returns zeros |
| Orders with null opAmount | Skipped (no commission) |
| Race condition | Double-check before creating entry |
| Multiple periods | Overlap check prevents duplicate date ranges |

---

## Part 3: Timeline

| Task | Description | Est. Time |
|------|-------------|-----------|
| 1 | Add repository query methods | 10 min |
| 2 | Add backfill method to CommissionService | 20 min |
| 3 | Call backfill from CommissionController | 10 min |
| 4 | Write integration tests | 20 min |
| 5 | Update frontend to show backfill stats | 5 min |
| 6 | Documentation and final verification | 5 min |
| **Total** | | **~70 min** |

---

## Part 4: Verification Checklist

- [ ] `mvn compile` — No syntax errors
- [ ] `mvn test` — All tests pass (142+)
- [ ] `node --check app.js` — No syntax errors
- [ ] Manual test: Open period with existing orders → verify backfill
- [ ] Manual test: Open period with no orders → verify no errors
- [ ] Manual test: Verify toast notification shows backfill stats
- [ ] Manual test: Verify agent can see backfilled commissions in Commission tab

---

## Part 5: Related Code References

| File | Line | Description |
|------|------|-------------|
| `CommissionService.java` | 27-77 | `createEntriesForOrder()` — where entries are created |
| `CommissionService.java` | 57 | `if (period == null) return;` — silent failure point |
| `CommissionController.java` | 68-122 | `createPeriod()` — where period is created |
| `CommissionEntry.java` | 1-99 | Commission entry entity definition |
| `Order.java` | 122-123 | `agentId` field on orders |
| `ImportController.java` | 1073-1074 | Silent failure on import |

---

*This document was generated during the Agent Registry Redesign project on June 10, 2026.*
