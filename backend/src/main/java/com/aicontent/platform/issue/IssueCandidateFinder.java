package com.aicontent.platform.issue;

import com.aicontent.platform.common.Json;
import com.aicontent.platform.common.Sql;
import com.aicontent.platform.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Embedding retrieval of candidate issues (pgvector cosine distance, HNSW index). Retrieval only narrows the field;
 * the same-event judgement is always made by the LLM + guard policy, never by similarity alone. The article's own
 * embedding is read inside the query, so no vector travels through Java.
 */
@Component
public class IssueCandidateFinder {

    private final JdbcClient jdbc;
    private final Json json;
    private final AppProperties props;

    public IssueCandidateFinder(JdbcClient jdbc, Json json, AppProperties props) {
        this.jdbc = jdbc;
        this.json = json;
        this.props = props;
    }

    public List<IssueCandidate> find(long articleId) {
        var c = props.classifier();
        return jdbc.sql("""
                SELECT * FROM (
                    SELECT i.id, i.title, i.summary, CAST(i.key_facts AS text) AS key_facts, i.first_published_at,
                           i.last_updated_at, i.article_count, i.publisher_count,
                           1 - (i.embedding <=> a.embedding) AS similarity
                    FROM issue i, (SELECT embedding FROM news_article WHERE id = :article) a
                    WHERE i.status IN (:statuses)
                      AND i.embedding IS NOT NULL AND a.embedding IS NOT NULL
                      AND COALESCE(i.last_updated_at, i.updated_at) >= now() - make_interval(days => :days)
                    ORDER BY i.embedding <=> a.embedding
                    LIMIT :limit
                ) ranked
                WHERE similarity >= :minSimilarity
                ORDER BY similarity DESC""")
                .param("article", articleId)
                .param("statuses", c.candidateIssueStatuses())
                .param("days", c.candidateWindowDays())
                .param("limit", c.candidateLimit())
                .param("minSimilarity", c.candidateMinSimilarity())
                .query((rs, n) -> {
                    List<String> facts = new ArrayList<>();
                    JsonNode node = json.parse(rs.getString("key_facts"));
                    node.forEach(f -> facts.add(f.asText()));
                    return new IssueCandidate(rs.getLong("id"), rs.getString("title"), rs.getString("summary"), facts,
                            Sql.odt(rs, "first_published_at"), Sql.odt(rs, "last_updated_at"),
                            rs.getInt("article_count"), rs.getInt("publisher_count"), rs.getDouble("similarity"));
                })
                .list();
    }
}
