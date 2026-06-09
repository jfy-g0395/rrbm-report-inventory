-- V30: Add must_change_password flag to users.
-- When true, the user is forced to set their own password on next login.
-- Set automatically when a Super Admin creates an account or resets a password.
-- Cleared automatically when the user successfully changes their own password.

ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
