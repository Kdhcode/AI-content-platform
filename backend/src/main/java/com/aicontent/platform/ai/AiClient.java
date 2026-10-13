package com.aicontent.platform.ai;

/**
 * LLM boundary. A real provider (OPEN ITEM) implements this and is selected by {@code app.ai.provider}; nothing
 * outside this package knows which vendor is used. Implementations must throw {@link AiException} for provider
 * failures and must NOT validate or interpret the answer - that is {@link StructuredOutputService}'s job.
 */
public interface AiClient {

    String provider();

    /** Model this client requests, recorded on failed calls (no response, so no reported model). Null when unknown. */
    default String model() {
        return null;
    }

    AiCompletion complete(AiRequest request) throws AiException;
}
