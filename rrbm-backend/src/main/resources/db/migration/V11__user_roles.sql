-- V11: Expand user roles and add employee_id column

-- Add ADMINISTRATOR and STANDARD_USER alongside existing SUPER_ADMIN, ADMIN, STAFF
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_role;
ALTER TABLE users ADD CONSTRAINT chk_role
    CHECK (role IN ('SUPER_ADMIN','ADMIN','ADMINISTRATOR','STAFF','STANDARD_USER'));

-- Optional employee reference ID shown in Activity Log
ALTER TABLE users ADD COLUMN IF NOT EXISTS employee_id VARCHAR(20);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_employee_id ON users(employee_id) WHERE employee_id IS NOT NULL;
