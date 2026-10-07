package com.aicontent.platform.news;

import com.aicontent.platform.common.Sql;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NewsArticleRepository {

    public record NewArticle(long sourceId, String title, String originalUrl, String normalizedUrl, String titleHash,
                             String publisherName, String author, String category, OffsetDateTime publishedAt,
                             String publishedAtRaw, String analysisText) {}

    private final JdbcClient jdbc;

    public NewsArticleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An earlier, non-duplicate article from the same publisher with the same normalized-title hash, collected
     * within the window. Returns the oldest one so duplicates always point at the original.
     */
    public Optional<Long> findTitleDuplicate(String publisherName, String titleHash, int windowDays) {
        return jdbc.sql("""
                SELECT id FROM news_article
                WHERE lower(publisher_name) = lower(:publisher) AND title_hash = :hash
                  AND status <> 'DUPLICATE'
                  AND collected_at >= now() - make_interval(days => :days)
                ORDER BY id LIMIT 1""")
                .param("publisher", publisherName).param("hash", titleHash).param("days", windowDays)
                .query(Long.class).optional();
    }

    /** @return the new id, or empty when the normalized URL already exists (URL duplicate). */
    public Optional<Long> insertIfNewUrl(NewArticle a, String status, Long duplicateOfId) {
        return jdbc.sql("""
                INSERT INTO news_article (source_id, title, original_url, normalized_url, title_hash, publisher_name,
                    author, category, published_at, published_at_raw, analysis_text, status, duplicate_of_article_id)
                VALUES (:source, :title, :orig, :norm, :hash, :publisher, :author, :category, :publishedAt, :raw,
                    :text, :status, :dupOf)
                ON CONFLICT (normalized_url) DO NOTHING
                RETURNING id""")
                .param("source", a.sourceId()).param("title", a.title()).param("orig", a.originalUrl())
                .param("norm", a.normalizedUrl()).param("hash", a.titleHash()).param("publisher", a.publisherName())
                .param("author", a.author()).param("category", a.category()).param("publishedAt", a.publishedAt())
                .param("raw", a.publishedAtRaw()).param("text", a.analysisText()).param("status", status)
                .param("dupOf", duplicateOfId)
                .query(Long.class).optional();
    }

    /** Columns the AI pipeline needs; the embedding itself is never loaded into Java. */
    public record ArticleRow(long id, long sourceId, String title, String publisherName, String category,
                             OffsetDateTime publishedAt, String analysisText, String status,
                             String classificationStatus, String analysisJson, boolean hasEmbedding) {}

    public Optional<ArticleRow> findRow(long id) {
        return jdbc.sql("""
                SELECT id, source_id, title, publisher_name, category, published_at, analysis_text, status,
                       classification_status, CAST(analysis_json AS text) AS analysis_json,
                       (embedding IS NOT NULL) AS has_embedding
                FROM news_article WHERE id = :id""")
                .param("id", id)
                .query((rs, n) -> new ArticleRow(rs.getLong("id"), rs.getLong("source_id"), rs.getString("title"),
                        rs.getString("publisher_name"), rs.getString("category"), Sql.odt(rs, "published_at"),
                        rs.getString("analysis_text"), rs.getString("status"), rs.getString("classification_status"),
                        rs.getString("analysis_json"), rs.getBoolean("has_embedding")))
                .optional();
    }

    public void saveAnalysis(long id, String analysisJson, String promptVersion) {
        jdbc.sql("""
                UPDATE news_article SET analysis_json = CAST(:json AS jsonb), analysis_prompt_version = :pv,
                    analyzed_at = now(), analysis_error = NULL, status = 'ANALYZED'
                WHERE id = :id AND status <> 'DUPLICATE'""")
                .param("json", analysisJson).param("pv", promptVersion).param("id", id).update();
    }

    /** Keeps an earlier successful analysis usable: only an article that never had one becomes FAILED. */
    public void markAnalysisFailed(long id, String error) {
        jdbc.sql("""
                UPDATE news_article SET analysis_error = :error,
                    status = CASE WHEN analysis_json IS NULL AND status <> 'DUPLICATE' THEN 'FAILED' ELSE status END
                WHERE id = :id""")
                .param("error", error == null || error.length() <= 1000 ? error : error.substring(0, 1000))
                .param("id", id).update();
    }

    public void saveEmbedding(long id, float[] vector, String model) {
        jdbc.sql("""
                UPDATE news_article SET embedding = CAST(:vec AS vector), embedding_model = :model, embedded_at = now()
                WHERE id = :id""")
                .param("vec", Sql.vectorLiteral(vector)).param("model", model).param("id", id).update();
    }

    public void setClassificationStatus(long id, String status) {
        jdbc.sql("UPDATE news_article SET classification_status = :s WHERE id = :id")
                .param("s", status).param("id", id).update();
    }

    public void setClassificationStatusBatch(java.util.Collection<Long> ids, String status) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE news_article SET classification_status = :s WHERE id IN (:ids)")
                .param("s", status).param("ids", ids).update();
    }
}
