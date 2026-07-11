-- ============================================================================
-- V99 — Employee 201 records (HR module)
-- ============================================================================
-- Registers company employees for record-keeping: personal info, education,
-- previous employment, a 2x2 photo, compensation & benefits, and an append-only
-- milestone timeline (salary/position/status changes, memos, addendums, notes).
-- Distinct from the existing `users` table (system logins) — this is HR data.
--
-- Money columns use numeric(13,5) to match the system-wide 5-decimal precision (V96).
-- NOTE: numbered V99 to sit above Phase A's V98 (resellers). Phase A (V98) must be
-- applied before this migration on any shared/live database.
-- ============================================================================

CREATE TABLE employees (
    id                  BIGSERIAL PRIMARY KEY,
    employee_code       VARCHAR(20) UNIQUE,                 -- EMP-YYYY-NNNN, auto
    -- Personal information
    last_name           VARCHAR(80)  NOT NULL,
    first_name          VARCHAR(80)  NOT NULL,
    middle_name         VARCHAR(80),
    maiden_name         VARCHAR(80),
    birthdate           DATE         NOT NULL,              -- age computed in responses, never stored
    nationality         VARCHAR(50),
    civil_status        VARCHAR(20),
    gender              VARCHAR(20),
    position            VARCHAR(100) NOT NULL,
    date_of_employment  DATE         NOT NULL,
    email               VARCHAR(150),
    spouse_name         VARCHAR(150),                       -- shown only if civil_status = MARRIED
    contact_number      VARCHAR(50)  NOT NULL,
    address             TEXT,
    sss_number          VARCHAR(30),
    pagibig_number      VARCHAR(30),
    philhealth_number   VARCHAR(30),
    photo               TEXT,                               -- base64 data-URL (2x2)
    -- Standing
    employment_status   VARCHAR(20)  NOT NULL DEFAULT 'PROBATIONARY',  -- PROBATIONARY/REGULAR/CONTRACTUAL
    probation_end_date  DATE,
    daily_wage          NUMERIC(13,5),
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',        -- ACTIVE/RESIGNED/TERMINATED
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          BIGINT       REFERENCES users(id)
);

CREATE TABLE employee_education (
    id             BIGSERIAL PRIMARY KEY,
    employee_id    BIGINT      NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    level          VARCHAR(20) NOT NULL,   -- PRIMARY/SECONDARY/TERTIARY/VOCATIONAL/GRADUATE
    school_name    VARCHAR(150),
    year_graduated VARCHAR(10)
);
CREATE INDEX idx_emp_education_emp ON employee_education(employee_id);

CREATE TABLE employee_work_history (
    id            BIGSERIAL PRIMARY KEY,
    employee_id   BIGINT       NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    employer_name VARCHAR(150),
    year_started  VARCHAR(10),
    year_ended    VARCHAR(10),
    position      VARCHAR(100)
);
CREATE INDEX idx_emp_work_emp ON employee_work_history(employee_id);

CREATE TABLE benefit_types (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(80) UNIQUE NOT NULL,
    is_government BOOLEAN DEFAULT FALSE,
    active        BOOLEAN DEFAULT TRUE
);
INSERT INTO benefit_types (name, is_government) VALUES
    ('SSS', TRUE), ('PhilHealth', TRUE), ('Pag-IBIG', TRUE), ('HMO', FALSE), ('Food Allowance', FALSE);

CREATE TABLE employee_benefits (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT        NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    benefit_type_id BIGINT        NOT NULL REFERENCES benefit_types(id),
    amount          NUMERIC(13,5),
    notes           VARCHAR(255),
    UNIQUE (employee_id, benefit_type_id)
);

CREATE TABLE employee_events (          -- append-only milestone timeline
    id          BIGSERIAL PRIMARY KEY,
    employee_id BIGINT      NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    event_type  VARCHAR(30) NOT NULL,   -- SALARY_CHANGE/POSITION_CHANGE/STATUS_CHANGE/MEMO/ADDENDUM/NOTE
    event_date  DATE        NOT NULL DEFAULT CURRENT_DATE,
    old_value   VARCHAR(150),
    new_value   VARCHAR(150),
    details     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  BIGINT      REFERENCES users(id)
);
CREATE INDEX idx_emp_events_emp ON employee_events(employee_id);
