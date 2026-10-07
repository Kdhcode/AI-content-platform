package com.aicontent.platform.ai;

public record AiCompletion(String text, String model, Integer inputTokens, Integer outputTokens) {}
