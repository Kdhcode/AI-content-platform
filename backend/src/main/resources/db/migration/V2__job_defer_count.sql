ALTER TABLE async_job
    ADD COLUMN defer_count INTEGER NOT NULL DEFAULT 0 CHECK (defer_count >= 0);
COMMENT ON COLUMN async_job.defer_count IS
    'Times the job was put back without running (e.g. LOCK_TIMEOUT). Separate from retry_count, which counts real failures.';
