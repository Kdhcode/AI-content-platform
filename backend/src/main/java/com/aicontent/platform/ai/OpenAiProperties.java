package com.aicontent.platform.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.ai.openai")
public record OpenAiProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://api.openai.com/v1") String baseUrl,
        @DefaultValue("gpt-4o-mini") String chatModel,
        @DefaultValue("text-embedding-3-small") String embeddingModel,
        @DefaultValue("60") int timeoutSeconds,
        @DefaultValue("4096") int maxCompletionTokens) {}
