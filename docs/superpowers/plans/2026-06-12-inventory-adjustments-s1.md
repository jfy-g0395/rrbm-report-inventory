# Inventory Adjustments S1 — Return Flow + Shared Warehouse Helper

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `restockWarehouse` into the Return flow end-to-end (DTO → validation → service → frontend → tests) so sellable units land in the staff-chosen warehouse instead of silently defaulting to origin.

**Architecture:** Add `restockWarehouse` to `ReturnItemRequest` DTO; add a shared `requireValidWarehouse` helper on `InventoryService`; thread the chosen warehouse through `OrderService.processReturn` and `InventoryService.processReturnForItem`; add a per-row warehouse `<select>` to the Return modal that gates the submit button; update and expand `OrderVoidReturnIT` to prove the destination is honored and that blank/invalid values return 400.

**Tech Stack:** Java 21, Spring Boot, Lombok `@Data`, vanilla JS (app.js), JUnit 5 + Spring MockMvc integration tests.

---

## Files Touched

| File | Change |
|------|--------|
| `rrbm-backend/src/main/java/rrbm_backend/dto/ReturnOrderRequest.java` | Add `restockWarehouse` field to `ReturnItemRequest` + Javadoc |
| `rrbm-backend/src/main/java/rrbm_backend/InventoryService.java` | Add `requireValidWarehouse` helper; add `destinationWarehouse` param to `processReturnForItem`; split sellable/rejected warehouse variables |
| `rrbm-backend/src/main/java/rrbm_backend/OrderService.java` | Validate `restockWarehouse` in `processReturn` validation loop; thread it into the `processReturnForItem` call |
| `rrbm_frontend/rrbm-frontend/js/app.js` | `renderReturnItems`: add per-row warehouse select (hidden until sellable > 0); `onReturnQtyChange`: show/hide select + gate on selection; `confirmReturn`: include `restockWarehouse` in payload |
| `rrbm-backend/src/test/java/rrbm_backend/OrderVoidReturnIT.java` | Update `t03`; add `t07` (blank warehouse → 400) and `t08` (invalid warehouse → 400) |

---

## Task 1: Add `restockWarehouse` to `ReturnItemRequest` DTO

**Files:**
- Modify: `rrbm-backend/src/main/java/rrbm_backend/dto/ReturnOrderRequest.java`

- [ ] **Step 1: Add the field with Javadoc**

  Replace the closing lines of `ReturnItemRequest` (currently at line 47):

  ```java
      @Data
      public static class ReturnItemRequest {
          /** Primary key of the order_items row being returned. */
          private Long orderItemId;

          /** Total physical units coming back from the customer. */
          private Integer totalReturned;

          /** Of the returned units: how many are in resaleable condition. */
          private Integer sellableQty;

          /** Of the returned units: how many are damaged / unrecoverable. */
          private Integer rejectedQty;

          /**
           * Destination warehouse for SELLABLE units.
           * Required when sellableQty > 0; ignored otherwise.
           * Must be one of: wh1, wh2, wh3.
           */
          private String restockWarehouse;
      }
  ```

- [ ] **Step 2: Compile only (no tests yet)**

  ```bash
  cd rrbm-backend && mvn compile -q
  ```

  Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 3: Commit**

  ```bash
  git add rrbm-backend/src/main/java/rrbm_backend/dto/ReturnOrderRequest.java
  git commit -m "feat(S1): add restockWarehouse to ReturnItemRequest DTO"
  ```

---

## Task 2: Add `requireValidWarehouse` helper to `InventoryService`

**Files:**
- Modify: `rrbm-backend/src/main/java/rrbm_backend/InventoryService.java`

- [ ] **Step 1: Write the failing test for the helper**

  In `OrderVoidReturnIT.java`, add this test at line ~370 (before `t04`):

  ```java
  @Test
  void t07_returnSellableWithBlankWarehouse_returns400() throws Exception {
      String orderId = createOrderViaApi("S3-Return-NoWh-" + RUN, 2);
      Order order = orderRepository.findByIdWithItems(orderId).get();
      OrderItem item = order.getItems().get(0);
      order.setStatus("DELIVERED");
      orderRepository.save(order);

      Map<String, Object> returnItem = new HashMap<>();
      returnItem.put("orderItemId", item.getId());
      returnItem.put("totalReturned", 1);
      returnItem.put("sellableQty", 1);
      returnItem.put("rejectedQty", 0);
      // restockWarehouse intentionally omitted

      Map<String, Object> req = new HashMap<>();
      req.put("items", List.of(returnItem));
      req.put("reason", "Test");
      req.put("securityKey", SEC_KEY);

      mockMvc.perform(post("/api/orders/" + orderId + "/return")
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Authorization", "Bearer " + userJwt)
                      .content(objectMapper.writeValueAsString(req)))
              .andExpect(status().isBadRequest()); // 400
  }

  @Test
  void t08_returnSellableWithInvalidWarehouse_returns400() throws Exception {
      String orderId = createOrderViaApi("S3-Return-BadWh-" + RUN, 2);
      Order order = orderRepository.findByIdWithItems(orderId).get();
      OrderItem item = order.getItems().get(0);
      order.setStatus("DELIVERED");
      orderRepository.save(order);

      Map<String, Object> returnItem = new HashMap<>();
      returnItem.put("orderItemId", item.getId());
      returnItem.put("totalReturned", 1);
      returnItem.put("sellableQty", 1);
      returnItem.put("rejectedQty", 0);
      returnItem.put("restockWarehouse", "wh9"); // invalid

      Map<String, Object> req = new HashMap<>();
      req.put("items", List.of(returnItem));
      req.put("reason", "Test");
      req.put("securityKey", SEC_KEY);

      mockMvc.perform(post("/api/orders/" + orderId + "/return")
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Authorization", "Bearer " + userJwt)
                      .content(objectMapper.writeValueAsString(req)))
              .andExpect(status().isBadRequest()); // 400
  }
  ```

- [ ] **Step 2: Run tests to confirm they fail**

  ```bash
  cd rrbm-backend && mvn test -Dtest=OrderVoidReturnIT#t07_returnSellableWithBlankWarehouse_returns400+t08_returnSellableWithInvalidWarehouse_returns400 -q
  ```

  Expected: both tests FAIL (currently the endpoint accepts missing warehouse and defaults to origin).

- [ ] **Step 3: Add `requireValidWarehouse` to `InventoryService`**

  Insert this method just before the `logMovement` method (~line 501 in `InventoryService.java`):

  ```java
      /**
       * Validates and normalizes a destination warehouse code.
       * Must be called before any stock write when a sellable restock is requested.
       *
       * @param warehouse    raw value from the request (may be null/blank)
       * @param productLabel product name for the error message
       * @return normalized lowercase warehouse code: "wh1", "wh2", or "wh3"
       * @throws RuntimeException with a descriptive message if blank or unrecognized
       */
      String requireValidWarehouse(String warehouse, String productLabel) {
          if (warehouse == null || warehouse.isBlank())
              throw new RuntimeException(
                  "Destination warehouse is required for sellable item \""
                  + productLabel + "\". Must be wh1, wh2, or wh3.");
          String normalized = warehouse.trim().toLowerCase();
          if (!normalized.equals("wh1") && !normalized.equals("wh2") && !normalized.equals("wh3"))
              throw new RuntimeException(
                  "Invalid destination warehouse \"" + warehouse + "\" for item \""
                  + productLabel + "\". Must be wh1, wh2, or wh3.");
          return normalized;
      }
  ```

- [ ] **Step 4: Add validation to `OrderService.processReturn` validation loop**

  In `OrderService.java`, inside `processReturn`, locate the validation loop (~lines 589–617). At the end of the loop body, after the `if (total > item.getQuantity())` check (currently the last check before the closing `}`), add:

  ```java
              if (sellable > 0)
                  inventoryService.requireValidWarehouse(
                      req.getRestockWarehouse(), item.getProductName());
  ```

  The loop body now ends:
  ```java
              if (total > item.getQuantity())
                  throw new RuntimeException(
                      "Cannot return " + total + " unit(s) of \"" + item.getProductName()
                      + "\" — the original order quantity was " + item.getQuantity());
              if (sellable > 0)
                  inventoryService.requireValidWarehouse(
                      req.getRestockWarehouse(), item.getProductName());
          }
  ```

- [ ] **Step 5: Run t07 and t08 to confirm they pass**

  ```bash
  cd rrbm-backend && mvn test -Dtest=OrderVoidReturnIT#t07_returnSellableWithBlankWarehouse_returns400+t08_returnSellableWithInvalidWarehouse_returns400 -q
  ```

  Expected: both PASS.

- [ ] **Step 6: Compile to catch any issues**

  ```bash
  cd rrbm-backend && mvn compile -q
  ```

  Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

  ```bash
  git add rrbm-backend/src/main/java/rrbm_backend/InventoryService.java
  git add rrbm-backend/src/main/java/rrbm_backend/OrderService.java
  git add rrbm-backend/src/test/java/rrbm_backend/OrderVoidReturnIT.java
  git commit -m "feat(S1): add requireValidWarehouse helper and return validation"
  ```

---

## Task 3: Thread `destinationWarehouse` through `processReturnForItem`

**Files:**
- Modify: `rrbm-backend/src/main/java/rrbm_backend/InventoryService.java`
- Modify: `rrbm-backend/src/main/java/rrbm_backend/OrderService.java`
- Modify: `rrbm-backend/src/test/java/rrbm_backend/OrderVoidReturnIT.java`

- [ ] **Step 1: Write the failing destination-honored test**

  Update `t03` in `OrderVoidReturnIT.java` — replace the entire method body with:

  ```java
  @Test
  void t03_returnWithSellableAndRejected_createsRefundTransaction() throws Exception {
      String orderId = createOrderViaApi("S3-Return-Customer-" + RUN, 3);
      Order order = orderRepository.findByIdWithItems(orderId).get();
      OrderItem item = order.getItems().get(0);

      order.setStatus("DELIVERED");
      orderRepository.save(order);

      Product productBefore = productRepository.findById(product1.getId()).get();
      int stockWh1Before = productBefore.getStockWh1();
      int stockWh2Before = productBefore.getStockWh2();

      Map<String, Object> returnItem = new HashMap<>();
      returnItem.put("orderItemId", item.getId());
      returnItem.put("totalReturned", 3);
      returnItem.put("sellableQty", 2);
      returnItem.put("rejectedQty", 1);
      returnItem.put("restockWarehouse", "wh2"); // destination: WH2

      Map<String, Object> returnRequest = new HashMap<>();
      returnRequest.put("items", List.of(returnItem));
      returnRequest.put("reason", "Customer dissatisfied");
      returnRequest.put("securityKey", SEC_KEY);
      returnRequest.put("refundAmount", new BigDecimal("150.00"));

      mockMvc.perform(post("/api/orders/" + orderId + "/return")
                      .contentType(MediaType.APPLICATION_JSON)
                      .header("Authorization", "Bearer " + userJwt)
                      .content(objectMapper.writeValueAsString(returnRequest)))
              .andExpect(status().isOk());

      // Sellable units go to WH2 (chosen destination), not WH1 (origin)
      Product productAfter = productRepository.findById(product1.getId()).get();
      assertThat(productAfter.getStockWh2()).isEqualTo(stockWh2Before + 2);
      assertThat(productAfter.getStockWh1()).isEqualTo(stockWh1Before); // origin unchanged

      // Movement record for RETURN_SELLABLE has warehouse = "wh2"
      List<InventoryMovement> movements =
              inventoryMovementRepository.findByReferenceIdOrderByCreatedAtDesc(orderId);
      InventoryMovement sellableMov = movements.stream()
              .filter(m -> "RETURN_SELLABLE".equals(m.getMovementType()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("No RETURN_SELLABLE movement found"));
      assertThat(sellableMov.getWarehouse()).isEqualTo("wh2");

      // Refund transaction created
      List<Transaction> txns = transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
      assertThat(txns.stream().anyMatch(t -> "RETURN".equals(t.getTransactionType()))).isTrue();
  }
  ```

- [ ] **Step 2: Run t03 to confirm it fails**

  ```bash
  cd rrbm-backend && mvn test -Dtest=OrderVoidReturnIT#t03_returnWithSellableAndRejected_createsRefundTransaction -q
  ```

  Expected: FAIL — stock lands in wh1 (origin), not wh2.

- [ ] **Step 3: Change `processReturnForItem` signature and body**

  In `InventoryService.java`, replace the entire `processReturnForItem` method (currently lines ~442–499) with:

  ```java
      @Transactional
      public void processReturnForItem(OrderItem item, int sellableQty, int rejectedQty,
                                       String destinationWarehouse, String orderId, Long userId) {
          if (item.getProductId() == null) return;

          Product product = productRepository.findById(item.getProductId()).orElse(null);
          if (product == null) return;

          // Origin warehouse is kept as the audit tag for RETURN_REJECTED only
          String originWarehouse = item.getWarehouse() != null
                  ? item.getWarehouse().toLowerCase() : "wh1";

          // ── Sellable: restore stock to chosen destination ────────────────
          if (sellableQty > 0) {
              String destWh = requireValidWarehouse(destinationWarehouse, product.getName());
              if (Boolean.TRUE.equals(product.getIsSet())) {
                  List<ProductSetComponent> comps =
                          productSetComponentRepository.findBySetProductId(product.getId());
                  for (ProductSetComponent comp : comps) {
                      Product compProduct =
                              productRepository.findById(comp.getComponentProductId()).orElse(null);
                      if (compProduct == null) continue;
                      int restore = sellableQty * comp.getQuantityPerSet();
                      switch (destWh) {
                          case "wh2": compProduct.setStockWh2(compProduct.getStockWh2() + restore); break;
                          case "wh3": compProduct.setStockWh3(compProduct.getStockWh3() + restore); break;
                          default:    compProduct.setStockWh1(compProduct.getStockWh1() + restore); break;
                      }
                      productRepository.save(compProduct);
                      logMovement(compProduct.getId(), "RETURN_SELLABLE", destWh, +restore,
                              orderId,
                              "Return — " + sellableQty + " sellable unit(s) of set \""
                                  + product.getName() + "\" — order " + orderId,
                              userId);
                  }
              } else {
                  switch (destWh) {
                      case "wh2": product.setStockWh2(product.getStockWh2() + sellableQty); break;
                      case "wh3": product.setStockWh3(product.getStockWh3() + sellableQty); break;
                      default:    product.setStockWh1(product.getStockWh1() + sellableQty); break;
                  }
                  productRepository.save(product);
                  logMovement(item.getProductId(), "RETURN_SELLABLE", destWh, +sellableQty,
                          orderId,
                          "Return — " + sellableQty + " sellable unit(s) of \""
                              + item.getProductName() + "\" — order " + orderId,
                          userId);
              }
          }

          // ── Rejected: no stock change; keep origin warehouse as audit tag ─
          if (rejectedQty > 0) {
              logMovement(item.getProductId(), "RETURN_REJECTED", originWarehouse, rejectedQty,
                      orderId,
                      "Return — " + rejectedQty + " rejected unit(s) of \""
                          + item.getProductName() + "\" (no stock restore) — order " + orderId,
                      userId);
          }
      }
  ```

- [ ] **Step 4: Update the `processReturnForItem` call site in `OrderService`**

  In `OrderService.java`, inside `processReturn`, find the apply-loop call at line ~627:
  ```java
              inventoryService.processReturnForItem(item, sellable, rejected, orderId, userId);
  ```
  Replace it with:
  ```java
              inventoryService.processReturnForItem(
                  item, sellable, rejected, req.getRestockWarehouse(), orderId, userId);
  ```

- [ ] **Step 5: Compile**

  ```bash
  cd rrbm-backend && mvn compile -q
  ```

  Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Run t03 to confirm it now passes**

  ```bash
  cd rrbm-backend && mvn test -Dtest=OrderVoidReturnIT#t03_returnWithSellableAndRejected_createsRefundTransaction -q
  ```

  Expected: PASS.

- [ ] **Step 7: Run the full `OrderVoidReturnIT` suite**

  ```bash
  cd rrbm-backend && mvn test -Dtest=OrderVoidReturnIT -q
  ```

  Expected: all 8 tests PASS. Re-run once to confirm FK-safe cleanup.

- [ ] **Step 8: Commit**

  ```bash
  git add rrbm-backend/src/main/java/rrbm_backend/InventoryService.java
  git add rrbm-backend/src/main/java/rrbm_backend/OrderService.java
  git add rrbm-backend/src/test/java/rrbm_backend/OrderVoidReturnIT.java
  git commit -m "feat(S1): thread destinationWarehouse through processReturnForItem"
  ```

---

## Task 4: Frontend — per-row warehouse select in Return modal

**Files:**
- Modify: `rrbm_frontend/rrbm-frontend/js/app.js`

This task has three sub-changes: render, gate, payload. Do them in one edit pass, then test manually.

- [ ] **Step 1: `renderReturnItems` — add warehouse select row**

  In `renderReturnItems` (~line 10095), find the row template's closing `'</div></div>'` at line ~10127:

  Current closing:
  ```javascript
        +   '<div class="rtn-validity" style="font-size:10px;width:80px;text-align:center;"></div>'
        + '</div></div>';
  ```

  Replace with:
  ```javascript
        +   '<div class="rtn-validity" style="font-size:10px;width:80px;text-align:center;"></div>'
        + '</div>'
        + '<div class="rtn-wh-row" style="display:none;margin-top:6px;">'
        +   '<div style="font-size:10px;color:var(--text-muted);margin-bottom:2px;">Restock to warehouse</div>'
        +   '<select class="form-select rtn-warehouse" style="font-size:12px;padding:4px 8px;"'
        +   ' onchange="onReturnQtyChange()">'
        +   '<option value="">-- select --</option>'
        +   '<option value="wh1">WH1</option>'
        +   '<option value="wh2">WH2</option>'
        +   '<option value="wh3">Balagtas</option>'
        +   '</select>'
        + '</div>'
        + '</div>';
  ```

- [ ] **Step 2: `onReturnQtyChange` — show/hide select and include in gate**

  In `onReturnQtyChange` (~line 10133), inside the `querySelectorAll('.rtn-item-row').forEach` callback, after reading `sellable`, add show/hide logic:

  Find this block:
  ```javascript
      if (total <= 0) { indicator.textContent = ''; return; }  // row not participating
  ```

  Replace with:
  ```javascript
      // Show warehouse select only when sellable > 0
      var whRow = row.querySelector('.rtn-wh-row');
      if (whRow) whRow.style.display = (sellable > 0) ? '' : 'none';

      if (total <= 0) { indicator.textContent = ''; return; }  // row not participating
  ```

  Then, after the `forEach` block closes (before the submit gate line ~10161), add warehouse validation:

  Find:
  ```javascript
      var reason   = ($('rtn-reason').value      || '').trim();
      var secKey   = ($('rtn-security-key').value || '').trim();
      var refundOn = $('rtn-refund-toggle').checked;
      var refundAmt = refundOn ? (parseFloat($('rtn-refund-amount').value) || 0) : 1;

      $('rtn-submit-btn').disabled = !(anyReturning && allValid && reason && secKey && (!refundOn || refundAmt > 0));
  ```

  Replace with:
  ```javascript
      var reason   = ($('rtn-reason').value      || '').trim();
      var secKey   = ($('rtn-security-key').value || '').trim();
      var refundOn = $('rtn-refund-toggle').checked;
      var refundAmt = refundOn ? (parseFloat($('rtn-refund-amount').value) || 0) : 1;

      var warehouseOk = true;
      document.querySelectorAll('.rtn-item-row').forEach(function(r) {
        var sel = parseInt(r.querySelector('.rtn-sellable').value) || 0;
        if (sel > 0) {
          var wh = r.querySelector('.rtn-warehouse');
          if (!wh || !wh.value) warehouseOk = false;
        }
      });

      $('rtn-submit-btn').disabled = !(anyReturning && allValid && reason && secKey && (!refundOn || refundAmt > 0) && warehouseOk);
  ```

- [ ] **Step 3: `confirmReturn` — include `restockWarehouse` in payload**

  In `confirmReturn` (~line 10189), find the `items.push({...})` call:

  ```javascript
        items.push({
          orderItemId:   Number(row.getAttribute('data-item-id')),
          totalReturned: total,
          sellableQty:   sellable,
          rejectedQty:   rejected
        });
  ```

  Replace with:
  ```javascript
        var whEl = row.querySelector('.rtn-warehouse');
        var whVal = whEl ? whEl.value : '';
        var entry = {
          orderItemId:   Number(row.getAttribute('data-item-id')),
          totalReturned: total,
          sellableQty:   sellable,
          rejectedQty:   rejected
        };
        if (sellable > 0) entry.restockWarehouse = whVal;
        items.push(entry);
  ```

- [ ] **Step 4: Manual verification checklist**

  Open the frontend in the browser and test the Return modal:

  1. Open the Return modal on a delivered order.
  2. Set Total = 0, Sellable = 0 for all rows → warehouse selects remain hidden, submit stays disabled.
  3. Set Sellable = 1 on one row (without selecting a warehouse) → warehouse select appears, submit stays disabled.
  4. Select "WH2" from the warehouse select → submit becomes enabled (assuming reason + sec key filled in).
  5. Submit the return → succeeds; verify in DB that stock lands in `stock_wh2`, not `stock_wh1`.
  6. Test a rejected-only row (Sellable = 0, Rejected = 1) → no warehouse select visible, submit still works.

- [ ] **Step 5: Commit**

  ```bash
  git add rrbm_frontend/rrbm-frontend/js/app.js
  git commit -m "feat(S1): add per-row restock warehouse select to Return modal"
  ```

---

## Task 5: Full regression and checklist update

- [ ] **Step 1: Run the full `OrderVoidReturnIT` suite one final time**

  ```bash
  cd rrbm-backend && mvn test -Dtest=OrderVoidReturnIT -q
  ```

  Expected: 8 tests PASS. Re-run once to confirm FK-safe cleanup.

- [ ] **Step 2: Update `inventory-adjustments.md` session checklist**

  Open `inventory-adjustments.md` and tick the S1 Backend, Frontend, and Tests checkboxes, then update the Progress Tracker row for S1 to `✅ done`.

- [ ] **Step 3: Final commit**

  ```bash
  git add inventory-adjustments.md
  git commit -m "chore: mark S1 complete in inventory-adjustments.md"
  ```

---

## Self-Review

### Spec coverage

| Spec requirement | Task covering it |
|-----------------|-----------------|
| `restockWarehouse` on `ReturnItemRequest` | Task 1 |
| `requireValidWarehouse` helper (returns normalized, throws on blank/unknown) | Task 2 Step 3 |
| `OrderService.processReturn` validate before writes | Task 2 Step 4 |
| `processReturnForItem` uses destination for SELLABLE | Task 3 Step 3 |
| Origin kept on `RETURN_REJECTED` | Task 3 Step 3 (originWarehouse variable) |
| Frontend per-row warehouse select (hidden until Sellable > 0) | Task 4 Step 1–2 |
| Submit gate requires warehouse when Sellable > 0 | Task 4 Step 2 |
| Payload includes `restockWarehouse` | Task 4 Step 3 |
| `t03` asserts destination honored + movement.warehouse | Task 3 Step 1 |
| Blank warehouse → 400 | Task 2 Step 1 (t07) |
| Invalid `wh9` → 400 | Task 2 Step 1 (t08) |

### No placeholders — all steps have exact code shown.

### Type consistency — `requireValidWarehouse(String, String)` signature used consistently in Task 2 Step 3, Task 2 Step 4, and Task 3 Step 3.
