-- V35: Convert per-day order ID counters to a single global counter.
-- Receipt/order numbers will now be continuous across days and never reset at midnight.
--
-- Migration logic:
--   1. Seed the GLOBAL row with the highest counter value seen across all per-day rows
--      (safe on fresh install — COALESCE returns 0 when no rows exist).
--   2. Delete the old per-day rows (code will only look for key = 'GLOBAL' going forward).

INSERT INTO order_id_counter (date_key, last_number)
SELECT 'GLOBAL', COALESCE(MAX(last_number), 0)
FROM (SELECT last_number FROM order_id_counter WHERE date_key != 'GLOBAL') AS existing_rows;

DELETE FROM order_id_counter WHERE date_key != 'GLOBAL';
