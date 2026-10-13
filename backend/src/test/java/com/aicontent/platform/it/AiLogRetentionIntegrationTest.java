package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.ai.AiLogRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Expired raw model output is cleared in batches; the row and its usage data stay. */
@TestPropertySource(properties = {"app.retention.ai-log-response-days=30", "app.retention.batch-size=2"})
class AiLogRetentionIntegrationTest extends IntegrationTestBase {

    @Autowired AiLogRetentionService retention;

    @Test
    void clearsOnlyExpiredResponsesAndKeepsUsageData() {
        for (int i = 0; i < 5; i++) {
            log("now() - interval '31 days'", "'old output'");
        }
        log("now() - interval '29 days'", "'recent output'");
        log("now() - interval '40 days'", "NULL");   // nothing to clear

        // batch size 2: 2 + 2 + 1, the partial batch ends the run
        assertThat(retention.clearExpiredResponses()).isEqualTo(5);

        assertThat(count("SELECT count(*) FROM ai_log")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM ai_log WHERE response IS NOT NULL")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT response FROM ai_log WHERE response IS NOT NULL").query(String.class).single())
                .isEqualTo("recent output");
        assertThat(count("""
                SELECT count(*) FROM ai_log WHERE created_at < now() - interval '30 days'
                  AND input_tokens = 100 AND output_tokens = 20 AND estimated_cost_usd = 0.001 AND success
                  AND model = 'm' AND provider = 'openai'""")).isEqualTo(6);

        assertThat(retention.clearExpiredResponses()).isZero();   // idempotent
    }

    /** Both arguments are SQL expressions written by the test itself. */
    private void log(String createdAt, String response) {
        jdbc.sql("""
                INSERT INTO ai_log (call_type, provider, model, input_tokens, output_tokens, estimated_cost_usd, success,
                    response, created_at)
                VALUES ('ARTICLE_ANALYSIS', 'openai', 'm', 100, 20, 0.001, TRUE, %s, %s)""".formatted(response, createdAt))
                .update();
    }
}
