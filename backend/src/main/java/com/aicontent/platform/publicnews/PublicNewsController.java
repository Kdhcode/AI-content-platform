package com.aicontent.platform.publicnews;

import com.aicontent.platform.common.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/** Read-only projections; review items and internal AI/admin metadata never appear here. */
@RestController
@RequestMapping("/api/public")
public class PublicNewsController {
    private final JdbcClient jdbc;
    private static final String VISIBLE = "i.status IN ('ACTIVE', 'CLOSED')";
    private static final String FIELDS = "i.id, i.title, i.summary, i.category, i.status, i.last_updated_at";
    public record Issue(long id, String title, String summary, String category, String status,
                        OffsetDateTime updatedAt, int articleCount, int publisherCount) {}
    public record Article(long id, String title, String publisherName, OffsetDateTime publishedAt, String url) {}
    public record Detail(Issue issue, List<Article> articles) {}

    public PublicNewsController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/issues")
    public ApiResponse<PageResponse<Issue>> issues(@RequestParam(required = false) String q,
            @RequestParam(required = false) String category, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var paging = PageParams.of(page, size, null, Map.of(), "i.last_updated_at DESC NULLS LAST, i.id DESC");
        String search = q == null ? "" : q.trim();
        if (search.length() > 200 || (category != null && category.length() > 100)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var params = Map.of("q", Sql.likeContains(search), "empty", search.isEmpty(),
                "category", category == null ? "" : category);
        String from = " FROM issue i WHERE " + VISIBLE
                + " AND (:empty OR i.title ILIKE :q ESCAPE '\\' OR i.summary ILIKE :q ESCAPE '\\')"
                + " AND (:category = '' OR i.category = :category)";
        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        var items = jdbc.sql("SELECT " + FIELDS + counts() + from + " ORDER BY " + paging.orderBy()
                        + " LIMIT :limit OFFSET :offset")
                .params(params).param("limit", paging.size()).param("offset", paging.offset())
                .query((rs, n) -> new Issue(rs.getLong("id"), rs.getString("title"), rs.getString("summary"),
                        rs.getString("category"), rs.getString("status"), Sql.odt(rs, "last_updated_at"),
                        rs.getInt("article_count"), rs.getInt("publisher_count"))).list();
        return ApiResponse.ok(PageResponse.of(items, total, paging));
    }

    @GetMapping("/categories")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.ok(jdbc.sql("SELECT DISTINCT i.category FROM issue i WHERE " + VISIBLE
                + " AND i.category IS NOT NULL ORDER BY i.category").query(String.class).list());
    }

    @GetMapping("/issues/{id}")
    public ApiResponse<Detail> detail(@PathVariable long id) {
        var issue = jdbc.sql("SELECT " + FIELDS + counts() + " FROM issue i WHERE i.id=:id AND " + VISIBLE)
                .param("id", id).query((rs, n) -> new Issue(rs.getLong("id"), rs.getString("title"),
                        rs.getString("summary"), rs.getString("category"), rs.getString("status"),
                        Sql.odt(rs, "last_updated_at"), rs.getInt("article_count"), rs.getInt("publisher_count")))
                .optional().orElseThrow(() -> new BusinessException(ErrorCode.ISSUE_NOT_FOUND));
        var articles = jdbc.sql("""
                SELECT a.id, a.title, a.publisher_name, a.published_at, a.original_url
                FROM issue_article ia JOIN news_article a ON a.id=ia.article_id
                WHERE ia.issue_id=:id AND ia.is_primary AND a.status = 'ANALYZED' AND a.duplicate_of_article_id IS NULL
                ORDER BY COALESCE(a.published_at, a.collected_at) DESC, a.id DESC
                """).param("id", id).query((rs, n) -> new Article(rs.getLong("id"), rs.getString("title"),
                        rs.getString("publisher_name"), Sql.odt(rs, "published_at"), rs.getString("original_url"))).list();
        return ApiResponse.ok(new Detail(issue, articles));
    }

    private static String counts() {
        String members = " FROM issue_article ia JOIN news_article a ON a.id=ia.article_id"
                + " WHERE ia.issue_id=i.id AND ia.is_primary AND a.status = 'ANALYZED' AND a.duplicate_of_article_id IS NULL";
        return ", (SELECT count(*)" + members + ") AS article_count"
                + ", (SELECT count(DISTINCT a.publisher_name)" + members + ") AS publisher_count";
    }
}
