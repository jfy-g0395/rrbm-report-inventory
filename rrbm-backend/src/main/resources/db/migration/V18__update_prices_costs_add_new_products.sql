-- V18__update_prices_costs_add_new_products.sql
-- Syncs inventory data from the updated company CSV (May 23, 2026).
--
-- Changes vs V16:
--   1. UPDATE  — unit_price and unit_cost for all 108 existing products
--                (V16 seeded all prices/costs as 0)
--   2. INSERT  — 2 new Party Box products not in V16:
--                PB4P18 (18) Plain 4 in 1 Party Box
--                PB4P20 (20) Plain 4 in 1 Party Box
--   3. DEACTIVATE — any product in DB not in the CSV (currently a no-op;
--                   all V16 products are present in the updated CSV)
--
-- Stock columns (stock_wh1/2/3) are intentionally NOT touched for existing
-- products — those are tracked live by the order system.
-- ─────────────────────────────────────────────────────────────────────────

-- ═════════════════════════════════════════════════════════════════════════
-- 1.  BULK UPDATE — unit_price + unit_cost for all existing products
-- ═════════════════════════════════════════════════════════════════════════
UPDATE products AS p
SET
    unit_price = v.unit_price,
    unit_cost  = v.unit_cost
FROM (VALUES

    -- ── Pizza Box — Plain Pizza Box ───────────────────────────────────
    ('PPB006'::VARCHAR,  7.50::NUMERIC,  5.25::NUMERIC),
    ('PPB008',           8.40,           5.75),
    ('PPB010',           9.98,           7.30),
    ('PPB011',          11.03,           8.00),
    ('PPB012',          12.08,           8.50),
    ('PPB014',          15.75,          11.00),
    ('PPB016',          17.85,          14.10),
    ('PPB018',          19.95,          16.50),
    ('PPBW10',          10.50,           8.35),
    ('PPBW12',          12.60,           9.50),

    -- ── Pizza Box — Generic Pizza Box ─────────────────────────────────
    ('PBG110',          10.50,           7.50),
    ('PBG111',          11.55,           8.25),
    ('PBG112',          12.60,           9.00),
    ('PBG114',          16.28,          11.45),
    ('PBG210',          10.50,           7.50),
    ('PBG211',          11.55,           8.25),
    ('PBG212',          12.60,           9.00),
    ('PBG308',           9.50,           6.25),
    ('PBG310',          10.50,           7.50),
    ('PBG311',          11.55,           8.25),
    ('PBG312',          12.60,           9.00),
    ('PBG314',          16.28,          11.45),
    ('PBG316',          18.40,          13.75),
    ('PBG318',          22.00,          17.00),
    ('PBG410',          10.50,           7.50),
    ('PBG411',          11.55,           8.25),
    ('PBG412',          12.60,           9.00),
    ('PBG510',          10.50,           7.50),
    ('PBG511',          11.55,           8.25),
    ('PBG512',          12.60,           9.00),
    ('PBG610',          10.50,           7.50),
    ('PBG611',          11.55,           8.25),
    ('PBG612',          12.60,           9.00),
    ('PBG614',          16.28,          11.45),
    ('PBG710',          10.50,           7.50),
    ('PBG711',          11.55,           8.25),
    ('PBG712',          12.60,           9.00),
    ('PBG714',          16.28,          11.45),
    ('PBG808',           9.50,           6.25),
    ('PBG810',          10.50,           7.50),
    ('PBG811',          11.55,           8.25),
    ('PBG812',          12.60,           9.00),
    ('PBG910',          10.50,           7.50),
    ('PBG911',          11.55,           8.25),
    ('PBG912',          12.60,           9.00),
    ('PBGH01',          16.28,          11.45),

    -- ── Pizza Box — Claycoat Pizza Box ────────────────────────────────
    ('PBC110',           9.98,           8.00),
    ('PBC111',          11.50,           8.60),
    ('PBC112',          12.08,           9.50),
    ('PBC210',           9.98,           8.00),
    ('PBC211',          11.50,           8.60),
    ('PBC212',          12.08,           9.50),
    ('PBC310',           9.98,           8.00),
    ('PBC311',          11.50,           8.60),
    ('PBC312',          12.08,           9.50),

    -- ── RSC Boxes ─────────────────────────────────────────────────────
    ('SBP00A',          12.60,  NULL::NUMERIC),
    ('SBP00B',          13.65,  NULL),
    ('SBP00C',          14.70,  NULL),
    ('SBP00D',          33.60,  25.00),
    ('SBP00E',          34.65,  26.00),
    ('SBP00F',          78.75,  63.00),
    ('SBP00G',         110.00,  NULL),
    ('SBJTXS',          15.75,  13.00),
    ('SBJT0S',          23.10,  16.00),
    ('SBJT0M',          32.55,  21.00),
    ('SBJT0L',          59.85,  35.00),

    -- ── Die-Cut — Mailer Box ──────────────────────────────────────────
    ('DBPMB1',           5.78,   4.51),
    ('DBPMB2',           7.35,   5.45),
    ('DBPMB3',           8.40,   5.79),
    ('DBPMB4',          12.34,   9.40),
    ('DBPMB5',          12.60,   9.85),
    ('DBPMB6',          22.05,   3.56),

    -- ── Die-Cut — Flower Box ──────────────────────────────────────────
    ('DBFB01',          47.25,  35.50),

    -- ── Offset Packaging Boxes — Pastry Box ───────────────────────────
    ('OFPBVS',           8.00,  NULL),
    ('OFPBVB',          10.50,  NULL),
    ('OFPBBS',           8.00,  NULL),
    ('OFPBBB',          10.50,  NULL),

    -- ── Die-Cut — Party Box (existing 6) ─────────────────────────────
    ('PB4S18',          38.85,  27.00),
    ('PB4U18',          38.85,  27.00),
    ('PB4U20',          42.00,  29.00),
    ('PB4D18',          38.85,  27.00),
    ('PBIT08',           0.00,   0.00),
    ('PBIT10',           0.00,   0.00),

    -- ── Die-Cut — Roasted Food Boxes ─────────────────────────────────
    ('LBR00S',          18.90,  11.00),
    ('LBR00M',          24.15,  16.50),
    ('LBR00L',          26.25,  23.50),
    ('CCLT01',          68.25,  34.00),
    ('CCLB01',           0.00,   0.00),
    ('LHNB01',          40.95,  NULL),

    -- ── Die-Cut — Bilao Boxes ─────────────────────────────────────────
    ('BB010T',          11.55,  NULL),
    ('BB010B',           0.00,  NULL),
    ('BB012T',          15.75,  NULL),
    ('BB012B',          15.75,  NULL),

    -- ── Pizza Supplies ────────────────────────────────────────────────
    ('TRP001',           1.26,  NULL),
    ('WXP010',           0.95,  NULL),
    ('WXP011',           1.00,  NULL),
    ('WXP012',           1.05,  NULL),
    ('WXP014',           1.26,  NULL),

    -- ── Packaging Supplies — Tapes ────────────────────────────────────
    ('SPTC50',          25.20,  NULL),
    ('SPTC75',           0.00,  NULL),
    ('SPTC1H',          36.75,  NULL),
    ('SPTF50',          42.00,  NULL),
    ('SPTF75',           0.00,  NULL),
    ('SPTF1H',          70.35,  NULL),

    -- ── Packaging Supplies — Wrappers ─────────────────────────────────
    ('SBW00H',         367.50,  NULL),
    ('SBW00W',         735.00,  NULL),
    ('SSF250',         367.50,  NULL),
    ('SSF500',         525.00,  NULL)

) AS v(product_code, unit_price, unit_cost)
WHERE p.product_code = v.product_code;


-- ═════════════════════════════════════════════════════════════════════════
-- 2.  INSERT — 2 new Party Box products (not present in V16)
-- ═════════════════════════════════════════════════════════════════════════
INSERT INTO products
    (product_code, name, category, sub_category, selling_tag,
     unit_price, unit_cost, threshold_critical, threshold_low,
     stock_wh1, stock_wh2, stock_wh3, active)
SELECT
    'PB4P18',
    '(18) Plain 4 in 1 Party Box',
    'Die-Cut Packaging Boxes',
    'Party Box',
    'SELLING',
    37.80, 26.00, 0, 0, 0, 0, 0, true
WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_code = 'PB4P18');

INSERT INTO products
    (product_code, name, category, sub_category, selling_tag,
     unit_price, unit_cost, threshold_critical, threshold_low,
     stock_wh1, stock_wh2, stock_wh3, active)
SELECT
    'PB4P20',
    '(20) Plain 4 in 1 Party Box',
    'Die-Cut Packaging Boxes',
    'Party Box',
    'SELLING',
    39.90, 28.00, 0, 0, 0, 0, 0, true
WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_code = 'PB4P20');


-- ═════════════════════════════════════════════════════════════════════════
-- 3.  SOFT-DEACTIVATE — products in DB that are no longer in the CSV
--     (Currently a no-op: all V16 products exist in the updated CSV.
--      This guard runs safely on each re-apply.)
-- ═════════════════════════════════════════════════════════════════════════
UPDATE products
SET active = false
WHERE product_code NOT IN (
    -- Pizza Box
    'PPB006','PPB008','PPB010','PPB011','PPB012','PPB014','PPB016','PPB018',
    'PPBW10','PPBW12',
    'PBG110','PBG111','PBG112','PBG114',
    'PBG210','PBG211','PBG212',
    'PBG308','PBG310','PBG311','PBG312','PBG314','PBG316','PBG318',
    'PBG410','PBG411','PBG412',
    'PBG510','PBG511','PBG512',
    'PBG610','PBG611','PBG612','PBG614',
    'PBG710','PBG711','PBG712','PBG714',
    'PBG808','PBG810','PBG811','PBG812',
    'PBG910','PBG911','PBG912','PBGH01',
    'PBC110','PBC111','PBC112',
    'PBC210','PBC211','PBC212',
    'PBC310','PBC311','PBC312',
    -- RSC
    'SBP00A','SBP00B','SBP00C','SBP00D','SBP00E','SBP00F','SBP00G',
    'SBJTXS','SBJT0S','SBJT0M','SBJT0L',
    -- Die-Cut
    'DBPMB1','DBPMB2','DBPMB3','DBPMB4','DBPMB5','DBPMB6',
    'DBFB01',
    -- Offset
    'OFPBVS','OFPBVB','OFPBBS','OFPBBB',
    -- Party Box (including 2 new)
    'PB4P18','PB4P20','PB4S18','PB4U18','PB4U20','PB4D18','PBIT08','PBIT10',
    -- Roasted Food
    'LBR00S','LBR00M','LBR00L','CCLT01','CCLB01','LHNB01',
    -- Bilao
    'BB010T','BB010B','BB012T','BB012B',
    -- Pizza Supplies
    'TRP001','WXP010','WXP011','WXP012','WXP014',
    -- Packaging Supplies
    'SPTC50','SPTC75','SPTC1H','SPTF50','SPTF75','SPTF1H',
    'SBW00H','SBW00W','SSF250','SSF500'
)
AND active = true;
