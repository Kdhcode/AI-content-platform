package com.aicontent.platform.job;

import java.util.Map;

/**
 * One implementation per {@link JobType}. Handlers must be idempotent: a job can run again after a
 * retry or after a worker crash (stale RUNNING jobs are re-queued).
 */
public interface JobHandler {

    JobType type();

    /** @return optional result stored in {@code async_job.result}; may be null. */
    Map<String, Object> handle(JobContext context) throws Exception;

    /**
     * Called once when the job reaches FAILED (non-retryable error or retries exhausted) so the domain
     * object can reflect the failure (e.g. article status FAILED). Must not throw.
     */
    default void onFinalFailure(AsyncJob job, String errorCode, String errorMessage) {}
}
