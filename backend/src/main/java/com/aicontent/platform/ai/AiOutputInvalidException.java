package com.aicontent.platform.ai;

import java.util.List;

/** The model answered, but never with JSON that passes the schema within the allowed attempts. */
public class AiOutputInvalidException extends RuntimeException {

    private final List<String> errors;

    public AiOutputInvalidException(String message, List<String> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
