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
public class IssueQueryRepository {

    public static final Map<String, String> SORTS = Map.of(
            "id", "i.id", "lastUpdatedAt", "i.last_updated_at", "firstPublishedAt", "i.first_published_at",
            "articleCount", "i.article_count", "createdAt", "i.created_at");

    public record Filter(String status, String category, String q) {}

    public record ListItem(long id, String title, String summary, String category, String status, int articleCount,
                           int publisherCount, OffsetDateTime firstPublishedAt, OffsetDateTime lastUpdatedAt,
                           Long mergedIntoIssueId) {}

    public record ArticleItem(long articleId, String title, String publisherName, OffsetDateTime publishedAt,
                              String classificationMethod, String llmDecision, Double llmConfidence, String llmReason,
                              Double similarity, boolean manuallyCorrected) {}

    public record Detail(long id, String title, String summary, String summarySource, String category, String status,
                         JsonNode keyFacts, JsonNode entities, int articleCount, int publisherCount,
                         OffsetDateTime firstPublishedAt, OffsetDateTime lastUpdatedAt, Long mergedIntoIssueId,
                         List<Long> mergedFromIssueIds, OffsetDateTime createdAt, List<ArticleItem> articles) {}

    private final JdbcClient jdbc;
    private final Json json;

    public IssueQueryRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public PageResponse<ListItem> list(Filter f, PageParams page) {
        QueryHelper q = new QueryHelper();
        if (f.status() != null) {
            q.add("i.status = :status", "status", f.status());
        }
        if (f.category() != null) {
            q.add("i.category = :category", "category", f.category());
        }
        if (f.q() != null && !f.q().isBlank()) {
            q.add("(i.title ILIKE :q ESCAPE '\\' OR i.summary ILIKE :q ESCAPE '\\')", "q", Sql.likeContains(f.q().trim()));
        }
        String from = " FROM issue i" + q.where();
        long total = jdbc.sql("SELECT count(*)" + from).params(q.params()).query(Long.class).single();
        List<ListItem> items = jdbc.sql("""
                SELECT i.id, i.title, i.summary, i.category, i.status, i.article_count, i.publisher_count,
                       i.first_published_at, i.last_updated_at, i.merged_into_issue_id""" + from
                        + " ORDER BY " + page.orderBy() + " LIMIT :limit OFFSET :offset")
                .params(q.params()).param("limit", page.size()).param("offset", page.offset())
                .query((rs, n) -> new ListItem(rs.getLong("id"), rs.getString("title"), rs.getString("summary"),
                        rs.getString("category"), rs.getString("status"), rs.getInt("article_count"),
                        rs.getInt("publisher_count"), Sql.odt(rs, "first_published_at"), Sql.odt(rs, "last_updated_at"),
                        Sql.longOrNull(rs, "merged_into_issue_id")))
                .list();
        return PageResponse.of(items, total, page);
    }

    public Optional<Detail> detail(long id) {
        Optional<Detail> base = jdbc.sql("""
                SELECT id, title, summary, summary_source, category, status, CAST(key_facts AS text) AS key_facts,
                       CAST(entities AS text) AS entities, article_count, publisher_count, first_published_at,
                       last_updated_at, merged_into_issue_id, created_at
                FROM issue WHERE id = :id""").param("id", id)
                .query((rs, n) -> new Detail(rs.getLong("id"), rs.getString("title"), rs.getString("summary"),
                        rs.getString("summary_source"), rs.getString("category"), rs.getString("status"),
                        json.parse(rs.getString("key_facts")), json.parse(rs.getString("entities")),
                        rs.getInt("article_count"), rs.getInt("publisher_count"), Sql.odt(rs, "first_published_at"),
                        Sql.odt(rs, "last_updated_at"), Sql.longOrNull(rs, "merged_into_issue_id"), List.of(),
                        Sql.odt(rs, "created_at"), List.of()))
                .optional();
        return base.map(d -> new Detail(d.id(), d.title(), d.summary(), d.summarySource(), d.category(), d.status(),
                d.keyFacts(), d.entities(), d.articleCount(), d.publisherCount(), d.firstPublishedAt(),
                d.lastUpdatedAt(), d.mergedIntoIssueId(), mergedFrom(id), d.createdAt(), articles(id)));
    }

    private List<Long> mergedFrom(long id) {
        return jdbc.sql("SELECT id FROM issue WHERE merged_into_issue_id = :id ORDER BY id").param("id", id)
                .query(Long.class).list();
    }

    private List<ArticleItem> articles(long id) {
        return jdbc.sql("""
                SELECT a.id, a.title, a.publisher_name, a.published_at, ia.classification_method, ia.llm_decision,
                       ia.llm_confidence, ia.llm_reason, ia.similarity_score, ia.manually_corrected
                FROM issue_article ia JOIN news_article a ON a.id = ia.article_id
                WHERE ia.issue_id = :id AND ia.is_primary
                ORDER BY COALESCE(a.published_at, a.collected_at), a.id""").param("id", id)
                .query((rs, n) -> new ArticleItem(rs.getLong("id"), rs.getString("title"), rs.getString("publisher_name"),
                        Sql.odt(rs, "published_at"), rs.getString("classification_method"), rs.getString("llm_decision"),
                        Sql.doubleOrNull(rs, "llm_confidence"), rs.getString("llm_reason"),
                        Sql.doubleOrNull(rs, "similarity_score"), rs.getBoolean("manually_corrected")))
                .list();
    }
}
