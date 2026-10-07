package com.aicontent.platform.news;

/** Failure while talking to a news source; {@link Kind} decides whether retrying makes sense. */
public class SourceFetchException extends Exception {

    public enum Kind {
        TIMEOUT(true),
        NETWORK(true),
        RATE_LIMITED(true),
        SERVER_ERROR(true),
        CLIENT_ERROR(false),
        PARSE_ERROR(false),
        UNSUPPORTED(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;

    public SourceFetchException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public SourceFetchException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return kind.retryable();
    }
}
