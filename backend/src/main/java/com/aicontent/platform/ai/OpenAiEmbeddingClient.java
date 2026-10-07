package com.aicontent.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class OpenAiEmbeddingClient implements EmbeddingClient {
    private final OpenAiProperties props;
    private final ObjectMapper mapper;
    private final OpenAiTransport transport;
    private final int dimensions;

    public OpenAiEmbeddingClient(OpenAiProperties props, ObjectMapper mapper, int dimensions) {
        this.props = props;
        this.mapper = mapper;
        this.transport = new OpenAiTransport(props, mapper);
        this.dimensions = dimensions;
        if (dimensions != 1536) {
            throw new IllegalStateException("Phase 1 pgvector schema requires 1536 embedding dimensions");
        }
    }

    @Override public String provider() { return "openai"; }

    @Override public EmbeddingResult embed(String text) {
        var body = mapper.createObjectNode();
        body.put("model", props.embeddingModel());
        body.put("input", text);
        body.put("encoding_format", "float");
        body.put("dimensions", dimensions);
        var response = transport.post("/embeddings", body);
        var data = response.path("data");
        var embedding = data.path(0).path("embedding");
        if (!data.isArray() || data.size() != 1 || !embedding.isArray() || embedding.size() != dimensions) {
            throw OpenAiTransport.invalid("embedding dimensions do not match " + dimensions);
        }
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            if (!embedding.get(i).isNumber()) {
                throw OpenAiTransport.invalid("embedding contains a non-numeric value");
            }
            vector[i] = embedding.get(i).floatValue();
            if (!Float.isFinite(vector[i])) {
                throw OpenAiTransport.invalid("embedding contains a non-finite value");
            }
        }
        return new EmbeddingResult(vector, response.path("model").asText(props.embeddingModel()),
                OpenAiTransport.tokens(response.path("usage"), "total_tokens"));
    }
}
