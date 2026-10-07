package com.aicontent.platform.ai;

public record EmbeddingResult(float[] vector, String model, Integer inputTokens) {}
