# Hotfix Runbook — Batch-Import Product-Code Inventory Gap

**Branch:** `hotfix/import-product-code-inventory-gap` (based on `main`)
**Severity:** High — records revenue without deducting stock
**Audience:** Claude (or engineer) applying this patch to the **live server**

> READ THIS ENTIRE FILE BEFORE APPLYING ANYTHING. Do the pre-flight checks first.
> If any check fails, STOP and report — do not deploy.

---

## 1. What the bug is

During **batch import** of sales (`POST /api/import/upload/sales`, `/upload/combined`, then
`/commit`), a line item whose **Product Code did not match any product** was parsed as
**VALID** with a `null` productId.

Downstream effect:
- The order **committed** as a real order (total = qty × unit price, SALE ledger entry,
  commission created).
- `InventoryService.deductStockForOrder()` **silently skips** items with a null productId
  (`if (item.getProductId() == null) continue;`), so **no stock was deducted**.
- The pre-commit stock check (`/validate`) also skips null-productId items, so **nothing warned**.

**Highest risk: ECOMMERCE rows** — Shopee/TikTok/Lazada exports carry the marketplace SKU,
which rarely equals the internal `product_code`. The gap is not ecommerce-specific in code;
any source with a mismatched/typo'd/renamed/deleted code hits it.

**Data tell for already-affected orders:** imported order line items with
`product_name IS NULL` but a non-zero total.

---

## 2. What the fix does

Two layers, both in `rrbm-backend/.../ImportController.java`:

1. **Parse-time (primary):** in both parse paths (`uploadSales` inline parser and the shared
   `parseSalesCsv` used by `/upload/combined`), a non-blank Product Code that matches no
   product is now a **hard `needsFix` error** — it can never reach commit unresolved.
2. **Commit-time (defense-in-depth):** the commit loop throws (→ lands in the `errors` list,
   order NOT created) if any built line item still has a null productId. Protects in-flight
   sessions parsed before the fix was deployed.

Test: `rrbm-backend/.../ImportProductCodeGapTest.java` — asserts an ECOMMERCE row with an
unmatched SKU goes to `needsFix` (validCount 0), and a matched code stays valid.

Files changed:
- `rrbm-backend/src/main/java/rrbm_backend/ImportController.java`
- `rrbm-backend/src/test/java/rrbm_backend/ImportProductCodeGapTest.java` (new)

---

## 3. PRE-FLIGHT CHECKS (do these before deploying)

### 3.1 Confirm you are on the right code
```bash
git fetch --all
git checkout hotfix/import-product-code-inventory-gap
git log --oneline -1          # confirm the hotfix commit is HEAD
git diff --stat main...HEAD   # should show ONLY ImportController.java + ImportProductCodeGapTest.java
```
If the diff shows unrelated files, STOP — the branch is contaminated.

### 3.2 Flyway checksum check (KNOWN LANDMINE)
The dev DB has historically shown a **V5 checksum mismatch** that aborts Spring Boot startup:
```
Migration checksum mismatch for migration version 5
```
Before deploying, confirm the **live** DB does not have this drift:
```bash
# On the live host, inspect flyway history vs the committed migration files
psql -d rrbm_db -c "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank;"
```
- This hotfix **adds no migrations** — it must not require any schema change.
- If the live DB validates cleanly, proceed.
- If (and only if) the live DB shows a mismatch, do NOT blindly `flyway repair`. Investigate
  which branch last migrated it and confirm the data is intact first. Escalate to the owner.

### 3.3 Backup
Take a DB backup (or confirm the automated backup ran today) before restart:
```bash
pg_dump rrbm_db > rrbm_db_backup_$(date +%Y%m%d_%H%M).sql
```

---

## 4. DEPLOY

Follow the project's normal deploy for the backend (build jar / restart service). Because the
patch is Java-only with no migration, this is a standard rebuild + restart:
```bash
cd rrbm-backend
./mvnw -q -DskipTests package    # or the project's usual build step
# restart the backend service using the project's normal mechanism
```
> Skip tests during the live build only if the shared-DB Flyway drift (3.2) would otherwise
> block them. Prefer running the single test in a clean env (see §6).

---

## 5. POST-DEPLOY VERIFICATION

1. Import a **1-row sales CSV with a deliberately wrong Product Code** (e.g. `NO-SUCH-SKU-999`,
   source ECOMMERCE / SHOPEE) via the UI or `/upload/sales`.
   - Expect: the row appears under **Needs Fix** with an error mentioning the code —
     `validCount` = 0. It must NOT be committable.
2. Import a **1-row CSV with a valid Product Code**.
   - Expect: it parses as valid and, on commit, **stock decreases** by the ordered qty
     (check the product's warehouse stock and the `inventory_movements` ORDER_OUT row).

---

## 6. RUNNING THE AUTOMATED TEST (clean env only)

Tests in this repo run against the shared `rrbm_db`, and the `*IT` suite **wipes**
products/orders/transactions. Do **not** run the full suite against a live/dev DB.
Run only the regression test, ideally against an isolated DB:
```bash
cd rrbm-backend
./mvnw -Dtest=ImportProductCodeGapTest test
```
If it fails at ApplicationContext load with the V5 checksum error, that is the environment
drift from §3.2 — not a code failure.

---

## 7. ROLLBACK

No schema change, so rollback = redeploy the previous backend build:
```bash
git checkout main        # or the previously deployed tag/commit
# rebuild + restart
```
No data migration to reverse. Orders committed before the fix that already skipped inventory
are a **separate remediation** (find line items with `product_name IS NULL` and non-zero
total; correct stock manually) — this hotfix only prevents NEW occurrences.
