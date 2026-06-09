-- V50: Add ACCOUNTING to the users.chk_role constraint
-- ─────────────────────────────────────────────────────────────────────────────
-- The UserController.VALID_ROLES list has included ACCOUNTING since the initial
-- build, but V11 forgot to add it to the DB constraint.  Any attempt to INSERT
-- or UPDATE a user with role='ACCOUNTING' currently fails with a check-constraint
-- violation.  This migration brings the DB constraint in line with the app code.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_role;

ALTER TABLE users ADD CONSTRAINT chk_role
    CHECK (role IN (
        'SUPER_ADMIN',
        'ADMIN',
        'ADMINISTRATOR',
        'ACCOUNTING',
        'STAFF',
        'STANDARD_USER'
    ));
