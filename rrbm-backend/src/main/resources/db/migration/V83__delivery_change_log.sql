-- V83: Delivery report change-log (audit trail of edits).
-- Additive & reversible: a single nullable TEXT column. No data is touched.
-- Stores a human-readable, append-only history of edits made to a delivery
-- report (who, what, when, and the reason) — rendered at the bottom of the
-- delivery detail view. Kept separate from `notes` (delivery remarks).
ALTER TABLE delivery_log ADD COLUMN IF NOT EXISTS change_log TEXT;
