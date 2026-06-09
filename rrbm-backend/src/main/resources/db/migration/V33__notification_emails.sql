-- V33: Email addresses to receive low-stock alert notifications.
-- Managed by Super Admin / Administrator in the Settings page.

CREATE TABLE IF NOT EXISTS notification_emails (
    id         BIGSERIAL    PRIMARY KEY,
    email      VARCHAR(120) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(120)
);
