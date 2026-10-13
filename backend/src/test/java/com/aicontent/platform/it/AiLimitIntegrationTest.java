package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicontent.platform.job.JobType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With app.ai.limits.daily-max-calls=2 (stub AI), the third AI call is never made: its job waits for the next day
 * without using a retry or a deferral, and runs once the day has changed.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.ai.limits.daily-max-calls=2")
class AiLimitIntegrationTest extends IntegrationTestBase {

    private static final String WAITING = """
            SELECT count(*) FROM async_job WHERE status = 'PENDING' AND error_code = 'AI_DAILY_CALL_LIMIT_REACHED'
              AND retry_count = 0 AND defer_count = 0 AND locked_by IS NULL AND finished_at IS NULL
              AND available_at > now()""";

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;

    @Test
    void reachedDailyLimitMakesJobsWaitInsteadOfFailing() throws Exception {
        long sourceId = fixtureSource("src-a", "[" + String.join(",",
                article("가상시 A동 상가 화재 발생 주민 대피", "https://example.test/n/1", "가상일보", "2026-10-01T09:00:00+09:00"),
                article("가상시 A동 상가 화재 발생 소방 출동", "https://example.test/n/2", "샘플뉴스", "2026-10-01T10:00:00+09:00"),
                article("허구군 물류창고 정전 사고 복구 완료", "https://example.test/n/3", "예시방송", "2026-10-01T11:00:00+09:00")) + "]");
        jobsService.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", sourceId), "t", "collect:" + sourceId, null);
        runUntilIdle();

        // analyses of articles 1 and 2 used the two calls; analysis 3 and both embeddings wait
        assertThat(count("SELECT count(*) FROM ai_log")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ai_log WHERE call_type = 'ARTICLE_ANALYSIS'")).isEqualTo(2);
        assertThat(count(WAITING)).isEqualTo(3);
        assertThat(count(WAITING + " AND job_type = 'ANALYZE_ARTICLE'")).isEqualTo(1);
        assertThat(count(WAITING + " AND job_type = 'EMBED_ARTICLE'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM async_job WHERE status = 'FAILED'")).isZero();
        assertThat(count("SELECT count(*) FROM news_article WHERE status = 'FAILED'")).isZero();

        operator();
        mvc.perform(get("/api/admin/ai-usage?days=1").with(httpBasic("op", "op-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limits.dailyMaxCalls").value(2))
                .andExpect(jsonPath("$.data.limits.dailyBudgetUsd").value(nullValue()))
                .andExpect(jsonPath("$.data.limits.callsToday").value(2))
                .andExpect(jsonPath("$.data.limits.blockedBy").value("AI_DAILY_CALL_LIMIT_REACHED"));

        // the next day: yesterday's calls no longer count, waiting jobs are due again
        jdbc.sql("UPDATE ai_log SET created_at = created_at - interval '1 day'").update();
        jdbc.sql("UPDATE async_job SET available_at = now() WHERE status = 'PENDING'").update();
        worker.runPending(20);   // claims analysis 3, embedding 1, embedding 2 in this order

        assertThat(count("SELECT count(*) FROM async_job WHERE job_type = 'ANALYZE_ARTICLE' AND status = 'SUCCESS'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM async_job WHERE job_type = 'EMBED_ARTICLE' AND status = 'SUCCESS'")).isEqualTo(1);
        assertThat(count(WAITING + " AND job_type = 'EMBED_ARTICLE'")).isEqualTo(1);   // the new day's two calls are used
        assertThat(count("SELECT count(*) FROM async_job WHERE status = 'FAILED'")).isZero();
    }

    private void operator() {
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('op', :h) ON CONFLICT (lower(username)) DO NOTHING")
                .param("h", encoder.encode("op-pw")).update();
        jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, 'OPERATOR' FROM app_user WHERE username = 'op' ON CONFLICT DO NOTHING").update();
    }

    /** Unlike {@link #drain()} this respects available_at, so waiting jobs are not picked up again. */
    private void runUntilIdle() {
        for (int round = 0; round < 20; round++) {
            if (worker.runPending(20) == 0) {
                return;
            }
        }
        throw new IllegalStateException("queue did not go idle in 20 rounds");
    }
}
