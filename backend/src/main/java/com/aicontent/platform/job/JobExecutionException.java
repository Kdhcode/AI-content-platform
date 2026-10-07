package com.aicontent.platform.job;

/**
 * Thrown by a {@link JobHandler} to report a failure together with the retry decision.
 * Any other exception escaping a handler is treated as retryable (code UNEXPECTED_ERROR) until
 * {@code max_retries} is exhausted.
 */
public class JobExecutionException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public JobExecutionException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public JobExecutionException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
