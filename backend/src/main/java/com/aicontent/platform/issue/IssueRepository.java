package com.aicontent.platform.issue;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Write-side SQL for issue / issue_article. Read models for the admin UI live in {@link IssueQueryRepository}. */
@Repository
public class IssueRepository {

    public record IssueHead(long id, String title, String summary, String summaryStatus, String status,
                            Long mergedIntoIssueId, int articleCount) {}

    private final JdbcClient jdbc;

    public IssueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String title, String summary, String category, String status) {
        return jdbc.sql("""
                INSERT INTO issue (title, summary, category, status) VALUES (:title, :summary, :category, :status)
                RETURNING id""")
                .param("title", title).param("summary", summary).param("category", category).param("status", status)
                .query(Long.class).single();
    }

    public Optional<IssueHead> findHead(long id) {
        return jdbc.sql("""
                SELECT id, title, summary, summary_source, status, merged_into_issue_id, article_count
                FROM issue WHERE id = :id""").param("id", id)
                .query((rs, n) -> new IssueHead(rs.getLong("id"), rs.getString("title"), rs.getString("summary"),
                        rs.getString("summary_source"), rs.getString("status"),
                        (Long) rs.getObject("merged_into_issue_id"), rs.getInt("article_count")))
                .optional();
    }

    /** Row lock; callers lock several issues in ascending id order to avoid deadlocks. */
    public Optional<String> lockStatus(long id) {
        return jdbc.sql("SELECT status FROM issue WHERE id = :id FOR UPDATE").param("id", id)
                .query(String.class).optional();
    }

    public Optional<Long> findPrimaryIssueId(long articleId) {
        return jdbc.sql("SELECT issue_id FROM issue_article WHERE article_id = :a AND is_primary")
                .param("a", articleId).query(Long.class).optional();
    }

    public void link(long issueId, long articleId, String method, Double similarity, String llmDecision,
                     Double llmConfidence, String llmReason, Long runId, boolean manuallyCorrected) {
        jdbc.sql("""
                INSERT INTO issue_article (issue_id, article_id, is_primary, classification_method, similarity_score,
                    llm_decision, llm_confidence, llm_reason, classification_run_id, manually_corrected)
                VALUES (:issue, :article, TRUE, :method, :sim, :decision, :conf, :reason, :run, :manual)""")
                .param("issue", issueId).param("article", articleId).param("method", method).param("sim", similarity)
                .param("decision", llmDecision).param("conf", llmConfidence).param("reason", llmReason)
                .param("run", runId).param("manual", manuallyCorrected)
                .update();
    }

    public int unlink(long issueId, long articleId) {
        return jdbc.sql("DELETE FROM issue_article WHERE issue_id = :i AND article_id = :a")
                .param("i", issueId).param("a", articleId).update();
    }

    /** Re-points every member of {@code fromIssue} to {@code toIssue}; returns the moved article ids. */
    public List<Long> moveAllMembers(long fromIssue, long toIssue, String method) {
        return jdbc.sql("""
                UPDATE issue_article SET issue_id = :to, classification_method = :method, manually_corrected = TRUE
                WHERE issue_id = :from RETURNING article_id""")
                .param("from", fromIssue).param("to", toIssue).param("method", method)
                .query(Long.class).list();
    }

    public int repointArticle(long articleId, long fromIssue, long toIssue, String method) {
        return jdbc.sql("""
                UPDATE issue_article SET issue_id = :to, classification_method = :method, manually_corrected = TRUE
                WHERE article_id = :a AND issue_id = :from AND is_primary""")
                .param("to", toIssue).param("method", method).param("a", articleId).param("from", fromIssue).update();
    }

    public boolean isMember(long issueId, long articleId) {
        return jdbc.sql("SELECT count(*) FROM issue_article WHERE issue_id = :i AND article_id = :a")
                .param("i", issueId).param("a", articleId).query(Long.class).single() > 0;
    }

    public List<Long> memberArticleIds(long issueId) {
        return jdbc.sql("SELECT article_id FROM issue_article WHERE issue_id = :i ORDER BY article_id")
                .param("i", issueId).query(Long.class).list();
    }

    public void markMerged(long sourceIssueId, long targetIssueId) {
        jdbc.sql("""
                UPDATE issue SET status = 'MERGED', merged_into_issue_id = :target WHERE id = :source""")
                .param("source", sourceIssueId).param("target", targetIssueId).update();
    }

    public void updateStatus(long id, String status) {
        jdbc.sql("UPDATE issue SET status = :s WHERE id = :id").param("s", status).param("id", id).update();
    }

    /**
     * Recomputes counts, time range and the centroid embedding from the current primary members, and closes an
     * ACTIVE/REVIEW issue that has no members left. All derived values come from one SQL statement so they can
     * never disagree with each other.
     */
    public void recomputeAggregates(long issueId) {
        jdbc.sql("""
                UPDATE issue i SET
                    article_count = s.cnt,
                    publisher_count = s.pubs,
                    first_published_at = s.first_at,
                    last_updated_at = s.last_at,
                    embedding = s.emb,
                    status = CASE WHEN s.cnt = 0 AND i.status IN ('ACTIVE', 'REVIEW') THEN 'CLOSED' ELSE i.status END
                FROM (
                    SELECT count(*) AS cnt,
                           count(DISTINCT lower(trim(a.publisher_name))) AS pubs,
                           min(COALESCE(a.published_at, a.collected_at)) AS first_at,
                           max(COALESCE(a.published_at, a.collected_at)) AS last_at,
                           avg(a.embedding) AS emb
                    FROM issue_article ia JOIN news_article a ON a.id = ia.article_id
                    WHERE ia.issue_id = :id AND ia.is_primary
                ) s
                WHERE i.id = :id""").param("id", issueId).update();
    }

    /** analysis_json texts of the members, oldest first (stable order for IssueContentMerger). */
    public List<String> memberAnalyses(long issueId) {
        return jdbc.sql("""
                SELECT CAST(a.analysis_json AS text) FROM issue_article ia JOIN news_article a ON a.id = ia.article_id
                WHERE ia.issue_id = :id AND ia.is_primary AND a.analysis_json IS NOT NULL
                ORDER BY COALESCE(a.published_at, a.collected_at), a.id""")
                .param("id", issueId).query(String.class).list();
    }

    public void updateDerivedContent(long id, String keyFactsJson, String entitiesJson) {
        jdbc.sql("""
                UPDATE issue SET key_facts = CAST(:facts AS jsonb), entities = CAST(:entities AS jsonb)
                WHERE id = :id""").param("facts", keyFactsJson).param("entities", entitiesJson).param("id", id).update();
    }

    /** AUTO summaries follow the earliest member; MANUAL summaries are never touched. */
    public void fillAutoSummaryIfEmpty(long id, String summary) {
        jdbc.sql("""
                UPDATE issue SET summary = :summary
                WHERE id = :id AND summary_source = 'AUTO' AND (summary IS NULL OR summary = '')""")
                .param("summary", summary).param("id", id).update();
    }

    /** Null arguments leave the column unchanged. A given summary becomes MANUAL so automation never overwrites it. */
    public void updateFields(long id, String title, String summary, String category, String status) {
        jdbc.sql("""
                UPDATE issue SET title = COALESCE(:title, title),
                    summary = COALESCE(:summary, summary),
                    summary_source = CASE WHEN CAST(:summary AS text) IS NULL THEN summary_source ELSE 'MANUAL' END,
                    category = COALESCE(:category, category),
                    status = COALESCE(:status, status)
                WHERE id = :id""")
                .param("title", title).param("summary", summary).param("category", category)
                .param("status", status).param("id", id).update();
    }
}
