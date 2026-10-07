package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.job.JobType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** The stub answers SAME_ISSUE with 0.9; requiring 0.95 must downgrade it to REVIEW instead of auto-linking. */
@TestPropertySource(properties = "app.classifier.same-issue-min-confidence=0.95")
class ClassificationReviewIntegrationTest extends IntegrationTestBase {

    @Autowired AsyncJobService jobs;

    @Test
    void lowConfidenceSameIssueBecomesAReviewIssueWithoutPollutingTheExistingOne() throws Exception {
        long id = fixtureSource("src-a", "[" + String.join(",",
                article("가상시 A동 상가 화재 발생 주민 대피", "https://example.test/n/1", "가상일보", "2026-10-01T09:00:00+09:00"),
                article("가상시 A동 상가 화재 발생 소방 출동", "https://example.test/n/2", "샘플뉴스", "2026-10-01T10:00:00+09:00")) + "]");
        jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", id), "t", "collect:" + id, null);
        drain();

        assertThat(jdbc.sql("SELECT status FROM issue ORDER BY id").query(String.class).list()).containsExactly("ACTIVE", "REVIEW");
        assertThat(jdbc.sql("SELECT article_count FROM issue ORDER BY id").query(Integer.class).list()).containsExactly(1, 1);
        var run = jdbc.sql("SELECT raw_decision, decision, method FROM classification_run ORDER BY id DESC LIMIT 1")
                .query((rs, n) -> rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getString(3)).single();
        assertThat(run).isEqualTo("SAME_ISSUE/REVIEW/GUARD_DOWNGRADE");
        assertThat(jdbc.sql("SELECT classification_status FROM news_article ORDER BY id DESC LIMIT 1").query(String.class).single())
                .isEqualTo("REVIEW");
        assertThat(jdbc.sql("SELECT classification_method FROM issue_article ORDER BY article_id DESC LIMIT 1").query(String.class).single())
                .isEqualTo("LLM_REVIEW");
    }
}
