-- C-8 security fix: remove plaintext credential storage (CWE-256/312).
-- Passwords and security keys must only be stored as bcrypt hashes.
ALTER TABLE users DROP COLUMN IF EXISTS password_plain;
ALTER TABLE users DROP COLUMN IF EXISTS security_key_plain;
