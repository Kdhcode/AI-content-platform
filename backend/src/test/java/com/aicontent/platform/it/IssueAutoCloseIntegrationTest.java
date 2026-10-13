package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.issue.IssueAutoCloseService;
import com.aicontent.platform.issue.IssueCandidateFinder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Inactive ACTIVE issues are closed in batches with an audit row each; REVIEW and recently active issues stay. */
@TestPropertySource(properties = {"app.issue-lifecycle.auto-close-after-days=30", "app.issue-lifecycle.batch-size=1"})
class IssueAutoCloseIntegrationTest extends IntegrationTestBase {

    @Autowired IssueAutoCloseService autoClose;
    @Autowired IssueCandidateFinder finder;

    @Test
    void closesOnlyInactiveActiveIssuesAndAuditsEach() {
        long oldActive = issue("ACTIVE", "now() - interval '40 days'", "now() - interval '40 days'");
        long editedRecently = issue("ACTIVE", "now() - interval '40 days'", "now() - interval '1 day'");
        long recentArticle = issue("ACTIVE", "now() - interval '2 days'", "now() - interval '2 days'");
        long oldReview = issue("REVIEW", "now() - interval '40 days'", "now() - interval '40 days'");
        long noArticleTime = issue("ACTIVE", "NULL", "now() - interval '40 days'");

        // batch size 1: two full batches and an empty one
        assertThat(autoClose.closeInactive()).isEqualTo(2);

        assertThat(status(oldActive)).isEqualTo("CLOSED");
        assertThat(status(noArticleTime)).isEqualTo("CLOSED");
        assertThat(status(editedRecently)).isEqualTo("ACTIVE");
        assertThat(status(recentArticle)).isEqualTo("ACTIVE");
        assertThat(status(oldReview)).isEqualTo("REVIEW");

        assertThat(count("""
                SELECT count(*) FROM admin_audit_log
                WHERE actor = 'system' AND action = 'ISSUE_AUTO_CLOSE' AND target_type = 'ISSUE'
                  AND before_state->>'status' = 'ACTIVE' AND before_state->>'lastActivityAt' IS NOT NULL
                  AND after_state->>'status' = 'CLOSED' AND reason LIKE '30일%' AND correlation_id LIKE 'auto-close-%'"""))
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT target_id FROM admin_audit_log ORDER BY target_id").query(String.class).list())
                .containsExactly(String.valueOf(oldActive), String.valueOf(noArticleTime));
        // one run shares one correlation id
        assertThat(count("SELECT count(DISTINCT correlation_id) FROM admin_audit_log")).isEqualTo(1);

        assertThat(autoClose.closeInactive()).isZero();   // idempotent
        assertThat(count("SELECT count(*) FROM admin_audit_log")).isEqualTo(2);
    }

    @Test
    void aClosedIssueIsNoLongerAClassificationCandidate() {
        // set on INSERT: an UPDATE would fire the updated_at trigger and make the issue look active
        long issueId = issue("ACTIVE", "now() - interval '40 days'", "now() - interval '40 days'", VECTOR);
        long articleId = articleWithEmbedding();
        assertThat(finder.find(articleId)).extracting(c -> c.id()).containsExactly(issueId);

        autoClose.closeInactive();

        assertThat(status(issueId)).isEqualTo("CLOSED");
        assertThat(finder.find(articleId)).isEmpty();
    }

    /** Same vector for issue and article: cosine similarity 1, far above the candidate threshold. */
    private static final String VECTOR = "CAST(array_fill(0.5::real, ARRAY[1536]) AS vector)";

    private long articleWithEmbedding() {
        long sourceId = jdbc.sql("""
                INSERT INTO news_source (name, source_type) VALUES ('src-close', 'FIXTURE') RETURNING id""")
                .query(Long.class).single();
        return jdbc.sql("""
                INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name,
                    status, embedding)
                VALUES (:src, 'follow-up', 'https://example.test/f', 'https://example.test/f', repeat('a', 64), 'p',
                    'ANALYZED', %s) RETURNING id""".formatted(VECTOR))
                .param("src", sourceId).query(Long.class).single();
    }

    /**
     * Inserts an issue with fixed activity timestamps. updated_at is only overwritten by the trigger on UPDATE, so an
     * explicit value on INSERT sticks. The timestamp and embedding arguments are SQL expressions written by the test itself.
     */
    private long issue(String status, String lastUpdatedAt, String updatedAt) {
        return issue(status, lastUpdatedAt, updatedAt, "NULL");
    }

    private long issue(String status, String lastUpdatedAt, String updatedAt, String embedding) {
        return jdbc.sql("""
                INSERT INTO issue (title, status, article_count, last_updated_at, created_at, updated_at, embedding)
                VALUES ('t', :status, 1, %s, now() - interval '41 days', %s, %s) RETURNING id"""
                        .formatted(lastUpdatedAt, updatedAt, embedding))
                .param("status", status).query(Long.class).single();
    }

    private String status(long id) {
        return jdbc.sql("SELECT status FROM issue WHERE id = :id").param("id", id).query(String.class).single();
    }
}
