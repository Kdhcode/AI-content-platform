package com.aicontent.platform.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
        @DefaultValue Lock lock,
        @DefaultValue Retention retention,
        @DefaultValue IssueLifecycle issueLifecycle) {

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
            /** Wait before a deferred job (e.g. LOCK_TIMEOUT) is due again; deferrals do not consume retries. */
            @DefaultValue("30") long deferDelaySeconds,
            @DefaultValue("20") int maxDefers,
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
            /** none | stub | openai. "none" fails every AI call with AI_PROVIDER_NOT_CONFIGURED. */
            @DefaultValue("none") String provider,
            @DefaultValue("1536") int embeddingDimension,
            @DefaultValue("2") int analysisMaxAttempts,
            @DefaultValue("2") int classifierMaxAttempts,
            @DefaultValue("8000") int maxAnalysisTextChars,
            /** Model name (or name prefix) -> price. Empty by default: prices change, so none is built in. */
            @DefaultValue Map<String, ModelPrice> pricing,
            /** Budget and rate limits (OPEN ITEM #10). Every limit is off by default. */
            @DefaultValue AiLimits limits) {}

    /**
     * Checked before every AI call (analysis, embedding, classification, including schema-retry attempts). When a limit
     * is reached the job waits instead of failing: until the next day for daily limits, about a minute for the rate
     * limit. Counts come from ai_log, so they are shared by all instances; calls already in flight can overshoot by up
     * to {@code app.worker.concurrency}.
     */
    public record AiLimits(
            /** USD per day, summed from ai_log.estimated_cost_usd. Unset = no budget. Unpriced calls count as 0. */
            BigDecimal dailyBudgetUsd,
            /** AI calls per day (failed calls count too). 0 = no limit. */
            @DefaultValue("0") int dailyMaxCalls,
            /** AI calls in any 60-second window. 0 = no limit. */
            @DefaultValue("0") int maxCallsPerMinute,
            /** Where "a day" starts and ends (IANA zone). */
            @DefaultValue("Asia/Seoul") String timeZone) {

        public boolean anyEnabled() {
            return dailyBudgetUsd != null || dailyMaxCalls > 0 || maxCallsPerMinute > 0;
        }
    }

    /** USD per one million tokens. Embedding models set the input price only. */
    public record ModelPrice(BigDecimal inputPerMillionUsd, BigDecimal outputPerMillionUsd) {}

    public record Classifier(
            @DefaultValue("5") int candidateLimit,
            @DefaultValue("0.60") double candidateMinSimilarity,
            @DefaultValue("30") int candidateWindowDays,
            @DefaultValue("ACTIVE") List<String> candidateIssueStatuses,
            @DefaultValue("0.85") double sameIssueMinConfidence,
            @DefaultValue("0.70") double newIssueMinConfidence,
            @DefaultValue("300") long lockWaitSeconds,
            /**
             * What the classification lock covers (OPEN ITEM #7). GLOBAL (default): one lock for everything, so two
             * articles about a brand-new event can never both create an issue. CATEGORY: one lock per analysed category,
             * so categories classify in parallel; two same-event articles analysed into different categories may then
             * create two issues (REVIEW/merge recovers it).
             */
            @DefaultValue("GLOBAL") String lockScope) {}

    public record Prompts(
            @DefaultValue("v2") String articleAnalysisVersion,
            @DefaultValue("v1") String issueClassifierVersion) {}

    public record Admin(
            @DefaultValue("") String bootstrapUsername,
            @DefaultValue("") String bootstrapPassword) {}

    public record Lock(
            /** true => Redis lock; false => in-JVM lock (correct for a single instance only). */
            @DefaultValue("false") boolean redisEnabled,
            @DefaultValue("300") long ttlSeconds) {}

    /**
     * Data retention (OPEN ITEM #8). The retention period itself is a policy decision, so nothing is removed by
     * default: 0 means "keep forever".
     */
    public record Retention(
            /** Days to keep ai_log.response (raw model output). Older rows keep their metadata; only the text is cleared. */
            @DefaultValue("0") int aiLogResponseDays,
            /** Rows cleared per statement; a run repeats statements until nothing is left (bounded per run). */
            @DefaultValue("1000") int batchSize,
            /** Delay between runs (read by the @Scheduled placeholder). */
            @DefaultValue("3600000") long intervalMs) {}

    /**
     * Automatic closing of inactive issues (OPEN ITEM #9). When to close is a policy decision, so it is off by default.
     */
    public record IssueLifecycle(
            /**
             * ACTIVE issues with no new article and no change for this many days become CLOSED. 0 = never (default).
             * REVIEW issues are never closed automatically: they wait for an admin.
             */
            @DefaultValue("0") int autoCloseAfterDays,
            /** Issues closed per transaction; a run repeats batches (bounded per run). */
            @DefaultValue("100") int batchSize,
            /** Delay between runs (read by the @Scheduled placeholder). */
            @DefaultValue("3600000") long intervalMs) {}
}
