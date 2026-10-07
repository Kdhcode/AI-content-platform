package com.aicontent.platform.admin;

import com.aicontent.platform.common.Json;
import com.aicontent.platform.common.PageParams;
import com.aicontent.platform.common.PageResponse;
import com.aicontent.platform.common.Sql;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ArticleQueryRepository {

    public static final Map<String, String> SORTS = Map.of(
            "id", "a.id", "publishedAt", "a.published_at", "collectedAt", "a.collected_at");

    public record Filter(String status, String classificationStatus, Long sourceId, Long issueId, String q, String publisher) {}

    public record ListItem(long id, String title, String publisherName, OffsetDateTime publishedAt, OffsetDateTime collectedAt,
                           String status, String classificationStatus, Long sourceId, Long issueId, String issueStatus) {}

    public record IssueLink(long issueId, String issueTitle, String issueStatus, String method, String llmDecision,
                            Double llmConfidence, String llmReason, Double similarity, boolean manuallyCorrected) {}

    public record ClassificationRunView(long id, OffsetDateTime createdAt, String decision, String rawDecision, String method,
                                        Double confidence, String reason, Long matchedIssueId, Long appliedIssueId,
                                        boolean applied, JsonNode candidates, String promptVersion, String model) {}

    public record Detail(long id, long sourceId, String sourceName, String title, String originalUrl, String publisherName,
                         String author, String category, OffsetDateTime publishedAt, String publishedAtRaw,
                         OffsetDateTime collectedAt, String status, Long duplicateOfArticleId, String classificationStatus,
                         String analysisText, JsonNode analysis, String analysisPromptVersion, OffsetDateTime analyzedAt,
                         String analysisError, boolean hasEmbedding, String embeddingModel, IssueLink issue,
                         List<ClassificationRunView> classificationRuns) {}

    private final JdbcClient jdbc;
    private final Json json;

    public ArticleQueryRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public PageResponse<ListItem> list(Filter f, PageParams page) {
        QueryHelper q = new QueryHelper();
        if (f.status() != null) {
            q.add("a.status = :status", "status", f.status());
        }
        if (f.classificationStatus() != null) {
            q.add("a.classification_status = :cs", "cs", f.classificationStatus());
        }
        if (f.sourceId() != null) {
            q.add("a.source_id = :source", "source", f.sourceId());
        }
        if (f.issueId() != null) {
            q.add("ia.issue_id = :issue", "issue", f.issueId());
        }
        if (f.q() != null && !f.q().isBlank()) {
            q.add("a.title ILIKE :q ESCAPE '\\'", "q", Sql.likeContains(f.q().trim()));
        }
        if (f.publisher() != null && !f.publisher().isBlank()) {
            q.add("lower(a.publisher_name) = lower(:publisher)", "publisher", f.publisher().trim());
        }
        String from = " FROM news_article a LEFT JOIN issue_article ia ON ia.article_id = a.id AND ia.is_primary"
                + " LEFT JOIN issue i ON i.id = ia.issue_id" + q.where();
        long total = jdbc.sql("SELECT count(*)" + from).params(q.params()).query(Long.class).single();
        List<ListItem> items = jdbc.sql("""
                SELECT a.id, a.title, a.publisher_name, a.published_at, a.collected_at, a.status, a.classification_status,
                       a.source_id, ia.issue_id, i.status AS issue_status""" + from
                        + " ORDER BY " + page.orderBy() + " LIMIT :limit OFFSET :offset")
                .params(q.params()).param("limit", page.size()).param("offset", page.offset())
                .query((rs, n) -> new ListItem(rs.getLong("id"), rs.getString("title"), rs.getString("publisher_name"),
                        Sql.odt(rs, "published_at"), Sql.odt(rs, "collected_at"), rs.getString("status"),
                        rs.getString("classification_status"), rs.getLong("source_id"), Sql.longOrNull(rs, "issue_id"),
                        rs.getString("issue_status")))
                .list();
        return PageResponse.of(items, total, page);
    }

    public Optional<Detail> detail(long id) {
        Optional<Detail> base = jdbc.sql("""
                SELECT a.id, a.source_id, s.name AS source_name, a.title, a.original_url, a.publisher_name, a.author,
                       a.category, a.published_at, a.published_at_raw, a.collected_at, a.status, a.duplicate_of_article_id,
                       a.classification_status, a.analysis_text, CAST(a.analysis_json AS text) AS analysis_json,
                       a.analysis_prompt_version, a.analyzed_at, a.analysis_error, (a.embedding IS NOT NULL) AS has_embedding,
                       a.embedding_model
                FROM news_article a JOIN news_source s ON s.id = a.source_id WHERE a.id = :id""")
                .param("id", id)
                .query((rs, n) -> new Detail(rs.getLong("id"), rs.getLong("source_id"), rs.getString("source_name"),
                        rs.getString("title"), rs.getString("original_url"), rs.getString("publisher_name"),
                        rs.getString("author"), rs.getString("category"), Sql.odt(rs, "published_at"),
                        rs.getString("published_at_raw"), Sql.odt(rs, "collected_at"), rs.getString("status"),
                        Sql.longOrNull(rs, "duplicate_of_article_id"), rs.getString("classification_status"),
                        rs.getString("analysis_text"), json.parseOrNull(rs.getString("analysis_json")),
                        rs.getString("analysis_prompt_version"), Sql.odt(rs, "analyzed_at"), rs.getString("analysis_error"),
                        rs.getBoolean("has_embedding"), rs.getString("embedding_model"), null, List.of()))
                .optional();
        return base.map(d -> new Detail(d.id(), d.sourceId(), d.sourceName(), d.title(), d.originalUrl(), d.publisherName(),
                d.author(), d.category(), d.publishedAt(), d.publishedAtRaw(), d.collectedAt(), d.status(),
                d.duplicateOfArticleId(), d.classificationStatus(), d.analysisText(), d.analysis(),
                d.analysisPromptVersion(), d.analyzedAt(), d.analysisError(), d.hasEmbedding(), d.embeddingModel(),
                issueLink(id), runs(id)));
    }

    private IssueLink issueLink(long articleId) {
        return jdbc.sql("""
                SELECT i.id, i.title, i.status, ia.classification_method, ia.llm_decision, ia.llm_confidence, ia.llm_reason,
                       ia.similarity_score, ia.manually_corrected
                FROM issue_article ia JOIN issue i ON i.id = ia.issue_id
                WHERE ia.article_id = :id AND ia.is_primary""").param("id", articleId)
                .query((rs, n) -> new IssueLink(rs.getLong("id"), rs.getString("title"), rs.getString("status"),
                        rs.getString("classification_method"), rs.getString("llm_decision"),
                        Sql.doubleOrNull(rs, "llm_confidence"), rs.getString("llm_reason"),
                        Sql.doubleOrNull(rs, "similarity_score"), rs.getBoolean("manually_corrected")))
                .optional().orElse(null);
    }

    private List<ClassificationRunView> runs(long articleId) {
        return jdbc.sql("""
                SELECT id, created_at, decision, raw_decision, method, confidence, reason, matched_issue_id,
                       applied_issue_id, applied, CAST(candidates AS text) AS candidates, prompt_version, model
                FROM classification_run WHERE article_id = :id ORDER BY id DESC LIMIT 20""").param("id", articleId)
                .query((rs, n) -> new ClassificationRunView(rs.getLong("id"), Sql.odt(rs, "created_at"),
                        rs.getString("decision"), rs.getString("raw_decision"), rs.getString("method"),
                        Sql.doubleOrNull(rs, "confidence"), rs.getString("reason"), Sql.longOrNull(rs, "matched_issue_id"),
                        Sql.longOrNull(rs, "applied_issue_id"), rs.getBoolean("applied"),
                        json.parseOrNull(rs.getString("candidates")), rs.getString("prompt_version"), rs.getString("model")))
                .list();
    }
}
