package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.job.JobType;
import java.util.Map;

/** Whole chain COLLECT -> ANALYZE -> EMBED -> CLASSIFY with the deterministic stub provider (app.ai.provider=stub). */
class ClassificationFlowIntegrationTest extends IntegrationTestBase {

    @Autowired AsyncJobService jobs;

    @Test
    void relatedArticlesShareAnIssueAndUnrelatedGetTheirOwn() throws Exception {
        collectSample();

        assertThat(count("SELECT count(*) FROM news_article WHERE status = 'ANALYZED' AND embedding IS NOT NULL")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM issue")).isEqualTo(2);
        List<Integer> sizes = jdbc.sql("SELECT article_count FROM issue ORDER BY id").query(Integer.class).list();
        assertThat(sizes).containsExactly(2, 1);
        // aggregates are consistent with the members
        assertThat(jdbc.sql("SELECT publisher_count FROM issue ORDER BY id LIMIT 1").query(Integer.class).single()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM issue WHERE embedding IS NULL")).isEqualTo(0);
        // every article has exactly one primary link and a classification_run
        assertThat(count("SELECT count(*) FROM issue_article WHERE is_primary")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM classification_run WHERE applied")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM news_article WHERE classification_status = 'CLASSIFIED'")).isEqualTo(3);
        // the first article had no candidate; the second was linked by the LLM step
        assertThat(jdbc.sql("SELECT method FROM classification_run ORDER BY id").query(String.class).list())
                .containsExactly("NO_CANDIDATE", "LLM", "NO_CANDIDATE");
        // every AI call is logged with the prompt version
        assertThat(count("SELECT count(*) FROM ai_log WHERE call_type = 'ARTICLE_ANALYSIS' AND success AND prompt_version = 'v1'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM ai_log WHERE call_type = 'EMBEDDING' AND success")).isEqualTo(3);
    }

    @Test
    void runningTheWholeChainAgainDoesNotDuplicateAnything() throws Exception {
        long id = collectSample();
        jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", id), "t", "collect:" + id, null);
        drain();
        assertThat(count("SELECT count(*) FROM news_article")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM issue")).isEqualTo(2);
    }

    @Test
    void reclassifyingALinkedArticleOnlyRecordsARun() throws Exception {
        collectSample();
        long articleId = jdbc.sql("SELECT id FROM news_article ORDER BY id LIMIT 1").query(Long.class).single();
        long issueBefore = jdbc.sql("SELECT issue_id FROM issue_article WHERE article_id = :a").param("a", articleId).query(Long.class).single();
        jobs.enqueue(JobType.CLASSIFY_ARTICLE, Map.of("articleId", articleId), "t", "classify:" + articleId, null);
        drain();
        assertThat(jdbc.sql("SELECT issue_id FROM issue_article WHERE article_id = :a").param("a", articleId).query(Long.class).single())
                .isEqualTo(issueBefore);
        assertThat(count("SELECT count(*) FROM issue")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM classification_run WHERE article_id = " + articleId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM classification_run WHERE article_id = " + articleId + " AND NOT applied")).isEqualTo(1);
    }
}
