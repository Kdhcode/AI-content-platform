package com.aicontent.platform.content;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sources and related URLs of a content. Every lookup takes the content id together with the item id, so an item of
 * another content is simply "not found". Callers hold the content row lock (ContentChanges).
 */
@Repository
class ContentItemRepository {

    record SourceRow(long id, String kind, Long issueId, Long newsArticleId, String externalUrl, String label, String role) {}

    record LinkRow(long id, String linkType, String title, String url, boolean siteVisible, boolean blogVisible,
                   String displayPosition, int sortOrder) {}

    private final JdbcClient jdbc;

    ContentItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- sources ---

    Optional<SourceRow> findSource(long contentId, long sourceId) {
        return jdbc.sql("""
                SELECT id, source_kind, issue_id, news_article_id, external_url, source_label, source_role
                FROM content_source WHERE id = :sid AND content_id = :cid""")
                .param("sid", sourceId).param("cid", contentId)
                .query((rs, n) -> new SourceRow(rs.getLong("id"), rs.getString("source_kind"), (Long) rs.getObject("issue_id"),
                        (Long) rs.getObject("news_article_id"), rs.getString("external_url"), rs.getString("source_label"),
                        rs.getString("source_role")))
                .optional();
    }

    boolean sourceExists(long contentId, String kind, Long issueId, Long articleId, String url) {
        return jdbc.sql("""
                SELECT count(*) FROM content_source WHERE content_id = :cid AND source_kind = :kind
                  AND (issue_id = :issue OR news_article_id = :article OR external_url = :url)""")
                .param("cid", contentId).param("kind", kind).param("issue", issueId).param("article", articleId)
                .param("url", url).query(Long.class).single() > 0;
    }

    long insertSource(long contentId, String kind, Long issueId, Long articleId, String url, String label, String role,
                      String actor) {
        return jdbc.sql("""
                INSERT INTO content_source (content_id, source_kind, issue_id, news_article_id, external_url, source_label,
                                            source_role, source_checked_at, created_by)
                VALUES (:cid, :kind, :issue, :article, :url, :label, :role, now(), :actor) RETURNING id""")
                .param("cid", contentId).param("kind", kind).param("issue", issueId).param("article", articleId)
                .param("url", url).param("label", label).param("role", role).param("actor", actor)
                .query(Long.class).single();
    }

    void updateSource(long sourceId, String role, String label) {
        jdbc.sql("UPDATE content_source SET source_role = :role, source_label = :label WHERE id = :id")
                .param("role", role).param("label", label).param("id", sourceId).update();
    }

    void deleteSource(long sourceId) {
        jdbc.sql("DELETE FROM content_source WHERE id = :id").param("id", sourceId).update();
    }

    /** Issue status for a referenced issue, empty when it does not exist. */
    Optional<String> issueStatus(long issueId) {
        return jdbc.sql("SELECT status FROM issue WHERE id = :id").param("id", issueId).query(String.class).optional();
    }

    /** Duplicate-of id (0 when not a duplicate) for a referenced article, empty when it does not exist. */
    Optional<Long> articleDuplicateOf(long articleId) {
        return jdbc.sql("SELECT coalesce(duplicate_of_article_id, 0) FROM news_article WHERE id = :id").param("id", articleId)
                .query(Long.class).optional();
    }

    // --- related URLs ---

    List<LinkRow> links(long contentId) {
        return jdbc.sql("""
                SELECT id, link_type, title, url, site_visible, blog_visible, display_position, sort_order
                FROM content_link WHERE content_id = :cid ORDER BY sort_order, id""").param("cid", contentId)
                .query((rs, n) -> new LinkRow(rs.getLong("id"), rs.getString("link_type"), rs.getString("title"),
                        rs.getString("url"), rs.getBoolean("site_visible"), rs.getBoolean("blog_visible"),
                        rs.getString("display_position"), rs.getInt("sort_order")))
                .list();
    }

    long linkCount(long contentId) {
        return jdbc.sql("SELECT count(*) FROM content_link WHERE content_id = :cid").param("cid", contentId)
                .query(Long.class).single();
    }

    Optional<LinkRow> findLink(long contentId, long linkId) {
        return links(contentId).stream().filter(l -> l.id() == linkId).findFirst();
    }

    long insertLink(long contentId, LinkRow v) {
        return jdbc.sql("""
                INSERT INTO content_link (content_id, link_type, title, url, site_visible, blog_visible, display_position, sort_order)
                VALUES (:cid, :type, :title, :url, :site, :blog, :pos,
                        (SELECT coalesce(max(sort_order) + 1, 0) FROM content_link WHERE content_id = :cid))
                RETURNING id""")
                .param("cid", contentId).param("type", v.linkType()).param("title", v.title()).param("url", v.url())
                .param("site", v.siteVisible()).param("blog", v.blogVisible()).param("pos", v.displayPosition())
                .query(Long.class).single();
    }

    void updateLink(long linkId, LinkRow v) {
        jdbc.sql("""
                UPDATE content_link SET link_type = :type, title = :title, url = :url, site_visible = :site,
                       blog_visible = :blog, display_position = :pos, updated_at = now()
                WHERE id = :id""")
                .param("type", v.linkType()).param("title", v.title()).param("url", v.url()).param("site", v.siteVisible())
                .param("blog", v.blogVisible()).param("pos", v.displayPosition()).param("id", linkId).update();
    }

    void deleteLink(long linkId) {
        jdbc.sql("DELETE FROM content_link WHERE id = :id").param("id", linkId).update();
    }

    void setSortOrder(long linkId, int sortOrder) {
        jdbc.sql("UPDATE content_link SET sort_order = :o, updated_at = now() WHERE id = :id")
                .param("o", sortOrder).param("id", linkId).update();
    }
}
