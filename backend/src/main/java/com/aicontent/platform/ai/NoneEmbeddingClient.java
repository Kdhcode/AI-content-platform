package com.aicontent.platform.ai;

/**
 * Embedding twin of {@link NoneAiClient}. Kept as a separate class on purpose: a single object implementing both
 * interfaces would make Spring see two candidates for each injection point (NoUniqueBeanDefinitionException).
 */
public class NoneEmbeddingClient implements EmbeddingClient {

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public EmbeddingResult embed(String text) {
        throw notConfigured();
    }

    static AiException notConfigured() {
        return new AiException(AiException.NOT_CONFIGURED,
                "AI provider is not configured (app.ai.provider=none). Choosing a provider is an open item.", false);
    }
}
