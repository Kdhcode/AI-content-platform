package com.aicontent.platform.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every tunable of Phase 1 lives here so that no threshold, model name or provider is hard-coded
 * in service code. Thresholds are starting points only; they are expected to be tuned against the
 * evaluation set (see docs/DECISIONS.md).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue Worker worker,
        @DefaultValue News news,
        @DefaultValue Ai ai,
        @DefaultValue Classifier classifier,
        @DefaultValue Prompts prompts,
        @DefaultValue Admin admin,
        @DefaultValue Lock lock) {

    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2000") long pollIntervalMs,
            @DefaultValue("30000") long recoveryIntervalMs,
            @DefaultValue("5000") long heartbeatIntervalMs,
            @DefaultValue("4") int concurrency,
            @DefaultValue("300") long staleAfterSeconds,
            @DefaultValue("3") int defaultMaxRetries,
            @DefaultValue("5") long retryBaseDelaySeconds,
            @DefaultValue("600") long retryMaxDelaySeconds,
            @DefaultValue("20") long shutdownWaitSeconds) {}

    public record News(
            @DefaultValue("true") boolean schedulerEnabled,
            @DefaultValue("30000") long schedulerIntervalMs,
            @DefaultValue("7") int titleDedupeWindowDays,
            @DefaultValue("200") int maxArticlesPerRun,
            @DefaultValue("3") int degradedAfterFailures,
            @DefaultValue("10") int httpTimeoutSeconds,
            @DefaultValue List<SourceDefinition> sources) {}

    /** Declarative news source, upserted by name at start-up (no source is configured by default). */
    public record SourceDefinition(
            String name,
            String type,
            String baseUrl,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("600") int intervalSeconds,
            @DefaultValue java.util.Map<String, String> config) {}

    public record Ai(
            /** none | stub. "none" fails every AI call with AI_PROVIDER_NOT_CONFIGURED (real provider is an OPEN ITEM). */
            @DefaultValue("none") String provider,
            @DefaultValue("1536") int embeddingDimension,
            @DefaultValue("2") int analysisMaxAttempts,
            @DefaultValue("2") int classifierMaxAttempts,
            @DefaultValue("8000") int maxAnalysisTextChars) {}

    public record Classifier(
            @DefaultValue("5") int candidateLimit,
            @DefaultValue("0.60") double candidateMinSimilarity,
            @DefaultValue("30") int candidateWindowDays,
            @DefaultValue("ACTIVE") List<String> candidateIssueStatuses,
            @DefaultValue("0.85") double sameIssueMinConfidence,
            @DefaultValue("0.70") double newIssueMinConfidence,
            @DefaultValue("300") long lockWaitSeconds) {}

    public record Prompts(
            @DefaultValue("v1") String articleAnalysisVersion,
            @DefaultValue("v1") String issueClassifierVersion) {}

    public record Admin(
            @DefaultValue("") String bootstrapUsername,
            @DefaultValue("") String bootstrapPassword) {}

    public record Lock(
            /** true => Redis lock; false => in-JVM lock (correct for a single instance only). */
            @DefaultValue("false") boolean redisEnabled,
            @DefaultValue("300") long ttlSeconds) {}
}
