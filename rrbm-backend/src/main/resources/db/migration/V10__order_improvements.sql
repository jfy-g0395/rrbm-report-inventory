-- V10: Order improvements — new source types, COD payment, order_type, address

-- Expand source options: add RESELLER, DISTRIBUTOR
ALTER TABLE orders DROP CONSTRAINT IF EXISTS chk_source;
ALTER TABLE orders ADD CONSTRAINT chk_source
    CHECK (source IN ('WALK_IN','AGENT','ECOMMERCE','FACEBOOK_PAGE','RESELLER','DISTRIBUTOR'));

-- Expand payment options: add COD
ALTER TABLE orders DROP CONSTRAINT IF EXISTS chk_payment;
ALTER TABLE orders ADD CONSTRAINT chk_payment
    CHECK (payment_mode IN ('CASH','GCASH','PAYMAYA','BANK_TRANSFER','BANK_DEPOSIT','ONLINE','COD'));

-- Add order_type (fulfilment method)
ALTER TABLE orders ADD COLUMN IF NOT EXISTS order_type VARCHAR(30) DEFAULT 'STANDARD';

-- Add optional delivery address / pickup location
ALTER TABLE orders ADD COLUMN IF NOT EXISTS address TEXT;
