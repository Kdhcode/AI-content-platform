package com.aicontent.platform.ai;

/** Provider-level failure (not configured, timeout, rate limit, 5xx). {@code retryable} drives the job retry policy. */
public class AiException extends RuntimeException {

    public static final String NOT_CONFIGURED = "AI_PROVIDER_NOT_CONFIGURED";

    private final String code;
    private final boolean retryable;

    public AiException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public AiException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
