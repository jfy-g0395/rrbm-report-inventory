-- V3__seed_products.sql
-- Seeds the products table with RRBM's pizza box and packaging products.
-- Matches the actual schema: sku, name, category, selling_tag, unit_price, unit_cost,
-- threshold_critical, threshold_low, stock_wh1, stock_wh2, stock_wh3, active

INSERT INTO products (sku, name, category, selling_tag, unit_price, unit_cost, threshold_critical, threshold_low, stock_wh1, stock_wh2, stock_wh3, active) VALUES
-- Pizza Boxes (main product line)
('PB-08B', 'Pizza Box 8" Brown',        'Pizza Box',  'SELLING',  8.00,  5.00, 100, 200,  500, 0, 0, true),
('PB-08W', 'Pizza Box 8" White',        'Pizza Box',  'SELLING',  9.00,  6.00, 100, 200,  500, 0, 0, true),
('PB-10B', 'Pizza Box 10" Brown',       'Pizza Box',  'HOT',     12.00,  8.00, 200, 400, 1000, 0, 0, true),
('PB-10W', 'Pizza Box 10" White',       'Pizza Box',  'HOT',     13.00,  9.00, 200, 400, 1000, 0, 0, true),
('PB-12B', 'Pizza Box 12" Brown',       'Pizza Box',  'HOT',     15.00, 10.00, 150, 300,  800, 0, 0, true),
('PB-12W', 'Pizza Box 12" White',       'Pizza Box',  'HOT',     16.00, 11.00, 150, 300,  800, 0, 0, true),
('PB-14B', 'Pizza Box 14" Brown',       'Pizza Box',  'SELLING', 18.00, 12.00, 100, 200,  600, 0, 0, true),
('PB-14W', 'Pizza Box 14" White',       'Pizza Box',  'SELLING', 19.00, 13.00, 100, 200,  600, 0, 0, true),
('PB-16B', 'Pizza Box 16" Brown',       'Pizza Box',  'SELLING', 22.00, 15.00,  80, 160,  400, 0, 0, true),
('PB-16W', 'Pizza Box 16" White',       'Pizza Box',  'SELLING', 23.00, 16.00,  80, 160,  400, 0, 0, true),
('PB-18B', 'Pizza Box 18" Brown',       'Pizza Box',  'SLOW',    28.00, 19.00,  60, 120,  300, 0, 0, true),
('PB-18W', 'Pizza Box 18" White',       'Pizza Box',  'SLOW',    30.00, 20.00,  60, 120,  300, 0, 0, true),

-- Pastry Boxes
('PT-6B',  'Pastry Box 6x6x3 Brown',   'Pastry Box', 'SELLING',  6.00,  4.00,  80, 160,  400, 0, 0, true),
('PT-6W',  'Pastry Box 6x6x3 White',   'Pastry Box', 'SELLING',  7.00,  5.00,  80, 160,  400, 0, 0, true),
('PT-8B',  'Pastry Box 8x8x3 Brown',   'Pastry Box', 'SELLING',  8.00,  5.50,  60, 120,  300, 0, 0, true),
('PT-8W',  'Pastry Box 8x8x3 White',   'Pastry Box', 'SELLING',  9.00,  6.50,  60, 120,  300, 0, 0, true),
('PT-10B', 'Pastry Box 10x10x4 Brown',  'Pastry Box', 'SLOW',    12.00,  8.00,  50, 100,  200, 0, 0, true),
('PT-10W', 'Pastry Box 10x10x4 White',  'Pastry Box', 'SLOW',    13.00,  9.00,  50, 100,  200, 0, 0, true),

-- Food Trays
('FT-S',   'Food Tray Small Brown',     'Food Tray',  'SELLING',  4.00,  2.50, 100, 200,  600, 0, 0, true),
('FT-M',   'Food Tray Medium Brown',    'Food Tray',  'SELLING',  5.50,  3.50, 100, 200,  500, 0, 0, true),
('FT-L',   'Food Tray Large Brown',     'Food Tray',  'SELLING',  7.00,  4.50,  80, 160,  400, 0, 0, true),

-- Packaging Supplies
('TP-2C',  'Packaging Tape 2" Clear',   'Supplies',   'SELLING', 35.00, 22.00,  50, 100,  200, 0, 0, true),
('TP-2B',  'Packaging Tape 2" Brown',   'Supplies',   'SELLING', 33.00, 20.00,  50, 100,  200, 0, 0, true),
('BW-12',  'Bubble Wrap 12" x 50m',     'Supplies',   'SLOW',   180.00, 120.00, 10,  20,   50, 0, 0, true),
('SF-18',  'Stretch Film 18" x 300m',   'Supplies',   'SLOW',   250.00, 170.00, 10,  20,   40, 0, 0, true);
