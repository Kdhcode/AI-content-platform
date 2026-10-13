package com.aicontent.platform.job;

/**
 * Thrown by a {@link JobHandler} when the job could not even start (e.g. a lock was busy). Nothing is wrong
 * with the job itself, so the worker puts it back without consuming {@code retry_count}.
 *
 * <ul>
 *   <li>Counted (default): {@code defer_count} grows, up to {@code app.worker.max-defers}, then the job FAILS. For
 *       waits that should end on their own (a lock); the cap stops a job from looping forever when they do not.</li>
 *   <li>Not counted: the job only waits ({@code available_at} moves). For waits the operator chose on purpose, such
 *       as an AI budget or rate limit, which must not turn queued work into FAILED jobs while the limit holds.</li>
 * </ul>
 */
public class JobDeferredException extends RuntimeException {

    private final String errorCode;
    private final Long delaySeconds;
    private final boolean counted;

    /** Counted deferral after {@code app.worker.defer-delay-seconds}. */
    public JobDeferredException(String errorCode, String message) {
        this(errorCode, message, null, true);
    }

    /**
     * @param delaySeconds when the job is due again; null = {@code app.worker.defer-delay-seconds}
     * @param counted      whether this deferral uses {@code defer_count} / {@code max-defers}
     */
    public JobDeferredException(String errorCode, String message, Long delaySeconds, boolean counted) {
        super(message);
        this.errorCode = errorCode;
        this.delaySeconds = delaySeconds;
        this.counted = counted;
    }

    public String errorCode() {
        return errorCode;
    }

    /** @return the requested delay, or null for the configured default. */
    public Long delaySeconds() {
        return delaySeconds;
    }

    public boolean counted() {
        return counted;
    }
}
