package com.aicontent.platform.ai;

import com.aicontent.platform.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the AI providers from {@code app.ai.provider}. PLUG-IN POINT for a real vendor (OPEN ITEM): add a case that
 * returns your {@link AiClient} / {@link EmbeddingClient}; keep the vendor SDK/HTTP code inside that implementation.
 */
@Configuration
public class AiConfig {

    @Bean
    AiClient aiClient(AppProperties props, ObjectMapper mapper) {
        return switch (props.ai().provider().toLowerCase()) {
            case "stub" -> new StubAiClient(mapper);
            case "none" -> new NoneAiClient();
            default -> throw new IllegalStateException("unknown app.ai.provider '" + props.ai().provider()
                    + "' (supported: none, stub)");
        };
    }

    @Bean
    EmbeddingClient embeddingClient(AppProperties props) {
        return switch (props.ai().provider().toLowerCase()) {
            case "stub" -> new StubEmbeddingClient(props.ai().embeddingDimension());
            case "none" -> new NoneEmbeddingClient();
            default -> throw new IllegalStateException("unknown app.ai.provider '" + props.ai().provider() + "'");
        };
    }
}
