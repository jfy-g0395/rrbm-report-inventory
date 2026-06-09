-- V12: Extended employee profile fields

ALTER TABLE users ADD COLUMN IF NOT EXISTS username       VARCHAR(80);
ALTER TABLE users ADD COLUMN IF NOT EXISTS designation    VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS birthdate      DATE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS address        TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS contact_number VARCHAR(30);
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_image  TEXT;   -- base64 data-URL

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username
    ON users(username) WHERE username IS NOT NULL;
