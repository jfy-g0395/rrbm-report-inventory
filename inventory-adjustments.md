# Inventory Adjustments — Per-Item Destination Warehouse on Restock

> Working document. Chopped into small sessions so each fits in a single context window
> without burning tokens. **Update the checklists in this file as you go.** Full design
> rationale lives in the approved plan: `C:\Users\franc\.claude\plans\okay-let-s-create-a-foamy-shannon.md`.

---

## Purpose

When sellable units are restocked on a **Return**, **Void**, or **Cancel-for-replacement**, the
system today silently puts them back into the **originating** warehouse the item was sold from
(`InventoryService` uses `item.getWarehouse()`). Physically, returned goods are placed wherever
staff put them — often a different warehouse. This drifts the per-warehouse counts
(wh1 / wh2 / wh3-"Balagtas") away from reality and breaks accurate stock tracing.

**Fix:** require an authorized user to **explicitly choose the destination warehouse, per item**,
for any line whose units go back into sellable stock. No silent fallback to origin.

---

## Scope

**In scope**
- Add a per-item `restockWarehouse` choice to the SELLABLE restock path of Return, Void, and
  Cancel-for-replacement.
- Backend validation: required + valid (`wh1`/`wh2`/`wh3`) on every restocking line → 400 if missing/invalid.
- Frontend: per-row warehouse dropdown, blank by default, shown only when the line restocks,
  wired into the submit-gate.
- Tests proving the destination is honored (stock lands in chosen wh, not origin).

**Out of scope (do NOT touch)**
- The REJECTED path. Rejected/damaged units never restock and already flow to the Rejected Items
  page via `RETURN_REJECTED`/`VOID_REJECTED`/`CANCEL_REJECTED` movements. Leave it alone.
- SET-product decomposition behavior for Void (pre-existing: Void doesn't decompose sets — leave as-is).
- The wh3 "Santan" vs "Balagtas" cosmetic label mismatch (flag only).
- DB schema — no migration; only the *value* written to `inventory_movements.warehouse` changes.

---

## Rules (apply to every session)

1. **One flow per session, as a vertical slice** (DTO → service → frontend → tests). Each session
   must leave the codebase **compiling and working**.
2. **Each inventory method has exactly one caller** (the matching `OrderService` method) — confirmed —
   so changing a signature within a session never leaves a dangling caller.
3. **Validate up front, inside the service method** (within the controller's existing `try/catch`)
   so a thrown `RuntimeException` maps to **HTTP 400**. Do not use `ResponseStatusException`.
4. **Required only when the line restocks.** Rejected-only lines (Return) / REJECTED disposition
   (Void/Cancel) / fully-voided lines (Cancel `remaining ≤ 0`) need **no** destination.
5. **Preserve the rejected flow.** Keep writing `item.getWarehouse()` (origin) as the audit tag on
   `*_REJECTED` movements so the `NOT NULL` warehouse column is never blank.
6. **Reuse, don't reinvent:** one shared helper `InventoryService.requireValidWarehouse(...)`
   (added in Session 1); reuse the delivery form's warehouse `<select>` markup (WH1 / WH2 / Balagtas).
7. **Update this file's checklist** at the end of every session before stopping.

---

## Files Touched (master list)

| File | Sessions | Change |
|------|----------|--------|
| `rrbm-backend/.../dto/ReturnOrderRequest.java` | S1 | add `restockWarehouse` to `ReturnItemRequest` |
| `rrbm-backend/.../dto/VoidOrderRequest.java` | S2 | add `restockWarehouse` to `VoidItemRequest` |
| `rrbm-backend/.../dto/CancelForReplacementRequest.java` | S3 | add `restockWarehouse` to `CancelItemDisposition` |
| `rrbm-backend/.../InventoryService.java` | S1 (helper + return), S2 (void), S3 (cancel) | `requireValidWarehouse` helper; destination param on the 3 restock methods |
| `rrbm-backend/.../OrderService.java` | S1/S2/S3 | validate-on-restock + thread destination in `processReturn` / `voidOrderItems` / `cancelOrderForReplacement` |
| `rrbm-backend/.../OrderController.java` | S4 (optional) | Javadoc example only |
| `rrbm_frontend/rrbm-frontend/js/app.js` | S1 (return), S2 (void), S3 (cancel) | per-row warehouse select + submit-gate + payload |
| `rrbm-backend/.../test/.../OrderVoidReturnIT.java` | S1/S2/S3 | update payloads + destination-honored / 400 assertions |
| (other ITs touching `/return` `/void` `/cancel-for-replacement`) | S4 | update payloads after grep |

---

## Sessions

### S1 — Return flow (+ shared helper)  ✅ done
**Backend**
- [x] `dto/ReturnOrderRequest.java`: add `private String restockWarehouse;` to `ReturnItemRequest` + Javadoc.
- [x] `InventoryService.java`: add `requireValidWarehouse(String warehouse, String productLabel)` helper
      (returns normalized `wh1/wh2/wh3`; throws `RuntimeException` on blank/unknown).
- [x] `InventoryService.processReturnForItem(...)` (`:442`): add `String destinationWarehouse` param;
      when `sellableQty>0`, `warehouse = requireValidWarehouse(destinationWarehouse, product.getName())`
      (covers regular + SET-component branches). Keep origin tag on `RETURN_REJECTED`.
- [x] `OrderService.processReturn(...)` (`:573`): in the validation loop (`:588-617`) add
      `if (sellable > 0) inventoryService.requireValidWarehouse(req.getRestockWarehouse(), item.getProductName());`
      then pass `req.getRestockWarehouse()` into the apply call (`:627`).
- [x] Compile.

**Frontend** (`js/app.js`)
- [x] `renderReturnItems` (`:10095`): add per-row `<select class="rtn-warehouse">` (blank default + WH1/WH2/Balagtas), hidden until Sellable>0.
- [x] `onReturnQtyChange` (`:10133`): require a warehouse on any row with sellable>0 before enabling submit (`:10161`).
- [x] `confirmReturn` (`:10173`): include `restockWarehouse` per sellable item (`:10189`).

**Tests** (`OrderVoidReturnIT.java`)
- [x] `t03` (return sellable+rejected): add `restockWarehouse:"wh2"`; assert `stockWh2` +sellable, `stockWh1` unchanged, movement `warehouse=="wh2"`.
- [x] New: blank `restockWarehouse` on sellable line → 400 (t07); invalid `"wh9"` → 400 (t08).
- [x] `mvn test -Dtest=OrderVoidReturnIT` green; re-run to confirm FK-safe cleanup.

---

### S2 — Void flow  ⬜ not started
**Backend**
- [ ] `dto/VoidOrderRequest.java`: add `restockWarehouse` to `VoidItemRequest` + Javadoc.
- [ ] `InventoryService.restoreStockForVoidedItem(...)` (`:378`): add `String destinationWarehouse` param;
      when `restoreStock` (`!isDelivered || SELLABLE`), use validated destination. Keep origin tag on `VOID_REJECTED`.
- [ ] `OrderService.voidOrderItems`: validate destination when `!isDelivered || disposition==SELLABLE`; thread it in.
- [ ] Compile.

**Frontend** (`js/app.js`)
- [ ] Void modal (item render fn ~`:5441` disposition radios): per-row warehouse select shown when line will restock
      (SELLABLE disposition, or any line on a non-DELIVERED order).
- [ ] Void submit-gate requires the warehouse on restocking lines.
- [ ] Void confirm fn: include `restockWarehouse` per restocking item.

**Tests** (`OrderVoidReturnIT.java`)
- [ ] `t01` (void SELLABLE): add `restockWarehouse:"wh2"`; assert lands in wh2, not origin; movement `warehouse=="wh2"`.
- [ ] `t02` (void REJECTED): no warehouse needed — still passes (negative coverage).
- [ ] New: SELLABLE void with blank warehouse → 400.
- [ ] `mvn test -Dtest=OrderVoidReturnIT` green.

---

### S3 — Cancel-for-replacement flow  ⬜ not started
**Backend**
- [ ] `dto/CancelForReplacementRequest.java`: add `restockWarehouse` to `CancelItemDisposition` + Javadoc.
- [ ] `InventoryService.restoreStockForCancelledWithDisposition(...)` (`:284`): add parallel
      `Map<Long,String> destinationMap`; when a line restocks, use `requireValidWarehouse(destinationMap.get(id), ...)`.
      Keep origin tag on `CANCEL_REJECTED`.
- [ ] `OrderService.cancelOrderForReplacement`: build `destinationMap` (parallel to `dispositionMap`);
      validate for lines with `remaining = quantity − voidedQuantity > 0` resolving to SELLABLE; pass map in.
- [ ] Compile.

**Frontend** (`js/app.js`)
- [ ] Cancel-for-replacement modal (~`:5757` disposition buttons): per-row warehouse select for SELLABLE lines with remaining qty.
- [ ] Submit-gate requires it; confirm fn includes `restockWarehouse` per restocking item.
- [ ] **Note the tightening:** non-DELIVERED cancels now require destinations in the `items` list — ensure the modal always sends them.

**Tests** (`OrderVoidReturnIT.java`)
- [ ] `t04`/`t05` (cancel-for-replacement, non-DELIVERED): add `items` with `orderItemId`+`restockWarehouse`; assert honored.
- [ ] New: SELLABLE cancel line with missing warehouse → 400.
- [ ] `mvn test -Dtest=OrderVoidReturnIT` green.

---

### S4 — Cross-cutting cleanup & full verification  ⬜ not started
- [ ] Grep test suite for other ITs POSTing `/return`, `/void`, `/cancel-for-replacement` with sellable/SELLABLE lines; update payloads.
- [ ] (Optional) `OrderController.java` `/return` Javadoc example: add `restockWarehouse`.
- [ ] Full backend regression: run the broader IT suite to confirm nothing else broke.
- [ ] Manual frontend spot-check (per approved plan): dropdown appears only on sellable lines, blank default, submit blocked until chosen, stock lands in chosen wh; rejected-only line shows no dropdown and submits fine. Repeat for Void + Cancel.
- [ ] Confirm Rejected Items page still lists return/void/cancel rejections (untouched).

---

## Progress Tracker

| Session | Backend | Frontend | Tests | Status |
|---------|---------|----------|-------|--------|
| S1 Return | ✅ | ✅ | ✅ | ✅ done |
| S2 Void | ⬜ | ⬜ | ⬜ | ⬜ not started |
| S3 Cancel | ⬜ | ⬜ | ⬜ | ⬜ not started |
| S4 Cleanup/verify | — | — | ⬜ | ⬜ not started |

**Legend:** ⬜ not started · 🚧 in progress · ✅ done
