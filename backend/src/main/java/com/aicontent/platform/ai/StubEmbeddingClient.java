package com.aicontent.platform.ai;

/** Dev/test provider ({@code app.ai.provider=stub}); see {@link StubEmbeddingMath}. */
public class StubEmbeddingClient implements EmbeddingClient {

    private static final String MODEL = "stub-embedding";

    private final int dimension;

    public StubEmbeddingClient(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public EmbeddingResult embed(String text) {
        return new EmbeddingResult(StubEmbeddingMath.embed(text, dimension), MODEL, null);
    }
}
