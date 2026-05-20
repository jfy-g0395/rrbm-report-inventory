-- Migration V8: master_keys table (ensures table exists if previous migration missed it)
CREATE TABLE IF NOT EXISTS master_keys (
    id BIGSERIAL PRIMARY KEY,
    key_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);
