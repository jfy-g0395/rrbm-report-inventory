ALTER TABLE import_commit_log DROP CONSTRAINT IF EXISTS import_commit_log_committed_by_fkey;
ALTER TABLE import_commit_log ADD CONSTRAINT import_commit_log_committed_by_fkey
    FOREIGN KEY (committed_by) REFERENCES users(id) ON DELETE CASCADE;
