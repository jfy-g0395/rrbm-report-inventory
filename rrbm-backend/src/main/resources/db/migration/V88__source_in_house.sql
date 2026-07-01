-- V88: allow IN_HOUSE as a valid order source.
--
-- IN_HOUSE was already accepted by the application layer (OrderService.VALID_SOURCES,
-- ImportController.VALID_SOURCES) and offered in the UI source dropdowns, but the
-- orders.chk_source CHECK constraint (last set in V10) never included it. As a result an
-- in-house order passed app validation and then failed at INSERT with a raw
-- "violates check constraint chk_source" error. This migration widens the constraint to
-- include IN_HOUSE so in-house orders can be recorded.

ALTER TABLE orders DROP CONSTRAINT IF EXISTS chk_source;
ALTER TABLE orders ADD CONSTRAINT chk_source
    CHECK (source IN ('WALK_IN','IN_HOUSE','AGENT','ECOMMERCE','FACEBOOK_PAGE','RESELLER','DISTRIBUTOR'));
