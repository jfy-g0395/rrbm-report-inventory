-- ========================================================================
-- RRBM Backend — Seed Initial Data
-- Migration V2: Insert default user and settings
-- ========================================================================

-- ========================================================================
-- Insert Super Admin user
-- Email: admin@rrbm.com
-- Password: ChangeMe123!
-- (BCrypt hash below)
-- ========================================================================
INSERT INTO users (email, password_hash, full_name, role, status) VALUES
  ('admin@rrbm.com',
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'Ryan Reyes',
   'SUPER_ADMIN',
   'ACTIVE');

-- ========================================================================
-- Insert default system settings
-- ========================================================================
INSERT INTO settings (key_name, value, description) VALUES
  ('company_name',              'RRBM Packaging Supplies and Trading', 'Company display name'),
  ('daily_reset_time',          '00:00',                                'Daily reset time (HH:mm)'),
  ('master_key_hash',           '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'BCrypt hash of master cancellation key'),
  ('threshold_hot_critical',    '3000',                                 'Critical stock for HOT items (pcs)'),
  ('threshold_hot_low',         '5000',                                 'Low warning for HOT items (pcs)'),
  ('threshold_sel_critical',    '1000',                                 'Critical stock for SELLING/SLOW items (pcs)');