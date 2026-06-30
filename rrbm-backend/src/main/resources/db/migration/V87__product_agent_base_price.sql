-- V87: Per-product agent base price.
-- Additive & reversible: one nullable column. The agent's over price (commission) per unit is
-- unitPrice - agent_base_price; this stores the auto-fill default, managed by Accounting+/admin.
-- NULL = no default; the order encoder fills base/over price manually (existing behavior).
ALTER TABLE products ADD COLUMN IF NOT EXISTS agent_base_price NUMERIC(10,2);
