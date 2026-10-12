package com.aicontent.platform.job;

/**
 * Thrown by a {@link JobHandler} when the job could not even start (e.g. a lock was busy). Nothing is wrong
 * with the job itself, so the worker puts it back without consuming {@code retry_count}; only
 * {@code defer_count} grows, up to {@code app.worker.max-defers}.
 */
public class JobDeferredException extends RuntimeException {

    private final String errorCode;

    public JobDeferredException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
