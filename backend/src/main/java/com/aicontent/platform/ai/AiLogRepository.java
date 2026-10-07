package com.aicontent.platform.ai;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AiLogRepository {

    private static final int MAX_TEXT = 20_000;

    public record Entry(Long aiJobId, Long articleId, AiCallType type, String provider, String model, String promptKey,
                        String promptVersion, int attempt, Integer inputTokens, Integer outputTokens, Integer latencyMs,
                        boolean success, String errorCode, String errorMessage, String response) {}

    private final JdbcClient jdbc;

    public AiLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Entry e) {
        jdbc.sql("""
                INSERT INTO ai_log (ai_job_id, article_id, call_type, provider, model, prompt_key, prompt_version, attempt,
                    input_tokens, output_tokens, latency_ms, success, error_code, error_message, response)
                VALUES (:aiJob, :article, :type, :provider, :model, :pk, :pv, :attempt, :inTok, :outTok, :latency,
                    :success, :errCode, :errMsg, :response)""")
                .param("aiJob", e.aiJobId()).param("article", e.articleId()).param("type", e.type().name())
                .param("provider", e.provider()).param("model", e.model()).param("pk", e.promptKey())
                .param("pv", e.promptVersion()).param("attempt", e.attempt()).param("inTok", e.inputTokens())
                .param("outTok", e.outputTokens()).param("latency", e.latencyMs()).param("success", e.success())
                .param("errCode", e.errorCode()).param("errMsg", cut(e.errorMessage(), 2000))
                .param("response", cut(e.response(), MAX_TEXT))
                .update();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
