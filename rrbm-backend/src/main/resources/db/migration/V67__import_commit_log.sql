CREATE TABLE IF NOT EXISTS import_commit_log (
    id            BIGSERIAL PRIMARY KEY,
    import_ref    VARCHAR(255) NOT NULL,
    committed_by  BIGINT NOT NULL REFERENCES users(id),
    committed_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    result_json   TEXT NOT NULL,
    batch_date    DATE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_import_commit_log_import_ref ON import_commit_log(import_ref);
CREATE INDEX IF NOT EXISTS idx_import_commit_log_committed_at ON import_commit_log(committed_at);
