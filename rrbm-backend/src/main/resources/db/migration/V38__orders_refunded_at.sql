-- V38: Add refunded_at timestamp to orders
-- Marks orders that have had at least one refund issued against them.
-- Set atomically inside TransactionService.recordRefund() alongside the ledger write.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS refunded_at TIMESTAMPTZ;
