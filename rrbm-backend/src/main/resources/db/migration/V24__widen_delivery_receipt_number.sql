-- V24: Widen delivery_log.receipt_number from VARCHAR(10) to VARCHAR(30).
-- Root cause: column was set to VARCHAR(10) in V5 when receipt numbers were
-- auto-generated 6-digit codes. Validation was later widened to 2-20 chars
-- (Session 21) but the DB column was never updated to match. This causes any
-- DR number longer than 10 characters (e.g. 11-digit PO numbers) to be
-- rejected at the database level despite passing frontend/backend validation.
ALTER TABLE delivery_log ALTER COLUMN receipt_number TYPE VARCHAR(30);
