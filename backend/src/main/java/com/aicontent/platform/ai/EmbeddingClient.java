package com.aicontent.platform.ai;

/** Embedding boundary; the vector length must equal {@code app.ai.embedding-dimension} (checked by the caller). */
public interface EmbeddingClient {

    String provider();

    EmbeddingResult embed(String text) throws AiException;
}
