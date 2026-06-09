-- V29: Add plaintext credential storage for Super Admin credential viewer
-- Populated going forward whenever a Super Admin sets or changes a user's
-- password or security key. Values set before this migration are unavailable.
-- Only returned via the SUPER_ADMIN-gated GET /api/users/{id}/credentials endpoint.

ALTER TABLE users ADD COLUMN IF NOT EXISTS password_plain     VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS security_key_plain VARCHAR(255);
