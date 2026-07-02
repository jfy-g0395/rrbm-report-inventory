-- V90: widen users.chk_role to match the application's UserController.VALID_ROLES.
--
-- The CHECK constraint only listed SUPER_ADMIN, ADMIN, ADMINISTRATOR, ACCOUNTING,
-- STANDARD_USER. It was never widened when ACCOUNTING_PLUS was introduced (so assigning
-- Accounting+ silently failed at the DB), and it does not include the new REJECT_MANAGEMENT
-- role. Re-create the constraint with the full, current role set.

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_role;
ALTER TABLE users ADD CONSTRAINT chk_role
    CHECK (role IN ('SUPER_ADMIN','ADMIN','ADMINISTRATOR','ACCOUNTING','ACCOUNTING_PLUS','REJECT_MANAGEMENT','STANDARD_USER'));
