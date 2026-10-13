package com.aicontent.platform.ai;

/** Embedding boundary; the vector length must equal {@code app.ai.embedding-dimension} (checked by the caller). */
public interface EmbeddingClient {

    String provider();

    /** Model this client requests, recorded on failed calls (no response, so no reported model). Null when unknown. */
    default String model() {
        return null;
    }

    EmbeddingResult embed(String text) throws AiException;
}
