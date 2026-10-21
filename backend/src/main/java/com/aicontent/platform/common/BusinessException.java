package com.aicontent.platform.common;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /** @param details returned to the client as {@code error.details} (e.g. the current version on a conflict) */
    public BusinessException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object details() {
        return details;
    }
}
