package com.aicontent.platform.ai;

import com.aicontent.platform.config.AppProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Startup checks for the configured AI provider. The context does not start (so no job runs) when:
 * <ul>
 *   <li>the provider is {@code openai} and the chat or embedding model has no price in {@code app.ai.pricing}: every
 *       paid call would be recorded with a NULL cost and budget limits could not count it;</li>
 *   <li>the database already holds embeddings from the other kind of provider: stub vectors and real model vectors
 *       live in different spaces, so mixing them makes similarity search meaningless. Evidence is the provider of
 *       successful EMBEDDING rows in {@code ai_log} (a database without such rows is accepted).</li>
 * </ul>
 * The second check can be switched off with {@code app.ai.embedding-guard-enabled=false} (the test profile does, because
 * all integration tests share one database).
 */
@Component
public class AiProviderGuard {

    public AiProviderGuard(AppProperties app, OpenAiProperties openAi, AiCostCalculator costs, JdbcClient jdbc,
                           @Value("${app.ai.embedding-guard-enabled:true}") boolean embeddingGuard) {
        String provider = app.ai().provider() == null ? "" : app.ai().provider().trim().toLowerCase();
        List<String> problems = new ArrayList<>();
        if ("openai".equals(provider)) {
            problems.addAll(missingPrices(costs, openAi.chatModel(), openAi.embeddingModel()));
        }
        if (embeddingGuard) {
            problems.addAll(mixedEmbeddings(provider, embeddingProviders(jdbc)));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("app.ai.provider=" + provider + " refused: " + String.join("; ", problems));
        }
    }

    static List<String> missingPrices(AiCostCalculator costs, String chatModel, String embeddingModel) {
        List<String> problems = new ArrayList<>();
        if (costs.estimate(chatModel, 1000, 1000) == null) {
            problems.add("no app.ai.pricing entry for chat model " + chatModel + " (use the live profile or set a price)");
        }
        if (costs.estimate(embeddingModel, 1000, 0) == null) {
            problems.add("no app.ai.pricing entry for embedding model " + embeddingModel + " (use the live profile or set a price)");
        }
        return problems;
    }

    public static List<String> mixedEmbeddings(String provider, Set<String> existing) {
        if ("stub".equals(provider) && existing.contains("openai")) {
            return List.of("this database already has openai embeddings; stub vectors must not be added (use the stub DB)");
        }
        if ("openai".equals(provider) && existing.contains("stub")) {
            return List.of("this database already has stub embeddings; real vectors must not be mixed in (use a real-AI DB)");
        }
        return List.of();
    }

    /** Providers of successful embedding calls recorded in this database; empty before the schema exists. */
    public static Set<String> embeddingProviders(JdbcClient jdbc) {
        Boolean hasTable = jdbc.sql("SELECT to_regclass('public.ai_log') IS NOT NULL").query(Boolean.class).single();
        if (!Boolean.TRUE.equals(hasTable)) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT DISTINCT lower(provider) FROM ai_log
                WHERE call_type = 'EMBEDDING' AND success AND provider IS NOT NULL""").query(String.class).list());
    }
}
