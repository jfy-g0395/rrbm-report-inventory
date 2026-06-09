-- ================================================================
-- V19: QA Test Seed — realistic data for full-stack feature testing
--
-- NOTE: The spec referenced this as V18 but V18 is already taken by
--       the inventory price/cost sync. This is V19.
--
-- All test records are tagged for easy cleanup:
--   orders/expenses : customer_name / admin_name prefixed 'TEST_'
--   daily_reports   : notes = 'TEST_SEED'
--   delivery        : receipt_number = '888001'
--   payables        : supplier_name LIKE 'TEST%'
--   transactions    : notes = 'Test seed sale'
--
-- SCHEMA FIXES vs spec:
--   • daily_reports has no is_closed column; closed_by is BIGINT (user FK)
--   • order_items has no unique constraint — no ON CONFLICT possible
--   • delivery_log uses encoded_by_name (not encoded_by) + requires report_date
--   • source 'ONLINE' is invalid — using 'ECOMMERCE' (valid in V10 constraint)
--   • Products looked up by product_code, not name ILIKE
-- ================================================================

-- ──────────────────────────────────────────────────────────────────
-- STEP 1: 35 days × 4 orders/day (5 weeks of history)
-- ──────────────────────────────────────────────────────────────────
DO $$
DECLARE
  v_date        DATE;
  v_date_str    TEXT;
  v_order_id    TEXT;
  v_seq         INTEGER := 900001;   -- high enough to avoid real-order conflicts
  v_product_id  BIGINT;
  v_product2_id BIGINT;
  v_user_id     BIGINT;
  v_day_offset  INTEGER;
  v_qty1        INTEGER;
  v_qty2        INTEGER;
  v_price1      NUMERIC(12,2) := 10.50;   -- PPBW10 unit price
  v_price2      NUMERIC(12,2) := 12.60;   -- PPBW12 unit price
  v_total       NUMERIC(12,2);
  v_payment     TEXT;
  v_source      TEXT;
  v_type        TEXT;
BEGIN

  -- Lookup by product_code (reliable; name ILIKE fails on this dataset)
  SELECT id INTO v_product_id  FROM products WHERE product_code = 'PPBW10';
  SELECT id INTO v_product2_id FROM products WHERE product_code = 'PPBW12';

  -- Fallback: first two active products
  IF v_product_id  IS NULL THEN SELECT id INTO v_product_id  FROM products WHERE active = true ORDER BY id LIMIT 1 OFFSET 0; END IF;
  IF v_product2_id IS NULL THEN SELECT id INTO v_product2_id FROM products WHERE active = true ORDER BY id LIMIT 1 OFFSET 1; END IF;

  -- Any super-admin for created_by FK
  SELECT id INTO v_user_id FROM users WHERE role = 'SUPER_ADMIN' ORDER BY id LIMIT 1;
  IF v_user_id IS NULL THEN SELECT id INTO v_user_id FROM users ORDER BY id LIMIT 1; END IF;

  FOR v_day_offset IN 1..35 LOOP
    v_date     := CURRENT_DATE - v_day_offset;
    v_date_str := TO_CHAR(v_date, 'DDMMYY');

    FOR i IN 1..4 LOOP

      v_seq      := v_seq + 1;
      v_order_id := v_date_str || '-' || LPAD(v_seq::TEXT, 6, '0');

      -- Rotate all 4 valid payment modes
      v_payment := CASE (i % 4)
        WHEN 0 THEN 'CASH'
        WHEN 1 THEN 'GCASH'
        WHEN 2 THEN 'COD'
        ELSE        'BANK_TRANSFER'
      END;

      -- Rotate valid source values (V10 constraint)
      -- ONLINE is NOT a valid source; ECOMMERCE is.
      v_source := CASE (i % 3)
        WHEN 0 THEN 'WALK_IN'
        WHEN 1 THEN 'ECOMMERCE'
        ELSE        'RESELLER'
      END;

      -- order_type has no CHECK constraint
      v_type := CASE (i % 3)
        WHEN 0 THEN 'STANDARD'
        WHEN 1 THEN 'PICK_UP'
        ELSE        'COD'
      END;

      -- Varying quantities give the charts an interesting non-flat shape
      v_qty1 := 50 + (v_day_offset * 3  % 200);
      v_qty2 := 30 + (v_day_offset * 2  % 100);
      v_total := (v_qty1 * v_price1) + (v_qty2 * v_price2);

      -- Insert order (idempotent — safe if seed run twice)
      INSERT INTO orders (
        id, customer_name, source, payment_mode, order_type,
        status, total, created_at, created_by, address
      ) VALUES (
        v_order_id,
        'TEST_Customer_' || v_day_offset || '_' || i,
        v_source, v_payment, v_type,
        'ACTIVE',
        v_total,
        v_date::TIMESTAMP + (i * INTERVAL '2 hours'),
        v_user_id,
        'Test Address ' || i
      )
      ON CONFLICT (id) DO NOTHING;

      -- order_items has no unique constraint — guard with NOT EXISTS
      IF NOT EXISTS (SELECT 1 FROM order_items WHERE order_id = v_order_id) THEN

        INSERT INTO order_items (order_id, product_name, product_id, quantity, unit_price, subtotal)
        VALUES (v_order_id, '(10) Plain White Pizza Box', v_product_id,  v_qty1, v_price1, v_qty1 * v_price1);

        INSERT INTO order_items (order_id, product_name, product_id, quantity, unit_price, subtotal)
        VALUES (v_order_id, '(12) Plain White Pizza Box', v_product2_id, v_qty2, v_price2, v_qty2 * v_price2);

      END IF;

      -- SALE transaction (transaction_code is UNIQUE)
      INSERT INTO transactions (
        transaction_code, order_id, transaction_type, amount,
        reference_type, reference_id, notes,
        created_by, created_at, effective_date
      ) VALUES (
        'TEST-SALE-' || v_order_id,
        v_order_id,
        'SALE',
        v_total,
        'ORDER',
        v_order_id,
        'Test seed sale',
        v_user_id,
        v_date::TIMESTAMP + (i * INTERVAL '2 hours'),
        v_date
      )
      ON CONFLICT (transaction_code) DO NOTHING;

    END LOOP; -- i
  END LOOP;   -- v_day_offset

END $$;


-- ──────────────────────────────────────────────────────────────────
-- STEP 2: Special orders for refund / void testing
-- Both land on CURRENT_DATE - 3 (inside the past week for easy lookup)
-- ──────────────────────────────────────────────────────────────────
DO $$
DECLARE
  v_product_id  BIGINT;
  v_product2_id BIGINT;
  v_user_id     BIGINT;
  v_date        DATE := CURRENT_DATE - 3;
  v_date_str    TEXT := TO_CHAR(CURRENT_DATE - 3, 'DDMMYY');
BEGIN

  SELECT id INTO v_product_id  FROM products WHERE product_code = 'PPBW10';
  SELECT id INTO v_product2_id FROM products WHERE product_code = 'PPBW12';
  IF v_product_id  IS NULL THEN SELECT id INTO v_product_id  FROM products WHERE active = true ORDER BY id LIMIT 1 OFFSET 0; END IF;
  IF v_product2_id IS NULL THEN SELECT id INTO v_product2_id FROM products WHERE active = true ORDER BY id LIMIT 1 OFFSET 1; END IF;
  SELECT id INTO v_user_id FROM users WHERE role = 'SUPER_ADMIN' ORDER BY id LIMIT 1;
  IF v_user_id IS NULL THEN SELECT id INTO v_user_id FROM users ORDER BY id LIMIT 1; END IF;

  -- ── Void target: CANCELLED order (find it in Order History, click Void) ──
  INSERT INTO orders (
    id, customer_name, source, payment_mode, order_type, status, total,
    cancellation_reason, created_at, created_by
  ) VALUES (
    v_date_str || '-TEST01',
    'TEST_Void_Target_1',
    'WALK_IN', 'CASH', 'STANDARD',
    'CANCELLED',
    2100.00,
    'Test cancellation for void testing',
    v_date::TIMESTAMP + INTERVAL '9 hours',
    v_user_id
  )
  ON CONFLICT (id) DO NOTHING;

  IF NOT EXISTS (SELECT 1 FROM order_items WHERE order_id = v_date_str || '-TEST01') THEN
    INSERT INTO order_items (order_id, product_name, product_id, quantity, unit_price, subtotal)
    VALUES (v_date_str || '-TEST01', '(10) Plain White Pizza Box', v_product_id, 200, 10.50, 2100.00);
  END IF;

  -- ── Refund target: ACTIVE order (find it, click Refund, enter partial amount) ──
  -- Total: 100 × 10.50 + 67 × 12.60 = 1050.00 + 844.20 = 1894.20
  INSERT INTO orders (
    id, customer_name, source, payment_mode, order_type, status, total,
    created_at, created_by
  ) VALUES (
    v_date_str || '-TEST02',
    'TEST_Refund_Target_1',
    'ECOMMERCE', 'GCASH', 'STANDARD',
    'ACTIVE',
    1894.20,
    v_date::TIMESTAMP + INTERVAL '10 hours',
    v_user_id
  )
  ON CONFLICT (id) DO NOTHING;

  IF NOT EXISTS (SELECT 1 FROM order_items WHERE order_id = v_date_str || '-TEST02') THEN
    INSERT INTO order_items (order_id, product_name, product_id, quantity, unit_price, subtotal)
    VALUES (v_date_str || '-TEST02', '(10) Plain White Pizza Box', v_product_id,  100, 10.50, 1050.00);

    INSERT INTO order_items (order_id, product_name, product_id, quantity, unit_price, subtotal)
    VALUES (v_date_str || '-TEST02', '(12) Plain White Pizza Box', v_product2_id,  67, 12.60,  844.20);
  END IF;

  -- SALE transaction for the refund target (so the accounting summary reflects it)
  INSERT INTO transactions (
    transaction_code, order_id, transaction_type, amount,
    reference_type, reference_id, notes,
    created_by, created_at, effective_date
  ) VALUES (
    'TEST-SALE-' || v_date_str || '-TEST02',
    v_date_str || '-TEST02',
    'SALE',
    1894.20,
    'ORDER',
    v_date_str || '-TEST02',
    'Test seed sale',
    v_user_id,
    v_date::TIMESTAMP + INTERVAL '10 hours',
    v_date
  )
  ON CONFLICT (transaction_code) DO NOTHING;

END $$;


-- ──────────────────────────────────────────────────────────────────
-- STEP 3: Close daily reports for the past 35 days
-- (Drives the 7-day chart and Reports page)
-- daily_reports.closed_by is BIGINT (FK), not TEXT.
-- Use notes = 'TEST_SEED' as the cleanup tag instead.
-- ──────────────────────────────────────────────────────────────────
DO $$
DECLARE
  v_date    DATE;
  v_gross   NUMERIC(12,2);
  v_orders  INTEGER;
  v_day     INTEGER;
  v_user_id BIGINT;
BEGIN

  SELECT id INTO v_user_id FROM users WHERE role = 'SUPER_ADMIN' ORDER BY id LIMIT 1;
  IF v_user_id IS NULL THEN SELECT id INTO v_user_id FROM users ORDER BY id LIMIT 1; END IF;

  FOR v_day IN 1..35 LOOP
    v_date := CURRENT_DATE - v_day;

    -- Sum only the test orders we just inserted
    SELECT
      COALESCE(SUM(total), 0),
      COUNT(*)
    INTO v_gross, v_orders
    FROM orders
    WHERE DATE(created_at) = v_date
      AND status != 'CANCELLED'
      AND customer_name LIKE 'TEST_%';

    -- ON CONFLICT (report_date) skips dates that already have a closed report
    INSERT INTO daily_reports (
      report_date,
      total_revenue, total_orders,
      gross_sales, refunds_total, adjustments_total, net_sales,
      closed_at, closed_by,
      notes
    ) VALUES (
      v_date,
      v_gross, v_orders,
      v_gross, 0, 0, v_gross,
      v_date::TIMESTAMP + INTERVAL '23 hours',
      v_user_id,
      'TEST_SEED'           -- ← cleanup tag (closed_by is BIGINT, can't hold text)
    )
    ON CONFLICT (report_date) DO NOTHING;

  END LOOP;
END $$;


-- ──────────────────────────────────────────────────────────────────
-- STEP 4: Test expenses across the past 14 days
-- (Drives the Expense History date-range card)
-- ──────────────────────────────────────────────────────────────────
DO $$
DECLARE
  v_exp_id  BIGINT;
  v_user_id BIGINT;
  v_day     INTEGER;
  v_date    DATE;
  v_total   NUMERIC(12,2);
BEGIN

  SELECT id INTO v_user_id FROM users WHERE role = 'SUPER_ADMIN' ORDER BY id LIMIT 1;
  IF v_user_id IS NULL THEN SELECT id INTO v_user_id FROM users ORDER BY id LIMIT 1; END IF;

  FOR v_day IN 1..14 LOOP
    v_date  := CURRENT_DATE - v_day;
    v_total := 1200.00 + (v_day * 50);   -- escalating totals (₱1250 – ₱1900)

    INSERT INTO expenses (admin_id, admin_name, total_amount, date, created_at)
    VALUES (v_user_id, 'TEST_Admin', v_total, v_date, v_date::TIMESTAMP + INTERVAL '8 hours')
    RETURNING id INTO v_exp_id;

    INSERT INTO expense_items (expense_id, item_description, amount)
    VALUES
      (v_exp_id, 'Food Allowance',     500.00),
      (v_exp_id, 'Gas Allowance',      350.00),
      (v_exp_id, 'Delivery Allowance', v_total - 850.00);

  END LOOP;
END $$;


-- ──────────────────────────────────────────────────────────────────
-- STEP 5: Test delivery receipt + PENDING payable
-- delivery_log uses encoded_by_name (not encoded_by) and needs report_date.
-- Payable total = 480 received × ₱10.50 unit_cost = ₱5,040.00
-- ──────────────────────────────────────────────────────────────────
DO $$
DECLARE
  v_log_id     BIGINT;
  v_product_id BIGINT;
  v_user_id    BIGINT;
BEGIN

  -- Skip entirely if already seeded (receipt_number is UNIQUE)
  IF EXISTS (SELECT 1 FROM delivery_log WHERE receipt_number = '888001') THEN
    RETURN;
  END IF;

  SELECT id INTO v_product_id FROM products WHERE product_code = 'PPBW10';
  IF v_product_id IS NULL THEN SELECT id INTO v_product_id FROM products WHERE active = true ORDER BY id LIMIT 1; END IF;
  SELECT id INTO v_user_id FROM users WHERE role = 'SUPER_ADMIN' ORDER BY id LIMIT 1;
  IF v_user_id IS NULL THEN SELECT id INTO v_user_id FROM users ORDER BY id LIMIT 1; END IF;

  INSERT INTO delivery_log (
    receipt_number,
    received_by, verified_by, encoded_by_name,
    total_items, total_quantity,
    notes, report_date, created_at
  ) VALUES (
    '888001',
    'TEST_Receiver', 'TEST_Verifier', 'TEST_Encoder',
    1, 500,
    'Test delivery for QA — safe to delete',
    CURRENT_DATE - 2,
    (CURRENT_DATE - 2)::TIMESTAMP + INTERVAL '10 hours'
  )
  RETURNING id INTO v_log_id;

  INSERT INTO delivery_log_items (
    delivery_log_id, product_id, product_name, quantity,
    received_qty, rejected_qty, unit_cost
  ) VALUES (
    v_log_id, v_product_id, '(10) Plain White Pizza Box', 500,
    480, 20, 10.50     -- line_total (GENERATED) = 480 × 10.50 = ₱5,040.00
  );

  -- Matching PENDING payable
  INSERT INTO payables (
    delivery_log_id, receipt_number, supplier_name,
    total_amount, amount_paid, status,
    created_at, created_by
  ) VALUES (
    v_log_id, '888001', 'TEST Supplier Co.',
    5040.00, 0.00, 'PENDING',
    (CURRENT_DATE - 2)::TIMESTAMP + INTERVAL '10 hours',
    'TEST_Admin'
  );

END $$;
