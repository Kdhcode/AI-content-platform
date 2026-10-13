package com.aicontent.platform.ai;

import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AiLogRepository {

    private static final int MAX_TEXT = 20_000;

    public record Entry(Long aiJobId, Long articleId, AiCallType type, String provider, String model, String promptKey,
                        String promptVersion, int attempt, Integer inputTokens, Integer outputTokens,
                        BigDecimal estimatedCostUsd, Integer latencyMs, boolean success, String errorCode, String errorMessage, String response) {}

    private final JdbcClient jdbc;

    public AiLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Entry e) {
        jdbc.sql("""
                INSERT INTO ai_log (ai_job_id, article_id, call_type, provider, model, prompt_key, prompt_version, attempt,
                    input_tokens, output_tokens, estimated_cost_usd, latency_ms, success, error_code, error_message, response)
                VALUES (:aiJob, :article, :type, :provider, :model, :pk, :pv, :attempt, :inTok, :outTok,
                    CAST(:cost AS numeric), :latency, :success, :errCode, :errMsg, :response)""")
                .param("aiJob", e.aiJobId()).param("article", e.articleId()).param("type", e.type().name())
                .param("provider", e.provider()).param("model", e.model()).param("pk", e.promptKey())
                .param("pv", e.promptVersion()).param("attempt", e.attempt()).param("inTok", e.inputTokens())
                .param("outTok", e.outputTokens()).param("cost", e.estimatedCostUsd()).param("latency", e.latencyMs()).param("success", e.success())
                .param("errCode", e.errorCode()).param("errMsg", cut(e.errorMessage(), 2000))
                .param("response", cut(e.response(), MAX_TEXT))
                .update();
    }

    /** AI usage for limit checks: calls and cost since the start of today in {@code timeZone}, calls in the last minute. */
    public record UsageSnapshot(long callsToday, BigDecimal costToday, long callsLastMinute, long secondsUntilNextDay) {}

    public UsageSnapshot usageSnapshot(String timeZone) {
        return jdbc.sql("""
                WITH b AS (
                    SELECT (date_trunc('day', now() AT TIME ZONE :tz) AT TIME ZONE :tz) AS day_start,
                           ((date_trunc('day', now() AT TIME ZONE :tz) + interval '1 day') AT TIME ZONE :tz) AS next_day_start)
                SELECT count(l.id) FILTER (WHERE l.created_at >= b.day_start) AS calls_today,
                       coalesce(sum(l.estimated_cost_usd) FILTER (WHERE l.created_at >= b.day_start), 0) AS cost_today,
                       count(l.id) FILTER (WHERE l.created_at > now() - interval '60 seconds') AS calls_last_minute,
                       CAST(ceil(extract(epoch FROM (b.next_day_start - now()))) AS bigint) AS seconds_until_next_day
                FROM b LEFT JOIN ai_log l ON l.created_at >= LEAST(b.day_start, now() - interval '60 seconds')
                GROUP BY b.day_start, b.next_day_start""")
                .param("tz", timeZone)
                .query((rs, n) -> new UsageSnapshot(rs.getLong("calls_today"), rs.getBigDecimal("cost_today"),
                        rs.getLong("calls_last_minute"), rs.getLong("seconds_until_next_day")))
                .single();
    }

    /**
     * Clears {@code response} (raw model output) of at most {@code batchSize} rows older than {@code days}. Tokens, cost,
     * model and success stay, so usage and cost history are unaffected.
     *
     * @return number of rows cleared (less than {@code batchSize} means nothing more is due)
     */
    public int clearResponsesOlderThan(int days, int batchSize) {
        return jdbc.sql("""
                UPDATE ai_log SET response = NULL
                WHERE id IN (
                    SELECT id FROM ai_log
                    WHERE response IS NOT NULL AND created_at < now() - make_interval(days => :days)
                    ORDER BY id
                    LIMIT :batch
                    FOR UPDATE SKIP LOCKED)""")
                .param("days", days)
                .param("batch", batchSize)
                .update();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
