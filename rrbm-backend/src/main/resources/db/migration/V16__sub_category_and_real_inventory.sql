-- V16__sub_category_and_real_inventory.sql
-- 1. Add sub_category column to products
-- 2. Remove mock/seed data — order_items and inventory_movements both hold
--    non-cascading FK references to products, so they must be cleared first
-- 3. Insert 108 real RRBM products from company inventory CSV
-- NOTE: "Santan" warehouse column from CSV mapped to stock_wh3
--       (products stored at Santan location have wh1/wh2 = 0)

-- ─────────────────────────────────────────────────────────────────────────
-- 1. Schema change
-- ─────────────────────────────────────────────────────────────────────────
ALTER TABLE products ADD COLUMN IF NOT EXISTS sub_category VARCHAR(100);

-- ─────────────────────────────────────────────────────────────────────────
-- 2. Wipe mock data and reset sequence
--    Full FK dependency chain (must delete in this order):
--      transactions.order_id     → orders(id)     [no CASCADE]
--      order_items.order_id      → orders(id)     [CASCADE — auto-handled]
--      order_items.product_id    → products(id)   [no CASCADE]
--      inventory_movements.product_id → products(id) [no CASCADE, NOT NULL]
-- ─────────────────────────────────────────────────────────────────────────
DELETE FROM transactions;
DELETE FROM inventory_movements;
DELETE FROM order_items;
DELETE FROM orders;
DELETE FROM products;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_sequences
    WHERE schemaname = 'public' AND sequencename = 'products_id_seq'
  ) THEN
    PERFORM setval('products_id_seq', 1, false);
  END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────
-- 3. Real inventory
-- Columns: product_code, name, category, sub_category, selling_tag,
--          unit_price, unit_cost, threshold_critical, threshold_low,
--          stock_wh1, stock_wh2, stock_wh3, active
-- ─────────────────────────────────────────────────────────────────────────
INSERT INTO products
  (product_code, name, category, sub_category, selling_tag,
   unit_price, unit_cost, threshold_critical, threshold_low,
   stock_wh1, stock_wh2, stock_wh3, active)
VALUES

-- ── PIZZA BOX — Plain Pizza Box ──────────────────────────────────────────
('PPB006','(6) Plain Pizza Box',         'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,3050,0,0,true),
('PPB008','(8) Plain Pizza Box',         'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,3125,0,0,true),
('PPB010','(10) Plain Pizza Box',        'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,9950,0,0,true),
('PPB011','(11) Plain Pizza Box',        'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,5525,0,0,true),
('PPB012','(12) Plain Pizza Box',        'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,2050,0,0,true),
('PPB014','(14) Plain Pizza Box',        'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,1625,0,0,true),
('PPB016','(16) Plain Pizza Box',        'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PPB018','(18) Plain Pizza Box',        'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PPBW10','(10) Plain White Pizza Box',  'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,2125,0,0,true),
('PPBW12','(12) Plain White Pizza Box',  'Pizza Box','Plain Pizza Box','SELLING',0,0,0,0,2875,0,0,true),

-- ── PIZZA BOX — Generic Pizza Box ────────────────────────────────────────
('PBG110','Hot Fresh Pizza (10)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBG111','Hot Fresh Pizza (11)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,2525,0,0,true),
('PBG112','Hot Fresh Pizza (12)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,3500,0,0,true),
('PBG114','Hot Fresh Pizza (14)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBG210','Hot and Delicious (10)',       'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,1250,0,0,true),
('PBG211','Hot and Delicious (11)',       'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,850,0,0,true),
('PBG212','Hot and Delicious (12)',       'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBG308','Fresh and Tasty (8)',          'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,225,0,0,true),
('PBG310','Fresh and Tasty (10)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,11750,0,0,true),
('PBG311','Fresh and Tasty (11)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,775,0,0,true),
('PBG312','Fresh and Tasty (12)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,2775,0,0,true),
('PBG314','Fresh and Tasty (14)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,225,0,0,true),
('PBG316','Fresh and Tasty (16)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,575,0,0,true),
('PBG318','Fresh and Tasty (18)',         'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,1450,0,0,true),
('PBG410','Care for a Slice (10)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,10450,0,0,true),
('PBG411','Care for a Slice (11)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,4650,0,0,true),
('PBG412','Care for a Slice (12)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,6650,0,0,true),
('PBG510','Yummy Oven Pizza (10)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,8300,0,0,true),
('PBG511','Yummy Oven Pizza (11)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,3000,0,0,true),
('PBG512','Yummy Oven Pizza (12)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBG610','It''s Pizza Time (10)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,2900,0,0,true),
('PBG611','It''s Pizza Time (11)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,3700,0,0,true),
('PBG612','It''s Pizza Time (12)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,575,0,0,true),
('PBG614','It''s Pizza Time (14)',        'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,1825,0,0,true),
('PBG710','Making a Difference (10)',     'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,8550,0,0,true),
('PBG711','Making a Difference (11)',     'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,3650,0,0,true),
('PBG712','Making a Difference (12)',     'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBG714','Making a Difference (14)',     'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,225,0,0,true),
('PBG808','Super Delicious Pizza (8)',    'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,825,0,0,true),
('PBG810','Super Delicious Pizza (10)',   'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,3950,0,0,true),
('PBG811','Super Delicious Pizza (11)',   'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,3600,0,0,true),
('PBG812','Super Delicious Pizza (12)',   'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,4025,0,0,true),
('PBG910','Taste of Greatness (10)',      'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,3775,0,0,true),
('PBG911','Taste of Greatness (11)',      'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBG912','Taste of Greatness (12)',      'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,4450,0,0,true),
('PBGH01','Holiday Pizza Box (Old)(14)', 'Pizza Box','Generic Pizza Box','SELLING',0,0,0,0,6075,0,0,true),

-- ── PIZZA BOX — Claycoat Pizza Box ───────────────────────────────────────
('PBC110','Black Clay Coat Pizza Box (10)', 'Pizza Box','Claycoat Pizza Box','SELLING',0,0,0,0,3250,0,0,true),
('PBC111','Black Clay Coat Pizza Box (11)', 'Pizza Box','Claycoat Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBC112','Black Clay Coat Pizza Box (12)', 'Pizza Box','Claycoat Pizza Box','SELLING',0,0,0,0,11800,0,0,true),
('PBC210','Red Clay Coat Pizza Box (10)',   'Pizza Box','Claycoat Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBC211','Red Clay Coat Pizza Box (11)',   'Pizza Box','Claycoat Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBC212','Red Clay Coat Pizza Box (12)',   'Pizza Box','Claycoat Pizza Box','SELLING',0,0,0,0,6900,0,0,true),
('PBC310','Green Clay Coat Pizza Box (10)', 'Pizza Box','Claycoat Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBC311','Green Clay Coat Pizza Box (11)', 'Pizza Box','Claycoat Pizza Box','SELLING',0,0,0,0,0,0,0,true),
('PBC312','Green Clay Coat Pizza Box (12)', 'Pizza Box','Claycoat Pizza Box','SELLING',0,0,0,0,8850,0,0,true),

-- ── RSC BOXES ────────────────────────────────────────────────────────────
('SBP00A','(A) RSC - 8 x 4.72 x 3.54 inches',      'RSC',NULL,'SELLING',0,0,0,0,1519,0,0,true),
('SBP00B','(B) RSC - 8.86 x 5.51 x 3.94 inches',   'RSC',NULL,'SELLING',0,0,0,0,950,0,0,true),
('SBP00C','(C) RSC - 13.58 x 7 x 3.94 inches',     'RSC',NULL,'SELLING',0,0,0,0,0,0,0,true),
('SBP00D','(D) RSC - 13.58 x 13.58 x 9.54 inches', 'RSC',NULL,'SELLING',0,0,0,0,1023,0,0,true),
('SBP00E','(E) RSC - 20 x 10 x 12 inches',         'RSC',NULL,'SELLING',0,0,0,0,251,0,0,true),
('SBP00F','(F) RSC - 20 x 18 x 18 inches',         'RSC',NULL,'SELLING',0,0,0,0,25,0,0,true),
('SBP00G','(G) RSC - 20 x 20 x 20 inches',         'RSC',NULL,'SELLING',0,0,0,0,91,0,0,true),
('SBJTXS','(XS) J&T Extra Small',                   'RSC',NULL,'SELLING',0,0,0,0,1765,0,0,true),
('SBJT0S','(S) J&T Small',                          'RSC',NULL,'SELLING',0,0,0,0,10,0,0,true),
('SBJT0M','(M) J&T Medium',                         'RSC',NULL,'SELLING',0,0,0,0,0,0,0,true),
('SBJT0L','(L) J&T Large',                          'RSC',NULL,'SELLING',0,0,0,0,196,0,0,true),

-- ── DIE-CUT PACKAGING BOXES — Mailer Box ─────────────────────────────────
('DBPMB1','(MB1) Mailer Box T0',  'Die-Cut Packaging Boxes','Mailer Box','SELLING',0,0,0,0,3325,0,0,true),
('DBPMB2','(MB2) Mailer Box T1',  'Die-Cut Packaging Boxes','Mailer Box','SELLING',0,0,0,0,275,0,0,true),
('DBPMB3','(MB3) Mailer Box T2',  'Die-Cut Packaging Boxes','Mailer Box','SELLING',0,0,0,0,2000,0,0,true),
('DBPMB4','(MB4) Mailer Box T13', 'Die-Cut Packaging Boxes','Mailer Box','SELLING',0,0,0,0,2575,0,0,true),
('DBPMB5','(MB5) Mailer Box T5',  'Die-Cut Packaging Boxes','Mailer Box','SELLING',0,0,0,0,1775,0,0,true),
('DBPMB6','(MB6) Mailer Box T10', 'Die-Cut Packaging Boxes','Mailer Box','SELLING',0,0,0,0,2225,0,0,true),

-- ── DIE-CUT PACKAGING BOXES — Flower Box (stock at Santan → wh3) ─────────
('DBFB01','Flower Box - 22 x 10 x 8 inches', 'Die-Cut Packaging Boxes','Flower Box','SELLING',0,0,0,0,0,0,1250,true),

-- ── OFFSET PACKAGING BOXES — Pastry Box ──────────────────────────────────
('OFPBVS','(VS) Violet Small - 6.8 x 5 x 2 Inches', 'Offset Packaging Boxes','Pastry Box','SELLING',0,0,0,0,8450,0,0,true),
('OFPBVB','(VB) Violet Big - 9 x 9 x 2 inches',     'Offset Packaging Boxes','Pastry Box','SELLING',0,0,0,0,8475,0,0,true),
('OFPBBS','(BS) Brown Small - 6.8 x 5 x 2 Inches',  'Offset Packaging Boxes','Pastry Box','SELLING',0,0,0,0,9750,0,0,true),
('OFPBBB','(BB) Brown Big - 9 x 9 x 2 inches',      'Offset Packaging Boxes','Pastry Box','SELLING',0,0,0,0,9300,0,0,true),

-- ── DIE-CUT PACKAGING BOXES — Party Box ──────────────────────────────────
('PB4S18','(18) Sarap na Binabalik Balikan 4 in 1 Party Box (SNBB)', 'Die-Cut Packaging Boxes','Party Box','SELLING',0,0,0,0,0,0,0,true),
('PB4U18','(18) Unli Sarap 4 in 1 Party Box',     'Die-Cut Packaging Boxes','Party Box','SELLING',0,0,0,0,450,0,0,true),
('PB4U20','(20) Unli Sarap 4 in 1 Party Box',     'Die-Cut Packaging Boxes','Party Box','SELLING',0,0,0,0,500,0,0,true),
('PB4D18','(18) Delicious Feast 4 in 1 Party Box','Die-Cut Packaging Boxes','Party Box','SELLING',0,0,0,0,950,0,0,true),
('PBIT08','(8) Inner Tray - Party Box',            'Die-Cut Packaging Boxes','Party Box','SELLING',0,0,0,0,26525,0,0,true),
('PBIT10','(10) Inner Tray - Party Box',           'Die-Cut Packaging Boxes','Party Box','SELLING',0,0,0,0,15025,0,0,true),

-- ── DIE-CUT PACKAGING BOXES — Roasted Food Boxes ─────────────────────────
('LBR00S','(S) Lechon Belly Box - 9 x 6 x 5 inches',  'Die-Cut Packaging Boxes','Roasted Food Boxes','SELLING',0,0,0,0,1150,0,0,true),
('LBR00M','(M) Lechon Belly Box - 13 x 8 x 6 inches', 'Die-Cut Packaging Boxes','Roasted Food Boxes','SELLING',0,0,0,0,900,0,0,true),
('LBR00L','(L) Lechon Belly Box - 16 x 8 x 7 inches', 'Die-Cut Packaging Boxes','Roasted Food Boxes','SELLING',0,0,0,0,1300,0,0,true),
('CCLT01','Conchinillio Box (Top)',    'Die-Cut Packaging Boxes','Roasted Food Boxes','SELLING',0,0,0,0,164,0,0,true),
('CCLB01','Conchinillio Box (Bottom)','Die-Cut Packaging Boxes','Roasted Food Boxes','SELLING',0,0,0,0,164,0,0,true),
('LHNB01','Lechon Box',               'Die-Cut Packaging Boxes','Roasted Food Boxes','SELLING',0,0,0,0,0,0,0,true),

-- ── DIE-CUT PACKAGING BOXES — Bilao Boxes (Santan → wh3) ─────────────────
('BB010T','(10) Bilao Box - Top',    'Die-Cut Packaging Boxes','Bilao Boxes','SELLING',0,0,0,0,0,0,2325,true),
('BB010B','(10) Bilao Box - Bottom', 'Die-Cut Packaging Boxes','Bilao Boxes','SELLING',0,0,0,0,0,0,2325,true),
('BB012T','(12) Bilao Box - Top',    'Die-Cut Packaging Boxes','Bilao Boxes','SELLING',0,0,0,0,0,0,2425,true),
('BB012B','(12) Bilao Box - Bottom', 'Die-Cut Packaging Boxes','Bilao Boxes','SELLING',0,0,0,0,0,0,2425,true),

-- ── PIZZA SUPPLIES (Santan → wh3) ────────────────────────────────────────
('TRP001','Pizza Tripod',   'Pizza Supplies',NULL,'SELLING',0,0,0,0,0,0,210000,true),
('WXP010','(10) Wax Paper', 'Pizza Supplies',NULL,'SELLING',0,0,0,0,0,0,0,true),
('WXP011','(11) Wax Paper', 'Pizza Supplies',NULL,'SELLING',0,0,0,0,0,0,0,true),
('WXP012','(12) Wax Paper', 'Pizza Supplies',NULL,'SELLING',0,0,0,0,0,0,0,true),
('WXP014','(14) Wax Paper', 'Pizza Supplies',NULL,'SELLING',0,0,0,0,0,0,0,true),

-- ── PACKAGING SUPPLIES — Tapes ────────────────────────────────────────────
('SPTC50','Packaging Tape (Clear) 2M x 50',   'Packaging Supplies','Tapes','SELLING',0,0,0,0,142,0,0,true),
('SPTC75','Packaging Tape (Clear) 2M x 75',   'Packaging Supplies','Tapes','SELLING',0,0,0,0,0,0,0,true),
('SPTC1H','Packaging Tape (Clear) 2M x 100',  'Packaging Supplies','Tapes','SELLING',0,0,0,0,0,0,0,true),
('SPTF50','Packaging Tape (Fragile) 2M x 50', 'Packaging Supplies','Tapes','SELLING',0,0,0,0,138,0,0,true),
('SPTF75','Packaging Tape (Fragile) 2M x 75', 'Packaging Supplies','Tapes','SELLING',0,0,0,0,0,0,0,true),
('SPTF1H','Packaging Tape (Fragile) 2M x 100','Packaging Supplies','Tapes','SELLING',0,0,0,0,216,0,0,true),

-- ── PACKAGING SUPPLIES — Wrappers ────────────────────────────────────────
('SBW00H','Bubble Wrap Clear (Half) 20M x 100', 'Packaging Supplies','Wrappers','SELLING',0,0,0,0,3,0,0,true),
('SBW00W','Bubble Wrap Clear (Whole) 40M x 100','Packaging Supplies','Wrappers','SELLING',0,0,0,0,3,0,0,true),
('SSF250','Stretch Film 20M x 250',             'Packaging Supplies','Wrappers','SELLING',0,0,0,0,0,0,0,true),
('SSF500','Stretch Film 20M x 500',             'Packaging Supplies','Wrappers','SELLING',0,0,0,0,2,0,0,true);
