package com.aicontent.platform.ai;

/** Default while no provider is chosen: every call fails loudly instead of inventing output. */
public class NoneAiClient implements AiClient {

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public AiCompletion complete(AiRequest request) {
        throw NoneEmbeddingClient.notConfigured();
    }
}
