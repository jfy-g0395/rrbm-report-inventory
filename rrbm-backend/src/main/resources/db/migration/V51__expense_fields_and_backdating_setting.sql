-- V51: Add payment_method, notes, reference_number, status to expenses;
--      seed the backdating-window setting.

ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS payment_method    VARCHAR(30),
    ADD COLUMN IF NOT EXISTS notes             TEXT,
    ADD COLUMN IF NOT EXISTS reference_number  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS status            VARCHAR(20) NOT NULL DEFAULT 'COMPLETED';

INSERT INTO settings (key_name, value, description)
VALUES ('expense_backdating_days', '7',
        'Max days in the past an expense date may be backdated')
ON CONFLICT (key_name) DO NOTHING;
