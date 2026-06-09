-- V20: Admin Security Key + Supplier Name on Delivery Log
--
-- 1. admin_security_key (BCrypt hash) on users — NULL until Super Admin assigns one.
--    Used to authorise Cancel Order, Issue Refund, Post-Close Void.
-- 2. supplier_name on delivery_log — identifies the supplying vendor per receipt.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS admin_security_key VARCHAR(255) DEFAULT NULL;

ALTER TABLE delivery_log
    ADD COLUMN IF NOT EXISTS supplier_name VARCHAR(200) NOT NULL DEFAULT 'Unknown';
