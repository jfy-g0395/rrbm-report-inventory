-- V91: retire the REJECT_MANAGEMENT and ACCOUNTING_PLUS roles, converting their two
-- distinguishing capabilities into grantable page-access permissions:
--   - 'add-rejected-items'  → add/edit/delete manual rejected items (was REJECT_MANAGEMENT)
--   - 'void-cancel-orders'  → void/cancel/return orders            (was the ACCOUNTING role's power)
--
-- The capability checks (DailyReportController, OrderController) now read allowed_pages
-- instead of the role name. Existing ACCOUNTING users must therefore carry these keys or
-- they would silently lose abilities they have today. New accounting users get them via
-- ROLE_DEFAULT_PAGES; this migration backfills the existing ones. Purely additive (appends
-- keys to a JSON array) and idempotent.
--
-- Note: agent base pricing (the old ACCOUNTING_PLUS UI power) becomes SUPER_ADMIN + ADMINISTRATOR
-- only — enforced in the frontend, no data change needed here.
--
-- chk_role is intentionally left permissive (it still lists the retired roles from V90). The app
-- no longer offers them, so no row can acquire them; narrowing the constraint is unnecessary and
-- would add avoidable prod risk. No user currently holds either role.

-- Defensive: if any account somehow still holds a retired role, fold it into a base role so the
-- app can render it. Matches nothing in the current DB; kept for safety on out-of-band data.
UPDATE users SET role = 'ACCOUNTING'    WHERE role = 'ACCOUNTING_PLUS';
UPDATE users SET role = 'STANDARD_USER' WHERE role = 'REJECT_MANAGEMENT';

-- Backfill 'add-rejected-items' for accounting users that don't already have it.
UPDATE users
   SET allowed_pages = (allowed_pages::jsonb || '["add-rejected-items"]'::jsonb)::text
 WHERE role = 'ACCOUNTING'
   AND allowed_pages IS NOT NULL
   AND NOT (allowed_pages::jsonb @> '["add-rejected-items"]'::jsonb);

-- Backfill 'void-cancel-orders' for accounting users that don't already have it.
UPDATE users
   SET allowed_pages = (allowed_pages::jsonb || '["void-cancel-orders"]'::jsonb)::text
 WHERE role = 'ACCOUNTING'
   AND allowed_pages IS NOT NULL
   AND NOT (allowed_pages::jsonb @> '["void-cancel-orders"]'::jsonb);
