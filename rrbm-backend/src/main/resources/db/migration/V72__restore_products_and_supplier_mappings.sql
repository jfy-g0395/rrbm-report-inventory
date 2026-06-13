-- V72: Restore 110 products from rrbm-inventory.csv + create Well-Pack supplier mappings
--
-- Part 1: Re-insert missing products (safe — WHERE NOT EXISTS on product_code)
-- Part 2: Insert "Well-Pack Container Corporation" supplier (if not exists)
-- Part 3: Bulk-insert supplier_product_mapping for the 83 products with an item code

-- ═══════════════════════════════════════════════════════════════════════
-- Part 1: Products
-- ═══════════════════════════════════════════════════════════════════════

INSERT INTO products
  (product_code, item_code, name, category, sub_category, description,
   unit_price, unit_cost, threshold_critical, threshold_low,
   stock_wh1, stock_wh2, stock_wh3, active)
SELECT * FROM (VALUES

-- ── PIZZA BOX — Plain Pizza Box ──────────────────────────────────────────
('PPB006','RBM-115','(6) Plain Pizza Box','Pizza Box','Plain Pizza Box','PLAIN PIZZA BOX  -  6 x 6  x 1.5 inches',7.5,3.25,0,0,3050,0,0,true),
('PPB008','RBM-009','(8) Plain Pizza Box','Pizza Box','Plain Pizza Box','PLAIN BROWN 8 x 8 x 1.5 inches',8.4,3.75,0,0,3125,0,0,true),
('PPB010','RBM-003-R2','(10) Plain Pizza Box','Pizza Box','Plain Pizza Box','PLAIN BROWN 10.5 x 10.5 x 1.5 inches',9.98,5.3,0,0,9950,0,0,true),
('PPB011','RBM-008','(11) Plain Pizza Box','Pizza Box','Plain Pizza Box','PLAIN BROWN 11.5 x 11.5 x 1.5 inches',11.03,6,0,0,5525,0,0,true),
('PPB012','RBM-002','(12) Plain Pizza Box','Pizza Box','Plain Pizza Box','PLAIN BROWN 12.5 x 12.5 x 1.5 inches',12.08,6.5,0,0,2050,0,0,true),
('PPB014','RBM-004-R1','(14) Plain Pizza Box','Pizza Box','Plain Pizza Box','PLAIN BROWN 14 x 14 x 1.5 inches',15.75,9,0,0,1625,0,0,true),
('PPB016','RBM-010','(16) Plain Pizza Box','Pizza Box','Plain Pizza Box','PLAIN BROWN 16 x 16 x 1.5 inches',17.85,12.1,0,0,1000,0,0,true),
('PPB018','RBM-038','(18) Plain Pizza Box','Pizza Box','Plain Pizza Box','PLAIN BROWN PIZZA BOX - 18 x 18 x 1.5"',19.95,14.5,0,0,1000,0,0,true),

-- ── PIZZA BOX — Plain White ─────────────────────────────────────────────
('PPBW10','RBM-052','(10) Plain White Pizza Box','Pizza Box','Plain Pizza Box','PLAIN WHITE 10.5 x 10.5 x 1.5 inches',10.5,6.35,0,0,2125,0,0,true),
('PPBW12','RBM-053','(12) Plain White Pizza Box','Pizza Box','Plain Pizza Box','PLAIN WHITE 12.5 x 12.5 x 1.5 inches',12.6,7.5,0,0,2875,0,0,true),

-- ── PIZZA BOX — Generic Pizza Box ───────────────────────────────────────
('PBG110','RBM-005','Hot Fresh Pizza (10)','Pizza Box','Generic Pizza Box','HOT FRESH 10.5 x 10.5 x 1.5 inches',10.5,5.5,0,0,1000,0,0,true),
('PBG111','RBM-011-R2','Hot Fresh Pizza (11)','Pizza Box','Generic Pizza Box','HOT FRESH PIZZA BOX 11.5 x 11.5 x 1.5 inches',11.55,6.25,0,0,2525,0,0,true),
('PBG112','RBM-006-R1','Hot Fresh Pizza (12)','Pizza Box','Generic Pizza Box','HOT FRESH 12.5 x 12.5 x 1.5 inches',12.6,7,0,0,3500,0,0,true),
('PBG114','RBM-007','Hot Fresh Pizza (14)','Pizza Box','Generic Pizza Box','HOT FRESH 14 x 14 x 1.5 inches',16.28,9.45,0,0,1000,0,0,true),
('PBG210','RBM-058-R1','Hot and Delicious (10)','Pizza Box','Generic Pizza Box','HOT & DELICIOUS 10.5 x 10.5 x 1.5 inches',10.5,5.5,0,0,1250,0,0,true),
('PBG211','RBM-059','Hot and Delicious (11)','Pizza Box','Generic Pizza Box','HOT & DELICIOUS 11.5 x 11.5 x 1.5 inches',11.55,6.25,0,0,850,0,0,true),
('PBG212','RBM-060-R1','Hot and Delicious (12)','Pizza Box','Generic Pizza Box','HOT & DELICIOUS 12.5 x 12.5 x 1.5 inches',12.6,7,0,0,1000,0,0,true),
('PBG308','RBM-104','Fresh and Tasty (8)','Pizza Box','Generic Pizza Box','FRESH & TASTY - 8 x 8 x 1.5 inches',9.5,4.25,0,0,225,0,0,true),
('PBG310','RBM-021-R2','Fresh and Tasty (10)','Pizza Box','Generic Pizza Box','FRESH & TASTY  10.5 x 10.5 x 1.5 inches',10.5,5.5,0,0,11750,0,0,true),
('PBG311','RBM-022-R2','Fresh and Tasty (11)','Pizza Box','Generic Pizza Box','FRESH & TASTY  11.5 x 11.5 x 1.5 inches',11.55,6.25,0,0,775,0,0,true),
('PBG312','RBM-023-R1','Fresh and Tasty (12)','Pizza Box','Generic Pizza Box','FRESH & TASTY -12.5 X 12.5 X 1.5"',12.6,7,0,0,2775,0,0,true),
('PBG314','RBM-024-R1','Fresh and Tasty (14)','Pizza Box','Generic Pizza Box','FRESH & TASTY 14 x 14 x 1.5 inches',16.28,9.45,0,0,225,0,0,true),
('PBG316','RBM-105','Fresh and Tasty (16)','Pizza Box','Generic Pizza Box','FRESH & TASTY -16 x 16 x 1.5"',18.4,12.75,0,0,575,0,0,true),
('PBG318','RBM-106','Fresh and Tasty (18)','Pizza Box','Generic Pizza Box','FRESH & TASTY -18 x 18 x 1.5"',22,15,0,0,1450,0,0,true),
('PBG410','RBM-030','Care for a Slice (10)','Pizza Box','Generic Pizza Box','HOME MADE 10.5 x 10.5 x 1.5 inches',10.5,5.5,0,0,10450,0,0,true),
('PBG411','RBM-031','Care for a Slice (11)','Pizza Box','Generic Pizza Box','HOME MADE 11.5 x 11.5 x 1.5 inches',11.55,6.25,0,0,4650,0,0,true),
('PBG412','RBM-032','Care for a Slice (12)','Pizza Box','Generic Pizza Box','HOME MADE 12.5 x 12.5 x 1.5 inches',12.6,7,0,0,6650,0,0,true),
('PBG510','RBM-061','Yummy Oven Pizza (10)','Pizza Box','Generic Pizza Box','YUMMY OVEN FRESH 10.5 x 10.5 x 1.5 inches',10.5,5.5,0,0,8300,0,0,true),
('PBG511','RBM-062','Yummy Oven Pizza (11)','Pizza Box','Generic Pizza Box','YUMMY OVEN FRESH 11.5 x 11.5 x 1.5 inches',11.55,6.25,0,0,3000,0,0,true),
('PBG512','RBM-063','Yummy Oven Pizza (12)','Pizza Box','Generic Pizza Box','YUMMY OVEN FRESH 12.5 x 12.5 x 1.5 inches',12.6,7,0,0,1000,0,0,true),
('PBG610','RBM-054-R2','It''s Pizza Time (10)','Pizza Box','Generic Pizza Box','IT''S PIZZA TIME 10 x 10 x 1.5 inches',10.5,5.5,0,0,2900,0,0,true),
('PBG611','RBM-055-R2','It''s Pizza Time (11)','Pizza Box','Generic Pizza Box','IT''S PIZZA TIME 11 x 11 x 1.5 inches',11.55,6.25,0,0,3700,0,0,true),
('PBG612','RBM-056-R2','It''s Pizza Time (12)','Pizza Box','Generic Pizza Box','IT''S PIZZA TIME 12 x 12 x 1.5 inches',12.6,7,0,0,575,0,0,true),
('PBG614','RBM-057-R2','It''s Pizza Time (14)','Pizza Box','Generic Pizza Box','IT''S PIZZA TIME 14 x 14 x 1.5 inches',16.28,9.45,0,0,1825,0,0,true),
('PBG710','RBM-070','Making a Difference (10)','Pizza Box','Generic Pizza Box','MAKING A DIFFERENCE 10.5 x 10.5 x 1.5 inches',10.5,5.5,0,0,8550,0,0,true),
('PBG711','RBM-071','Making a Difference (11)','Pizza Box','Generic Pizza Box','MAKING A DIFFERENCE 11.5 x 11.5 x 1.5 inches',11.55,6.25,0,0,3650,0,0,true),
('PBG712','RBM-072','Making a Difference (12)','Pizza Box','Generic Pizza Box','MAKING A DIFFERENCE 12.5 x 12.5 x 1.5 inches',12.6,7,0,0,1000,0,0,true),
('PBG714','RBM-073','Making a Difference (14)','Pizza Box','Generic Pizza Box','MAKING A DIFFERENCE 14 x 14 x 1.5 inches',16.28,9.45,0,0,225,0,0,true),
('PBG808','RBM-074','Super Delicious Pizza (8)','Pizza Box','Generic Pizza Box','SUPER DELICIOUS 8 x 8 x 1.5 inches',9.5,4.25,0,0,825,0,0,true),
('PBG810','RBM-075','Super Delicious Pizza (10)','Pizza Box','Generic Pizza Box','SUPER DELICIOUS 10 x 10 x 1.5 inches',10.5,5.5,0,0,3950,0,0,true),
('PBG811','RBM-076','Super Delicious Pizza (11)','Pizza Box','Generic Pizza Box','SUPER DELICIOUS 11 x 11 x 1.5 inches',11.55,6.25,0,0,3600,0,0,true),
('PBG812','RBM-077','Super Delicious Pizza (12)','Pizza Box','Generic Pizza Box','SUPER DELICIOUS 12 x 12 x 1.5 inches',12.6,7,0,0,4025,0,0,true),
('PBG814','RBM-078','Super Delicious Pizza (14)','Pizza Box','Generic Pizza Box','SUPER DELICIOUS 14 x 14 x 1.5 inches',12.6,9.45,0,0,4025,0,0,true),
('PBG910','RBM-121','Taste of Greatness (10)','Pizza Box','Generic Pizza Box','TASTE OF GREATNESS - 10.5 x 10.5 x 1.5"',10.5,5.5,0,0,3775,0,0,true),
('PBG912','RBM-122','Taste of Greatness (12)','Pizza Box','Generic Pizza Box','TASTE OF GREATNESS - 12.5 x 12.5 x 1.5"',12.6,7,0,0,4450,0,0,true),

-- ── PIZZA BOX — Holiday ─────────────────────────────────────────────────
('PBGH01',NULL,'Holiday Pizza Box (Old)(14)','Pizza Box','Generic Pizza Box',NULL,16.28,11.45,0,0,6075,0,0,true),

-- ── PIZZA BOX — Clay Coat ───────────────────────────────────────────────
('PBC110',NULL,'Black Clay Coat Pizza Box (10)','Pizza Box','Claycoat Pizza Box','CLAYCOATED CAL.18-10 X 10 X 1.5" HOMEMADE (BLACK)',9.98,6,0,0,3250,0,0,true),
('PBC111',NULL,'Black Clay Coat Pizza Box (11)','Pizza Box','Claycoat Pizza Box','CLAYCOATED CAL.18-11 X 11 X 1.5" HOMEMADE (BLACK)',11.5,7,0,0,1000,0,0,true),
('PBC112',NULL,'Black Clay Coat Pizza Box (12)','Pizza Box','Claycoat Pizza Box','CLAYCOATED CAL.18-12 X 12 X 1.5" HOMEMADE (BLACK)',12.08,7.5,0,0,11800,0,0,true),
('PBC210',NULL,'Red Clay Coat Pizza Box (10)','Pizza Box','Claycoat Pizza Box','CLAYCOATED CAL.18-10 X 10 X 1.5"PERFECT FOR SHARING (RED)',9.98,6,0,0,1000,0,0,true),
('PBC211',NULL,'Red Clay Coat Pizza Box (11)','Pizza Box','Claycoat Pizza Box','CLAYCOATED CAL.18-11 X 11 X 1.5"PERFECT FOR SHARING (RED)',11.5,7,0,0,1000,0,0,true),
('PBC212',NULL,'Red Clay Coat Pizza Box (12)','Pizza Box','Claycoat Pizza Box','CLAYCOATED CAL.18-12 X 12 X 1.5"PERFECT FOR SHARING (RED)',12.08,7.5,0,0,6900,0,0,true),
('PBC310',NULL,'Green Clay Coat Pizza Box (10)','Pizza Box','Claycoat Pizza Box','CLAYCOATED CAL.18-10 X 10 X 1.5" SUPER DELICIOUS BEST PIZZA (GREEN)',9.98,6,0,0,1000,0,0,true),
('PBC311',NULL,'Green Clay Coat Pizza Box (11)','Pizza Box','Claycoat Pizza Box','CLAYCOATED CAL.18-11 X 11 X 1.5" SUPER DELICIOUS BEST PIZZA (GREEN)',11.5,7,0,0,1000,0,0,true),
('PBC312',NULL,'Green Clay Coat Pizza Box (12)','Pizza Box','Claycoat Pizza Box','CLAYCOATED CAL.18-12 X 12 X 1.5" SUPER DELICIOUS BEST PIZZA (GREEN)',12.08,7.5,0,0,8850,0,0,true),

-- ── RSC BOXES ────────────────────────────────────────────────────────────
('SBP00A','RBM-025','(A) RSC -  8 x 4.72 x 3.54 inches','RSC',NULL,'BOX A - 205 x 120 x 90 mm',12.6,6,0,0,1519,0,0,true),
('SBP00B','RBM-026','(B) RSC -8.86 x 5.51 x 3.94 inches','RSC',NULL,'BOX B - 225 x 140 x 100 mm',13.65,6.3,0,0,950,0,0,true),
('SBP00C','RBM-027','(C) RSC - 13.58 x 7 x 3.94 inches','RSC',NULL,'BOX C - 340 x 150 x 100 mm',14.7,7.25,0,0,1000,0,0,true),
('SBP00D','RBM-048','(D) RSC - 13.58 x 13.58 x 9.54 inches','RSC',NULL,'BOX D - 345 x 345 x 240 mm',33.6,22,0,0,1023,0,0,true),
('SBP00E','RBM-049','(E) RSC - 20 x 10 x 12 iches','RSC',NULL,'BOX  E - 510 x 265 x 303 mm',34.65,23,0,0,251,0,0,true),
('SBP00F','RBM-028','(F) RSC - 20 x 18 x 18 inches','RSC',NULL,'BOX F- 20 x 18 x 18 inches',78.75,53,0,0,25,0,0,true),
('SBP00G','RBM-116','(G) RSC - 20 x 20 x 20 inches','RSC',NULL,'PLAIN - 20 x 20 x 20 inches DOUBLE WALL',110,28.25,0,0,1000,0,0,true),

-- ── RSC BOXES — J&T Sizes ───────────────────────────────────────────────
('SBJTXS','RBM-092','(XS) J&T Extra Small','RSC',NULL,'BOX 1 - 300 x 240 x 150 mm',15.75,10,0,0,1765,0,0,true),
('SBJT0S','RBM-094','(S) J&T Small','RSC',NULL,'BOX 2 - 360 x 270 x 180 mm',23.1,13,0,0,1000,0,0,true),
('SBJT0M','RBM-095','(M) J&T Medium','RSC',NULL,'BOX 3 - 460 x 300 x 230 mm',32.55,18,0,0,1000,0,0,true),
('SBJT0L','RBM-096','(L) J&T Large','RSC',NULL,'BOX 4 - 600 x 360 x 320 mm',59.85,31,0,0,196,0,0,true),

-- ── DIE-CUT — Mailer Box ────────────────────────────────────────────────
('DBPMB1','RBM-089','(MB1) Mailer Box T0','Die-Cut Packaging Boxes','Mailer Box','T0 - 157 x 100 x 30 mm',5.78,3.51,0,0,3325,0,0,true),
('DBPMB2','RBM-090','(MB2) Mailer Box T1','Die-Cut Packaging Boxes','Mailer Box','T1 - 159 x 150 x 50 mm',7.35,4.45,0,0,275,0,0,true),
('DBPMB3','RBM-091','(MB3) Mailer Box T2','Die-Cut Packaging Boxes','Mailer Box','T2 - 208 x 140 x 40 mm',8.4,4.79,0,0,2000,0,0,true),
('DBPMB4',NULL,'(MB4) Mailer Box T13','Die-Cut Packaging Boxes','Mailer Box','T13 - 200 x 140 x 80 mm',12.34,8.4,0,0,2575,0,0,true),
('DBPMB5','RBM-093','(MB5) Mailer Box T5','Die-Cut Packaging Boxes','Mailer Box','T5 - 320 x 215 x 50 mm',12.6,8.85,0,0,1775,0,0,true),
('DBPMB6','RBM-098','(MB6) Mailer Box T10','Die-Cut Packaging Boxes','Mailer Box','T10 - 339 x 240 x 80 mm',22.05,12.56,0,0,2225,0,0,true),

-- ── DIE-CUT — Flower Box ────────────────────────────────────────────────
('DBFB01','RBM-113','Flower Box - 22 x 10 x 8 inches','Die-Cut Packaging Boxes','Flower Box','FLOWER BOX - 22 x 10 x 8 inches',47.25,30.5,0,0,1000,0,1250,true),

-- ── OFFSET PACKAGING — Pastry Box ────────────────────────────────────────
('OFPBVS',NULL,'(VS) Violet Small - 6.8 x 5 x 2 Inches','Offset Packaging Boxes','Pastry Box','FC#18 - Tasty Baked Goods, Small (Violet)',8,0,0,0,8450,0,0,true),
('OFPBVB',NULL,'(VB) Violet Big - 9 x 9 x 2 inches','Offset Packaging Boxes','Pastry Box','FC#18 - Tasty Baked Goods, Big (Violet)',10.5,0,0,0,8475,0,0,true),
('OFPBBS',NULL,'(BS) Brown Small - 6.8 x 5 x 2 Inches','Offset Packaging Boxes','Pastry Box','FC#18 - Yummy Treats, Small (Brown)',8,0,0,0,9750,0,0,true),
('OFPBBB',NULL,'(BB) Brown Big - 9 x 9 x 2 inches','Offset Packaging Boxes','Pastry Box','FC#18 - Yummy Treats, Big (Brown)',10.5,0,0,0,9300,0,0,true),

-- ── DIE-CUT — Party Box 4-in-1 ──────────────────────────────────────────
('PB4P18','RBM-109','(18) Plain 4 in 1 Party Box','Die-Cut Packaging Boxes','Party Box','457 x 457 x 45 mm - 18" PLAIN PARTY BOX',37.8,26,0,0,1000,0,0,true),
('PB4P20','RBM-114-1','(20) Plain 4 in 1 Party Box','Die-Cut Packaging Boxes','Party Box','PLAIN PARTY BOX  - 20 x 20 x 2 inches',39.9,28,0,0,1000,0,0,true),
('PB4S18','RBM-107','(18) Sarap na Binabalik Balikan 4 in 1 Party Box (SNBB)','Die-Cut Packaging Boxes','Party Box','457 x 457 x 45 mm - SARAP NA BABALIK BALIKAN',38.85,27,0,0,1000,0,0,true),
('PB4U18','RBM-111-1','(18) Unli Sarap 4 in 1 Party Box','Die-Cut Packaging Boxes','Party Box','457 x 457 x 45 mm - UNLI SARAP',38.85,27,0,0,450,0,0,true),
('PB4U20','RBM-112-1','(20) Unli Sarap 4 in 1 Party Box','Die-Cut Packaging Boxes','Party Box','20 x 20 x 2 inches - UNLI SARAP',42,29,0,0,500,0,0,true),
('PB4D18','RBM-120-1','(18) Delicious Feast 4 in 1 Party Box','Die-Cut Packaging Boxes','Party Box','PARTY BOX DELICIOUS FEAST - 457 x 457 x 45 mm',38.85,27,0,0,950,0,0,true),

-- ── DIE-CUT — Party Box Inner Tray ──────────────────────────────────────
('PBIT08','RBM-108','(8) Inner Tray - Party Box','Die-Cut Packaging Boxes','Party Box','223 x 218 x 37 mm',0,0,0,0,26525,0,0,true),
('PBIT10','RBM-112-2','(10) Inner Tray - Party Box','Die-Cut Packaging Boxes','Party Box','10 x 10 x 1.75 inches',0,0,0,0,15025,0,0,true),

-- ── DIE-CUT — Lechon Belly Box ──────────────────────────────────────────
('LBR00S','RBM-044','(S) Lechon Belly Box - 9 x 6 x 5 inches','Die-Cut Packaging Boxes','Roasted Food Boxes','LECHON BELLY - 9 x 6 x 5 inches',18.9,11,0,0,1150,0,0,true),
('LBR00M','RBM-012','(M) Lechon Belly Box - 13 x 8 x 6 inches','Die-Cut Packaging Boxes','Roasted Food Boxes','LECHON BELLY  - 13 x 8 x 6 inches',24.15,16.5,0,0,900,0,0,true),
('LBR00L','RBM-125','(L) Lechon Belly Box - 16 x 8 x 7 inches','Die-Cut Packaging Boxes','Roasted Food Boxes','LECHON BELLY LARGE - 16 x 8 x 7 inches',26.25,18.5,0,0,1300,0,0,true),

-- ── DIE-CUT — Conchinillo Box ───────────────────────────────────────────
('CCLT01','RBM-085-1','Conchinillio Box (Top)','Die-Cut Packaging Boxes','Roasted Food Boxes','CONCHINILLO TOP - 24 x 16 x 6 inches',68.25,34,0,0,164,0,0,true),
('CCLB01','RBM-085-2','Conchinillio Box (Bottom)','Die-Cut Packaging Boxes','Roasted Food Boxes','CONCHINILLO BOTTOM -',0,0,0,0,164,0,0,true),

-- ── DIE-CUT — Lechon Box ────────────────────────────────────────────────
('LHNB01','RBM-119','Lechon Box','Die-Cut Packaging Boxes','Roasted Food Boxes','RSC LECHON BOX|C#200|PLAIN | 920 x 254 x 254 mm',40.95,28.25,0,0,1000,0,0,true),

-- ── DIE-CUT — Bilao Box ─────────────────────────────────────────────────
('BB010T','RBM-123-1','(10) Bilao Box - Top','Die-Cut Packaging Boxes','Bilao Boxes','BILAO BOX -267 x 267 x 51',11.55,8.17,0,0,0,0,2325,true),
('BB010B','RBM-123-2','(10) Bilao Box - Bottom','Die-Cut Packaging Boxes','Bilao Boxes',NULL,0,0,0,0,0,0,2325,true),
('BB012T','RBM-124-1','(12) Bilao Box - Top','Die-Cut Packaging Boxes','Bilao Boxes','BILAO BOX - 318 x 318 x 51',15.75,12.2,0,0,0,0,2425,true),
('BB012B','RBM-124-2','(12) Bilao Box - Bottom','Die-Cut Packaging Boxes','Bilao Boxes',NULL,15.75,0,0,0,0,0,2425,true),

-- ── PIZZA SUPPLIES ─────────────────────────────────────────────────────
('TRP001',NULL,'Pizza Tripod','Pizza Supplies',NULL,NULL,1.26,0,0,0,0,0,210000,true),
('WXP010',NULL,'(10) Wax Paper','Pizza Supplies',NULL,NULL,0.95,0,0,0,0,0,1000,true),
('WXP011',NULL,'(11) Wax Paper','Pizza Supplies',NULL,NULL,1,0,0,0,0,0,1000,true),
('WXP012',NULL,'(12) Wax Paper','Pizza Supplies',NULL,NULL,1.05,0,0,0,0,0,1000,true),
('WXP014',NULL,'(14) Wax Paper','Pizza Supplies',NULL,NULL,1.26,0,0,0,0,0,1000,true),

-- ── PACKAGING SUPPLIES — Tapes ─────────────────────────────────────────
('SPTC50',NULL,'Packaging Tape (Clear) 2M x 50','Packaging Supplies','Tapes',NULL,25.2,0,0,0,142,0,0,true),
('SPTC75',NULL,'Packaging Tape (Clear) 2M x 75','Packaging Supplies','Tapes',NULL,0,0,0,0,1000,0,0,true),
('SPTC1H',NULL,'Packaging Tape (Clear) 2M x 100','Packaging Supplies','Tapes',NULL,36.75,0,0,0,1000,0,0,true),
('SPTF50',NULL,'Packaging Tape (Fragile) 2M x 50','Packaging Supplies','Tapes',NULL,42,0,0,0,138,0,0,true),
('SPTF75',NULL,'Packaging Tape (Fragile) 2M x 75','Packaging Supplies','Tapes',NULL,0,0,0,0,1000,0,0,true),
('SPTF1H',NULL,'Packaging Tape (Fragile) 2M x 100','Packaging Supplies','Tapes',NULL,70.35,0,0,0,216,0,0,true),

-- ── PACKAGING SUPPLIES — Wrappers ──────────────────────────────────────
('SBW00H',NULL,'Bubble Wrap Clear (Half) 20M x 100','Packaging Supplies','Wrappers',NULL,367.5,0,0,0,1000,0,0,true),
('SBW00W',NULL,'Bubble Wrap Clear (Whole) 40M x 100','Packaging Supplies','Wrappers',NULL,735,0,0,0,1000,0,0,true),
('SSF250',NULL,'Stretch Film 20M x 250','Packaging Supplies','Wrappers',NULL,367.5,0,0,0,1000,0,0,true),
('SSF500',NULL,'Stretch Film 20M x 500','Packaging Supplies','Wrappers',NULL,525,0,0,0,1000,0,0,true)

) AS v(
    product_code, item_code, name, category, sub_category, description,
    unit_price, unit_cost, threshold_critical, threshold_low,
    stock_wh1, stock_wh2, stock_wh3, active
)
WHERE NOT EXISTS (
    SELECT 1 FROM products p WHERE p.product_code = v.product_code
);

-- ═══════════════════════════════════════════════════════════════════════
-- Part 2: Supplier
-- ═══════════════════════════════════════════════════════════════════════

INSERT INTO suppliers (name, contact_person, contact_number, payment_terms, is_active)
SELECT 'Well-Pack Container Corporation', NULL, NULL, NULL, true
WHERE NOT EXISTS (
    SELECT 1 FROM suppliers WHERE name = 'Well-Pack Container Corporation'
);

-- ═══════════════════════════════════════════════════════════════════════
-- Part 3: Supplier–product mappings (83 products with item codes)
-- supplier_item_code = Item Code, supplier_description = Description,
-- unit_cost = Unit Cost from CSV. Skips if mapping already exists.
-- ═══════════════════════════════════════════════════════════════════════

INSERT INTO supplier_product_mapping
    (supplier_id, product_id, supplier_item_code, supplier_description, unit_cost)
SELECT
    (SELECT id FROM suppliers WHERE name = 'Well-Pack Container Corporation'),
    p.id,
    v.supplier_item_code,
    v.supplier_description,
    v.unit_cost
FROM (VALUES
-- Plain Pizza Box
('PPB006','RBM-115','PLAIN PIZZA BOX  -  6 x 6  x 1.5 inches',3.25),
('PPB008','RBM-009','PLAIN BROWN 8 x 8 x 1.5 inches',3.75),
('PPB010','RBM-003-R2','PLAIN BROWN 10.5 x 10.5 x 1.5 inches',5.3),
('PPB011','RBM-008','PLAIN BROWN 11.5 x 11.5 x 1.5 inches',6.0),
('PPB012','RBM-002','PLAIN BROWN 12.5 x 12.5 x 1.5 inches',6.5),
('PPB014','RBM-004-R1','PLAIN BROWN 14 x 14 x 1.5 inches',9.0),
('PPB016','RBM-010','PLAIN BROWN 16 x 16 x 1.5 inches',12.1),
('PPB018','RBM-038','PLAIN BROWN PIZZA BOX - 18 x 18 x 1.5"',14.5),
-- Plain White Pizza Box
('PPBW10','RBM-052','PLAIN WHITE 10.5 x 10.5 x 1.5 inches',6.35),
('PPBW12','RBM-053','PLAIN WHITE 12.5 x 12.5 x 1.5 inches',7.5),
-- Generic Pizza Box
('PBG110','RBM-005','HOT FRESH 10.5 x 10.5 x 1.5 inches',5.5),
('PBG111','RBM-011-R2','HOT FRESH PIZZA BOX 11.5 x 11.5 x 1.5 inches',6.25),
('PBG112','RBM-006-R1','HOT FRESH 12.5 x 12.5 x 1.5 inches',7.0),
('PBG114','RBM-007','HOT FRESH 14 x 14 x 1.5 inches',9.45),
('PBG210','RBM-058-R1','HOT & DELICIOUS 10.5 x 10.5 x 1.5 inches',5.5),
('PBG211','RBM-059','HOT & DELICIOUS 11.5 x 11.5 x 1.5 inches',6.25),
('PBG212','RBM-060-R1','HOT & DELICIOUS 12.5 x 12.5 x 1.5 inches',7.0),
('PBG308','RBM-104','FRESH & TASTY - 8 x 8 x 1.5 inches',4.25),
('PBG310','RBM-021-R2','FRESH & TASTY  10.5 x 10.5 x 1.5 inches',5.5),
('PBG311','RBM-022-R2','FRESH & TASTY  11.5 x 11.5 x 1.5 inches',6.25),
('PBG312','RBM-023-R1','FRESH & TASTY -12.5 X 12.5 X 1.5"',7.0),
('PBG314','RBM-024-R1','FRESH & TASTY 14 x 14 x 1.5 inches',9.45),
('PBG316','RBM-105','FRESH & TASTY -16 x 16 x 1.5"',12.75),
('PBG318','RBM-106','FRESH & TASTY -18 x 18 x 1.5"',15.0),
('PBG410','RBM-030','HOME MADE 10.5 x 10.5 x 1.5 inches',5.5),
('PBG411','RBM-031','HOME MADE 11.5 x 11.5 x 1.5 inches',6.25),
('PBG412','RBM-032','HOME MADE 12.5 x 12.5 x 1.5 inches',7.0),
('PBG510','RBM-061','YUMMY OVEN FRESH 10.5 x 10.5 x 1.5 inches',5.5),
('PBG511','RBM-062','YUMMY OVEN FRESH 11.5 x 11.5 x 1.5 inches',6.25),
('PBG512','RBM-063','YUMMY OVEN FRESH 12.5 x 12.5 x 1.5 inches',7.0),
('PBG610','RBM-054-R2','IT''S PIZZA TIME 10 x 10 x 1.5 inches',5.5),
('PBG611','RBM-055-R2','IT''S PIZZA TIME 11 x 11 x 1.5 inches',6.25),
('PBG612','RBM-056-R2','IT''S PIZZA TIME 12 x 12 x 1.5 inches',7.0),
('PBG614','RBM-057-R2','IT''S PIZZA TIME 14 x 14 x 1.5 inches',9.45),
('PBG710','RBM-070','MAKING A DIFFERENCE 10.5 x 10.5 x 1.5 inches',5.5),
('PBG711','RBM-071','MAKING A DIFFERENCE 11.5 x 11.5 x 1.5 inches',6.25),
('PBG712','RBM-072','MAKING A DIFFERENCE 12.5 x 12.5 x 1.5 inches',7.0),
('PBG714','RBM-073','MAKING A DIFFERENCE 14 x 14 x 1.5 inches',9.45),
('PBG808','RBM-074','SUPER DELICIOUS 8 x 8 x 1.5 inches',4.25),
('PBG810','RBM-075','SUPER DELICIOUS 10 x 10 x 1.5 inches',5.5),
('PBG811','RBM-076','SUPER DELICIOUS 11 x 11 x 1.5 inches',6.25),
('PBG812','RBM-077','SUPER DELICIOUS 12 x 12 x 1.5 inches',7.0),
('PBG814','RBM-078','SUPER DELICIOUS 14 x 14 x 1.5 inches',9.45),
('PBG910','RBM-121','TASTE OF GREATNESS - 10.5 x 10.5 x 1.5"',5.5),
('PBG912','RBM-122','TASTE OF GREATNESS - 12.5 x 12.5 x 1.5"',7.0),
-- RSC
('SBP00A','RBM-025','BOX A - 205 x 120 x 90 mm',6.0),
('SBP00B','RBM-026','BOX B - 225 x 140 x 100 mm',6.3),
('SBP00C','RBM-027','BOX C - 340 x 150 x 100 mm',7.25),
('SBP00D','RBM-048','BOX D - 345 x 345 x 240 mm',22.0),
('SBP00E','RBM-049','BOX  E - 510 x 265 x 303 mm',23.0),
('SBP00F','RBM-028','BOX F- 20 x 18 x 18 inches',53.0),
('SBP00G','RBM-116','PLAIN - 20 x 20 x 20 inches DOUBLE WALL',28.25),
('SBJTXS','RBM-092','BOX 1 - 300 x 240 x 150 mm',10.0),
('SBJT0S','RBM-094','BOX 2 - 360 x 270 x 180 mm',13.0),
('SBJT0M','RBM-095','BOX 3 - 460 x 300 x 230 mm',18.0),
('SBJT0L','RBM-096','BOX 4 - 600 x 360 x 320 mm',31.0),
-- Mailer Box
('DBPMB1','RBM-089','T0 - 157 x 100 x 30 mm',3.51),
('DBPMB2','RBM-090','T1 - 159 x 150 x 50 mm',4.45),
('DBPMB3','RBM-091','T2 - 208 x 140 x 40 mm',4.79),
('DBPMB4','RBM-092','T13 - 200 x 140 x 80 mm',8.4),
('DBPMB5','RBM-093','T5 - 320 x 215 x 50 mm',8.85),
('DBPMB6','RBM-098','T10 - 339 x 240 x 80 mm',12.56),
-- Flower Box
('DBFB01','RBM-113','FLOWER BOX - 22 x 10 x 8 inches',30.5),
-- Party Box
('PB4P18','RBM-109','457 x 457 x 45 mm - 18" PLAIN PARTY BOX',26.0),
('PB4P20','RBM-114-1','PLAIN PARTY BOX  - 20 x 20 x 2 inches',28.0),
('PB4S18','RBM-107','457 x 457 x 45 mm - SARAP NA BABALIK BALIKAN',27.0),
('PB4U18','RBM-111-1','457 x 457 x 45 mm - UNLI SARAP',27.0),
('PB4U20','RBM-112-1','20 x 20 x 2 inches - UNLI SARAP',29.0),
('PB4D18','RBM-120-1','PARTY BOX DELICIOUS FEAST - 457 x 457 x 45 mm',27.0),
-- Party Box Inner Tray
('PBIT08','RBM-108','223 x 218 x 37 mm',0.0),
('PBIT10','RBM-112-2','10 x 10 x 1.75 inches',0.0),
-- Lechon / Roasted Food
('LBR00S','RBM-044','LECHON BELLY - 9 x 6 x 5 inches',11.0),
('LBR00M','RBM-012','LECHON BELLY  - 13 x 8 x 6 inches',16.5),
('LBR00L','RBM-125','LECHON BELLY LARGE - 16 x 8 x 7 inches',18.5),
('CCLT01','RBM-085-1','CONCHINILLO TOP - 24 x 16 x 6 inches',34.0),
('CCLB01','RBM-085-2','CONCHINILLO BOTTOM -',0.0),
('LHNB01','RBM-119','RSC LECHON BOX|C#200|PLAIN | 920 x 254 x 254 mm',28.25),
-- Bilao Box
('BB010T','RBM-123-1','BILAO BOX -267 x 267 x 51',8.17),
('BB010B','RBM-123-2',NULL,0.0),
('BB012T','RBM-124-1','BILAO BOX - 318 x 318 x 51',12.2),
('BB012B','RBM-124-2',NULL,0.0)
) AS v(product_code, supplier_item_code, supplier_description, unit_cost)
JOIN products p ON p.product_code = v.product_code
WHERE NOT EXISTS (
    SELECT 1 FROM supplier_product_mapping m
    WHERE m.supplier_id = (SELECT id FROM suppliers WHERE name = 'Well-Pack Container Corporation')
      AND m.product_id = p.id
);
