package com.aicontent.platform.ai;

import com.aicontent.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Calls the embedding provider, enforces the configured dimension and records the call in ai_log. */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingClient client;
    private final AiLogRepository logs;
    private final AppProperties props;
    private final AiCostCalculator costs;
    private final AiUsageLimiter limiter;

    public EmbeddingService(EmbeddingClient client, AiLogRepository logs, AppProperties props, AiCostCalculator costs,
                            AiUsageLimiter limiter) {
        this.client = client;
        this.logs = logs;
        this.props = props;
        this.costs = costs;
        this.limiter = limiter;
    }

    /** @throws com.aicontent.platform.job.JobDeferredException when an {@code app.ai.limits} limit is reached (no call made) */
    public EmbeddingResult embed(String text, AiCallContext context) {
        limiter.checkBeforeCall(AiCallType.EMBEDDING);
        long started = System.nanoTime();
        Long articleId = context == null ? null : context.articleId();
        EmbeddingResult result;
        try {
            result = client.embed(text);
        } catch (AiException e) {
            // no response: record the model that was requested
            record(articleId, client.model(), null, started, false, e.code(), e.getMessage());
            throw e;
        }
        int expected = props.ai().embeddingDimension();
        if (result.vector() == null || result.vector().length != expected) {
            int actual = result.vector() == null ? 0 : result.vector().length;
            AiException e = new AiException("EMBEDDING_DIMENSION_MISMATCH",
                    "embedding has " + actual + " dimensions, schema expects " + expected, false);
            // the provider answered (and may have billed tokens): keep its model and token count
            record(articleId, result.model(), result.inputTokens(), started, false, e.code(), e.getMessage());
            throw e;
        }
        record(articleId, result.model(), result.inputTokens(), started, true, null, null);
        return result;
    }

    private void record(Long articleId, String model, Integer tokens, long started, boolean ok, String code, String msg) {
        try {
            logs.insert(new AiLogRepository.Entry(null, articleId, AiCallType.EMBEDDING, client.provider(), model, null, null, 1,
                    tokens, null, costs.estimate(model, tokens, null), (int) ((System.nanoTime() - started) / 1_000_000),
                    ok, code, msg, null));
        } catch (RuntimeException e) {
            log.warn("ai_log insert failed (ignored): {}", e.getMessage());
        }
    }
}
