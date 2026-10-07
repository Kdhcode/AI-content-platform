package com.aicontent.platform.ai;

import com.aicontent.platform.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Selects none, development stub or OpenAI clients from {@code app.ai.provider}.
 * Additional vendors remain isolated behind {@link AiClient} / {@link EmbeddingClient}.
 */
@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class AiConfig {

    @Bean
    AiClient aiClient(AppProperties props, ObjectMapper mapper, OpenAiProperties openai) {
        return switch (props.ai().provider().toLowerCase()) {
            case "stub" -> new StubAiClient(mapper);
            case "none" -> new NoneAiClient();
            case "openai" -> new OpenAiClient(openai, mapper);
            default -> throw new IllegalStateException("unknown app.ai.provider '" + props.ai().provider()
                    + "' (supported: none, stub, openai)");
        };
    }

    @Bean
    EmbeddingClient embeddingClient(AppProperties props, ObjectMapper mapper, OpenAiProperties openai) {
        return switch (props.ai().provider().toLowerCase()) {
            case "stub" -> new StubEmbeddingClient(props.ai().embeddingDimension());
            case "none" -> new NoneEmbeddingClient();
            case "openai" -> new OpenAiEmbeddingClient(openai, mapper, props.ai().embeddingDimension());
            default -> throw new IllegalStateException("unknown app.ai.provider '" + props.ai().provider() + "'");
        };
    }
}
