-- V28: Update inventory data from rrbm-inventory.csv (May 2026)
-- Updates: item_code, description, unit_cost per product
-- Note: 'Santan' warehouse → display renamed to 'Balagtas' (frontend only; column stock_wh3 unchanged)
-- Note: SBJTXS and DBPMB4 both listed RBM-092 in CSV; assigned to SBJTXS (first occurrence), DBPMB4 skipped.

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Plain Pizza Box (PPB)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-115',    description = 'PLAIN PIZZA BOX - 6 x 6 x 1.5 inches',           unit_cost = 3.25  WHERE product_code = 'PPB006';
UPDATE products SET item_code = 'RBM-009',    description = 'PLAIN BROWN 8 x 8 x 1.5 inches',                 unit_cost = 3.75  WHERE product_code = 'PPB008';
UPDATE products SET item_code = 'RBM-003-R2', description = 'PLAIN BROWN 10.5 x 10.5 x 1.5 inches',           unit_cost = 5.30  WHERE product_code = 'PPB010';
UPDATE products SET item_code = 'RBM-008',    description = 'PLAIN BROWN 11.5 x 11.5 x 1.5 inches',           unit_cost = 6.00  WHERE product_code = 'PPB011';
UPDATE products SET item_code = 'RBM-002',    description = 'PLAIN BROWN 12.5 x 12.5 x 1.5 inches',           unit_cost = 6.50  WHERE product_code = 'PPB012';
UPDATE products SET item_code = 'RBM-004-R1', description = 'PLAIN BROWN 14 x 14 x 1.5 inches',               unit_cost = 9.00  WHERE product_code = 'PPB014';
UPDATE products SET item_code = 'RBM-010',    description = 'PLAIN BROWN 16 x 16 x 1.5 inches',               unit_cost = 12.10 WHERE product_code = 'PPB016';
UPDATE products SET item_code = 'RBM-038',    description = 'PLAIN BROWN PIZZA BOX - 18 x 18 x 1.5"',         unit_cost = 14.50 WHERE product_code = 'PPB018';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Plain White (PPBW)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-052', description = 'PLAIN WHITE 10.5 x 10.5 x 1.5 inches', unit_cost = 6.35 WHERE product_code = 'PPBW10';
UPDATE products SET item_code = 'RBM-053', description = 'PLAIN WHITE 12.5 x 12.5 x 1.5 inches', unit_cost = 7.50 WHERE product_code = 'PPBW12';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Generic: Hot Fresh (PBG1)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-005',    description = 'HOT FRESH 10.5 x 10.5 x 1.5 inches',             unit_cost = 5.50 WHERE product_code = 'PBG110';
UPDATE products SET item_code = 'RBM-011-R2', description = 'HOT FRESH PIZZA BOX 11.5 x 11.5 x 1.5 inches',   unit_cost = 6.25 WHERE product_code = 'PBG111';
UPDATE products SET item_code = 'RBM-006-R1', description = 'HOT FRESH 12.5 x 12.5 x 1.5 inches',             unit_cost = 7.00 WHERE product_code = 'PBG112';
UPDATE products SET item_code = 'RBM-007',    description = 'HOT FRESH 14 x 14 x 1.5 inches',                 unit_cost = 9.45 WHERE product_code = 'PBG114';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Generic: Hot and Delicious (PBG2)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-058-R1', description = 'HOT & DELICIOUS 10.5 x 10.5 x 1.5 inches', unit_cost = 5.50 WHERE product_code = 'PBG210';
UPDATE products SET item_code = 'RBM-059',    description = 'HOT & DELICIOUS 11.5 x 11.5 x 1.5 inches', unit_cost = 6.25 WHERE product_code = 'PBG211';
UPDATE products SET item_code = 'RBM-060-R1', description = 'HOT & DELICIOUS 12.5 x 12.5 x 1.5 inches', unit_cost = 7.00 WHERE product_code = 'PBG212';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Generic: Fresh and Tasty (PBG3)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-104',    description = 'FRESH & TASTY - 8 x 8 x 1.5 inches',       unit_cost = 4.25  WHERE product_code = 'PBG308';
UPDATE products SET item_code = 'RBM-021-R2', description = 'FRESH & TASTY 10.5 x 10.5 x 1.5 inches',   unit_cost = 5.50  WHERE product_code = 'PBG310';
UPDATE products SET item_code = 'RBM-022-R2', description = 'FRESH & TASTY 11.5 x 11.5 x 1.5 inches',   unit_cost = 6.25  WHERE product_code = 'PBG311';
UPDATE products SET item_code = 'RBM-023-R1', description = 'FRESH & TASTY - 12.5 x 12.5 x 1.5"',       unit_cost = 7.00  WHERE product_code = 'PBG312';
UPDATE products SET item_code = 'RBM-024-R1', description = 'FRESH & TASTY 14 x 14 x 1.5 inches',       unit_cost = 9.45  WHERE product_code = 'PBG314';
UPDATE products SET item_code = 'RBM-105',    description = 'FRESH & TASTY - 16 x 16 x 1.5"',           unit_cost = 12.75 WHERE product_code = 'PBG316';
UPDATE products SET item_code = 'RBM-106',    description = 'FRESH & TASTY - 18 x 18 x 1.5"',           unit_cost = 15.00 WHERE product_code = 'PBG318';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Generic: Care for a Slice / Home Made (PBG4)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-030', description = 'HOME MADE 10.5 x 10.5 x 1.5 inches', unit_cost = 5.50 WHERE product_code = 'PBG410';
UPDATE products SET item_code = 'RBM-031', description = 'HOME MADE 11.5 x 11.5 x 1.5 inches', unit_cost = 6.25 WHERE product_code = 'PBG411';
UPDATE products SET item_code = 'RBM-032', description = 'HOME MADE 12.5 x 12.5 x 1.5 inches', unit_cost = 7.00 WHERE product_code = 'PBG412';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Generic: Yummy Oven Pizza (PBG5)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-061', description = 'YUMMY OVEN FRESH 10.5 x 10.5 x 1.5 inches', unit_cost = 5.50 WHERE product_code = 'PBG510';
UPDATE products SET item_code = 'RBM-062', description = 'YUMMY OVEN FRESH 11.5 x 11.5 x 1.5 inches', unit_cost = 6.25 WHERE product_code = 'PBG511';
UPDATE products SET item_code = 'RBM-063', description = 'YUMMY OVEN FRESH 12.5 x 12.5 x 1.5 inches', unit_cost = 7.00 WHERE product_code = 'PBG512';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Generic: It's Pizza Time (PBG6)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-054-R2', description = 'IT''S PIZZA TIME 10 x 10 x 1.5 inches', unit_cost = 5.50 WHERE product_code = 'PBG610';
UPDATE products SET item_code = 'RBM-055-R2', description = 'IT''S PIZZA TIME 11 x 11 x 1.5 inches', unit_cost = 6.25 WHERE product_code = 'PBG611';
UPDATE products SET item_code = 'RBM-056-R2', description = 'IT''S PIZZA TIME 12 x 12 x 1.5 inches', unit_cost = 7.00 WHERE product_code = 'PBG612';
UPDATE products SET item_code = 'RBM-057-R2', description = 'IT''S PIZZA TIME 14 x 14 x 1.5 inches', unit_cost = 9.45 WHERE product_code = 'PBG614';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Generic: Making a Difference (PBG7)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-070', description = 'MAKING A DIFFERENCE 10.5 x 10.5 x 1.5 inches', unit_cost = 5.50 WHERE product_code = 'PBG710';
UPDATE products SET item_code = 'RBM-071', description = 'MAKING A DIFFERENCE 11.5 x 11.5 x 1.5 inches', unit_cost = 6.25 WHERE product_code = 'PBG711';
UPDATE products SET item_code = 'RBM-072', description = 'MAKING A DIFFERENCE 12.5 x 12.5 x 1.5 inches', unit_cost = 7.00 WHERE product_code = 'PBG712';
UPDATE products SET item_code = 'RBM-073', description = 'MAKING A DIFFERENCE 14 x 14 x 1.5 inches',     unit_cost = 9.45 WHERE product_code = 'PBG714';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Generic: Super Delicious (PBG8)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-074', description = 'SUPER DELICIOUS 8 x 8 x 1.5 inches',   unit_cost = 4.25 WHERE product_code = 'PBG808';
UPDATE products SET item_code = 'RBM-075', description = 'SUPER DELICIOUS 10 x 10 x 1.5 inches', unit_cost = 5.50 WHERE product_code = 'PBG810';
UPDATE products SET item_code = 'RBM-076', description = 'SUPER DELICIOUS 11 x 11 x 1.5 inches', unit_cost = 6.25 WHERE product_code = 'PBG811';
UPDATE products SET item_code = 'RBM-077', description = 'SUPER DELICIOUS 12 x 12 x 1.5 inches', unit_cost = 7.00 WHERE product_code = 'PBG812';
UPDATE products SET item_code = 'RBM-078', description = 'SUPER DELICIOUS 14 x 14 x 1.5 inches', unit_cost = 9.45 WHERE product_code = 'PBG814';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Generic: Taste of Greatness (PBG9)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-121', description = 'TASTE OF GREATNESS - 10.5 x 10.5 x 1.5"', unit_cost = 5.50 WHERE product_code = 'PBG910';
UPDATE products SET item_code = 'RBM-122', description = 'TASTE OF GREATNESS - 12.5 x 12.5 x 1.5"', unit_cost = 7.00 WHERE product_code = 'PBG912';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Holiday / Seasonal (PBGH)
-- ═══════════════════════════════════════════════════════════════════════
-- PBGH01: no item_code or description in CSV; unit_cost updated only
UPDATE products SET unit_cost = 11.45 WHERE product_code = 'PBGH01';

-- ═══════════════════════════════════════════════════════════════════════
-- PIZZA BOX — Clay Coat (PBC) — no item_code in CSV
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET description = 'CLAYCOATED CAL.18 - 10 x 10 x 1.5" HOMEMADE (BLACK)',             unit_cost = 6.00 WHERE product_code = 'PBC110';
UPDATE products SET description = 'CLAYCOATED CAL.18 - 11 x 11 x 1.5" HOMEMADE (BLACK)',             unit_cost = 7.00 WHERE product_code = 'PBC111';
UPDATE products SET description = 'CLAYCOATED CAL.18 - 12 x 12 x 1.5" HOMEMADE (BLACK)',             unit_cost = 7.50 WHERE product_code = 'PBC112';
UPDATE products SET description = 'CLAYCOATED CAL.18 - 10 x 10 x 1.5" PERFECT FOR SHARING (RED)',    unit_cost = 6.00 WHERE product_code = 'PBC210';
UPDATE products SET description = 'CLAYCOATED CAL.18 - 11 x 11 x 1.5" PERFECT FOR SHARING (RED)',    unit_cost = 7.00 WHERE product_code = 'PBC211';
UPDATE products SET description = 'CLAYCOATED CAL.18 - 12 x 12 x 1.5" PERFECT FOR SHARING (RED)',    unit_cost = 7.50 WHERE product_code = 'PBC212';
UPDATE products SET description = 'CLAYCOATED CAL.18 - 10 x 10 x 1.5" SUPER DELICIOUS BEST PIZZA (GREEN)', unit_cost = 6.00 WHERE product_code = 'PBC310';
UPDATE products SET description = 'CLAYCOATED CAL.18 - 11 x 11 x 1.5" SUPER DELICIOUS BEST PIZZA (GREEN)', unit_cost = 7.00 WHERE product_code = 'PBC311';
UPDATE products SET description = 'CLAYCOATED CAL.18 - 12 x 12 x 1.5" SUPER DELICIOUS BEST PIZZA (GREEN)', unit_cost = 7.50 WHERE product_code = 'PBC312';

-- ═══════════════════════════════════════════════════════════════════════
-- RSC BOXES (SBP)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-025', description = 'BOX A - 205 x 120 x 90 mm',                   unit_cost = 6.00  WHERE product_code = 'SBP00A';
UPDATE products SET item_code = 'RBM-026', description = 'BOX B - 225 x 140 x 100 mm',                  unit_cost = 6.30  WHERE product_code = 'SBP00B';
UPDATE products SET item_code = 'RBM-027', description = 'BOX C - 340 x 150 x 100 mm',                  unit_cost = 7.25  WHERE product_code = 'SBP00C';
UPDATE products SET item_code = 'RBM-048', description = 'BOX D - 345 x 345 x 240 mm',                  unit_cost = 22.00 WHERE product_code = 'SBP00D';
UPDATE products SET item_code = 'RBM-049', description = 'BOX E - 510 x 265 x 303 mm',                  unit_cost = 23.00 WHERE product_code = 'SBP00E';
UPDATE products SET item_code = 'RBM-028', description = 'BOX F - 20 x 18 x 18 inches',                 unit_cost = 53.00 WHERE product_code = 'SBP00F';
UPDATE products SET item_code = 'RBM-116', description = 'PLAIN - 20 x 20 x 20 inches DOUBLE WALL',     unit_cost = 28.25 WHERE product_code = 'SBP00G';

-- ═══════════════════════════════════════════════════════════════════════
-- RSC BOXES — J&T Sizes (SBJT)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-092', description = 'BOX 1 - 300 x 240 x 150 mm', unit_cost = 10.00 WHERE product_code = 'SBJTXS';
UPDATE products SET item_code = 'RBM-094', description = 'BOX 2 - 360 x 270 x 180 mm', unit_cost = 13.00 WHERE product_code = 'SBJT0S';
UPDATE products SET item_code = 'RBM-095', description = 'BOX 3 - 460 x 300 x 230 mm', unit_cost = 18.00 WHERE product_code = 'SBJT0M';
UPDATE products SET item_code = 'RBM-096', description = 'BOX 4 - 600 x 360 x 320 mm', unit_cost = 31.00 WHERE product_code = 'SBJT0L';

-- ═══════════════════════════════════════════════════════════════════════
-- DIE-CUT — Mailer Box (DBPMB)
-- Note: DBPMB4 item_code skipped — RBM-092 is already assigned to SBJTXS above
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-089', description = 'T0  - 157 x 100 x 30 mm',    unit_cost = 3.51  WHERE product_code = 'DBPMB1';
UPDATE products SET item_code = 'RBM-090', description = 'T1  - 159 x 150 x 50 mm',    unit_cost = 4.45  WHERE product_code = 'DBPMB2';
UPDATE products SET item_code = 'RBM-091', description = 'T2  - 208 x 140 x 40 mm',    unit_cost = 4.79  WHERE product_code = 'DBPMB3';
UPDATE products SET                        description = 'T13 - 200 x 140 x 80 mm',    unit_cost = 8.40  WHERE product_code = 'DBPMB4';
UPDATE products SET item_code = 'RBM-093', description = 'T5  - 320 x 215 x 50 mm',    unit_cost = 8.85  WHERE product_code = 'DBPMB5';
UPDATE products SET item_code = 'RBM-098', description = 'T10 - 339 x 240 x 80 mm',    unit_cost = 12.56 WHERE product_code = 'DBPMB6';

-- ═══════════════════════════════════════════════════════════════════════
-- DIE-CUT — Flower Box (DBFB)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-113', description = 'FLOWER BOX - 22 x 10 x 8 inches', unit_cost = 30.50 WHERE product_code = 'DBFB01';

-- ═══════════════════════════════════════════════════════════════════════
-- OFFSET PACKAGING — Pastry Box (OFPB) — no item_code in CSV
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET description = 'FC#18 - Tasty Baked Goods, Small (Violet)' WHERE product_code = 'OFPBVS';
UPDATE products SET description = 'FC#18 - Tasty Baked Goods, Big (Violet)'   WHERE product_code = 'OFPBVB';
UPDATE products SET description = 'FC#18 - Yummy Treats, Small (Brown)'        WHERE product_code = 'OFPBBS';
UPDATE products SET description = 'FC#18 - Yummy Treats, Big (Brown)'          WHERE product_code = 'OFPBBB';

-- ═══════════════════════════════════════════════════════════════════════
-- DIE-CUT — Party Box 4-in-1 (PB4)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-109',   description = '457 x 457 x 45 mm - 18" PLAIN PARTY BOX',          unit_cost = 26.00 WHERE product_code = 'PB4P18';
UPDATE products SET item_code = 'RBM-114-1', description = 'PLAIN PARTY BOX - 20 x 20 x 2 inches',              unit_cost = 28.00 WHERE product_code = 'PB4P20';
UPDATE products SET item_code = 'RBM-107',   description = '457 x 457 x 45 mm - SARAP NA BABALIK BALIKAN',      unit_cost = 27.00 WHERE product_code = 'PB4S18';
UPDATE products SET item_code = 'RBM-111-1', description = '457 x 457 x 45 mm - UNLI SARAP',                    unit_cost = 27.00 WHERE product_code = 'PB4U18';
UPDATE products SET item_code = 'RBM-112-1', description = '20 x 20 x 2 inches - UNLI SARAP',                   unit_cost = 29.00 WHERE product_code = 'PB4U20';
UPDATE products SET item_code = 'RBM-120-1', description = 'PARTY BOX DELICIOUS FEAST - 457 x 457 x 45 mm',     unit_cost = 27.00 WHERE product_code = 'PB4D18';

-- ═══════════════════════════════════════════════════════════════════════
-- DIE-CUT — Party Box Inner Tray (PBIT)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-108',   description = '223 x 218 x 37 mm',      unit_cost = 0.00 WHERE product_code = 'PBIT08';
UPDATE products SET item_code = 'RBM-112-2', description = '10 x 10 x 1.75 inches',  unit_cost = 0.00 WHERE product_code = 'PBIT10';

-- ═══════════════════════════════════════════════════════════════════════
-- DIE-CUT — Lechon Belly Box (LBR)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-044', description = 'LECHON BELLY - 9 x 6 x 5 inches',        unit_cost = 11.00 WHERE product_code = 'LBR00S';
UPDATE products SET item_code = 'RBM-012', description = 'LECHON BELLY - 13 x 8 x 6 inches',       unit_cost = 16.50 WHERE product_code = 'LBR00M';
UPDATE products SET item_code = 'RBM-125', description = 'LECHON BELLY LARGE - 16 x 8 x 7 inches', unit_cost = 18.50 WHERE product_code = 'LBR00L';

-- ═══════════════════════════════════════════════════════════════════════
-- DIE-CUT — Conchinillo Box (CCL) — set components; unit_cost 0 for Bottom
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-085-1', description = 'CONCHINILLO TOP - 24 x 16 x 6 inches', unit_cost = 34.00 WHERE product_code = 'CCLT01';
UPDATE products SET item_code = 'RBM-085-2', description = 'CONCHINILLO BOTTOM',                    unit_cost = 0.00  WHERE product_code = 'CCLB01';

-- ═══════════════════════════════════════════════════════════════════════
-- DIE-CUT — Lechon Box / RSC (LHNB)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-119', description = 'RSC LECHON BOX | C#200 | PLAIN | 920 x 254 x 254 mm', unit_cost = 28.25 WHERE product_code = 'LHNB01';

-- ═══════════════════════════════════════════════════════════════════════
-- DIE-CUT — Bilao Box (BB) — set components; stored in Balagtas (wh3)
-- ═══════════════════════════════════════════════════════════════════════
UPDATE products SET item_code = 'RBM-123-1', description = 'BILAO BOX - 267 x 267 x 51 mm', unit_cost = 8.17  WHERE product_code = 'BB010T';
UPDATE products SET item_code = 'RBM-123-2',                                                  unit_cost = 0.00  WHERE product_code = 'BB010B';
UPDATE products SET item_code = 'RBM-124-1', description = 'BILAO BOX - 318 x 318 x 51 mm', unit_cost = 12.20 WHERE product_code = 'BB012T';
UPDATE products SET item_code = 'RBM-124-2'                                                               WHERE product_code = 'BB012B';
