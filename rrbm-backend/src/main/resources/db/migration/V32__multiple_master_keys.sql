-- V32: Support up to 3 simultaneous active master keys.
-- Adds label (user-friendly name) and is_active flag to master_keys table.
-- Validation now checks ALL active keys rather than just the latest one.

ALTER TABLE master_keys ADD COLUMN IF NOT EXISTS label     VARCHAR(50);
ALTER TABLE master_keys ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
