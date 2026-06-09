-- V43: Suppliers master table and PO year counter for auto-generated PO numbers.

-- ── Suppliers ────────────────────────────────────────────────────────────────

CREATE TABLE suppliers (
    id               BIGSERIAL       PRIMARY KEY,
    name             VARCHAR(100)    NOT NULL,
    address          TEXT,
    contact_number   VARCHAR(30),
    contact_person   VARCHAR(100),
    payment_terms    VARCHAR(50),
    notes            TEXT,
    is_active        BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE INDEX idx_suppliers_name ON suppliers(name);

-- ── PO year counter ──────────────────────────────────────────────────────────
-- One row per calendar year. The Java service locks this row with SELECT FOR UPDATE
-- before incrementing, preventing race conditions on concurrent PO creates.
-- Format produced: PO-MMDDYY-XXXXX  (e.g. PO-060326-00087 = 87th PO of 2026, created June 3)

CREATE TABLE po_year_counter (
    year         INTEGER     NOT NULL,
    last_number  INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_po_year_counter PRIMARY KEY (year)
);

-- Seed the current year so the first PO create does not need to INSERT.
INSERT INTO po_year_counter (year, last_number)
VALUES (EXTRACT(YEAR FROM now())::INTEGER, 0)
ON CONFLICT (year) DO NOTHING;
