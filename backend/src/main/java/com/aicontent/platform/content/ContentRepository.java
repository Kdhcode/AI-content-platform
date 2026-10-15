package com.aicontent.platform.content;

import com.aicontent.platform.common.Json;
import com.aicontent.platform.common.PageParams;
import com.aicontent.platform.common.PageResponse;
import com.aicontent.platform.common.Sql;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContentRepository {

    public static final Map<String, String> SORTS = Map.of(
            "updatedAt", "c.updated_at", "createdAt", "c.created_at", "title", "c.title", "id", "c.id");

    public record Filter(String type, String status, Long issueId, String q) {}

    public record ListItem(long id, String contentType, String status, String title, long version, long originIssueId,
                           String originIssueTitle, String originIssueStatus, String updatedBy, OffsetDateTime updatedAt) {}

    public record OriginIssue(long id, String title, String status, Long mergedIntoIssueId) {}

    public record Source(long id, String kind, Long issueId, Long newsArticleId, String externalUrl, String label,
                         String role, OffsetDateTime checkedAt) {}

    public record Detail(long id, String contentType, String status, String title, JsonNode body, long version,
                         OriginIssue originIssue, OffsetDateTime originCheckedAt, List<Source> sources, String statusReason,
                         String createdBy, OffsetDateTime createdAt, String updatedBy, OffsetDateTime updatedAt,
                         OffsetDateTime submittedAt, String approvedBy, OffsetDateTime approvedAt) {}

    /** What a create request asked for; replays with the same key must match it. */
    public record CreationRecord(long id, String fingerprint) {}

    /** Current state read under a row lock (FOR UPDATE) before a change. */
    record Locked(long id, String status, String title, JsonNode body, long version, String updatedBy,
                  OffsetDateTime updatedAt) {}

    private final JdbcClient jdbc;
    private final Json json;

    public ContentRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @return the new id, or empty when another request already used this key (its row is then visible: see below) */
    Optional<Long> insertIfNewKey(String type, String title, JsonNode body, long issueId, String key, String fingerprint,
                                  String actor) {
        // ON CONFLICT waits for a concurrent insert with the same key to commit, then does nothing: exactly one row.
        return jdbc.sql("""
                INSERT INTO content (content_type, title, body, origin_issue_id, creation_key, creation_fingerprint,
                                     created_by, updated_by)
                VALUES (:type, :title, CAST(:body AS jsonb), :issue, :key, :fp, :actor, :actor)
                ON CONFLICT (creation_key) DO NOTHING
                RETURNING id""")
                .param("type", type).param("title", title).param("body", json.write(body)).param("issue", issueId)
                .param("key", key).param("fp", fingerprint).param("actor", actor)
                .query(Long.class).optional();
    }

    Optional<CreationRecord> findByCreationKey(String key) {
        return jdbc.sql("SELECT id, creation_fingerprint FROM content WHERE creation_key = :key").param("key", key)
                .query((rs, n) -> new CreationRecord(rs.getLong("id"), rs.getString("creation_fingerprint"))).optional();
    }

    void insertPrimaryIssueSource(long contentId, long issueId, String actor) {
        jdbc.sql("""
                INSERT INTO content_source (content_id, source_kind, issue_id, source_role, source_checked_at, created_by)
                VALUES (:content, 'ISSUE', :issue, 'PRIMARY', now(), :actor)""")
                .param("content", contentId).param("issue", issueId).param("actor", actor).update();
    }

    Optional<Locked> lock(long id) {
        return jdbc.sql("""
                SELECT id, status, title, CAST(body AS text) AS body, version, updated_by, updated_at
                FROM content WHERE id = :id FOR UPDATE""").param("id", id)
                .query((rs, n) -> new Locked(rs.getLong("id"), rs.getString("status"), rs.getString("title"),
                        json.parse(rs.getString("body")), rs.getLong("version"), rs.getString("updated_by"),
                        Sql.odt(rs, "updated_at")))
                .optional();
    }

    /**
     * Saves title and body and increments the version, only if the version is still the expected one (the caller
     * holds the row lock, so this is a guard, not the conflict check). A REVIEW or APPROVED content goes back to DRAFT
     * when {@code backToDraft}: approval and review request are cleared.
     */
    int update(long id, long expectedVersion, String title, JsonNode body, boolean backToDraft, String actor) {
        return jdbc.sql("""
                UPDATE content SET title = :title, body = CAST(:body AS jsonb), version = version + 1,
                       updated_by = :actor, updated_at = now(),
                       status = CASE WHEN :draft THEN 'DRAFT' ELSE status END,
                       submitted_at = CASE WHEN :draft THEN NULL ELSE submitted_at END,
                       approved_by = CASE WHEN :draft THEN NULL ELSE approved_by END,
                       approved_at = CASE WHEN :draft THEN NULL ELSE approved_at END,
                       status_reason = CASE WHEN :draft THEN NULL ELSE status_reason END
                WHERE id = :id AND version = :version""")
                .param("title", title).param("body", json.write(body)).param("actor", actor).param("draft", backToDraft)
                .param("id", id).param("version", expectedVersion).update();
    }

    /**
     * Applies a status action decided by {@link ContentStatusPolicy} and increments the version, guarded by the expected
     * version (the caller holds the row lock). {@code reason} is stored as status_reason when the rule says so.
     */
    int transition(long id, long expectedVersion, ContentStatusPolicy.Rule rule, String reason, String actor) {
        return jdbc.sql("""
                UPDATE content SET status = :to, version = version + 1, updated_by = :actor, updated_at = now(),
                       status_reason = :reason,
                       submitted_at = CASE :sub WHEN 'SET' THEN now() WHEN 'CLEAR' THEN NULL ELSE submitted_at END,
                       approved_by  = CASE :app WHEN 'SET' THEN :actor WHEN 'CLEAR' THEN NULL ELSE approved_by END,
                       approved_at  = CASE :app WHEN 'SET' THEN now() WHEN 'CLEAR' THEN NULL ELSE approved_at END
                WHERE id = :id AND version = :version""")
                .param("to", rule.to()).param("actor", actor).param("reason", rule.storesReason() ? reason : null)
                .param("sub", rule.submitted().name()).param("app", rule.approved().name())
                .param("id", id).param("version", expectedVersion).update();
    }

    long primarySourceCount(long contentId) {
        return jdbc.sql("SELECT count(*) FROM content_source WHERE content_id = :id AND source_role = 'PRIMARY'")
                .param("id", contentId).query(Long.class).single();
    }

    public PageResponse<ListItem> list(Filter f, PageParams page) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (f.type() != null) { where.add("c.content_type = :type"); params.put("type", f.type()); }
        if (f.status() != null) { where.add("c.status = :status"); params.put("status", f.status()); }
        if (f.issueId() != null) { where.add("c.origin_issue_id = :issue"); params.put("issue", f.issueId()); }
        if (f.q() != null && !f.q().isBlank()) {
            where.add("c.title ILIKE :q ESCAPE '\\'");
            params.put("q", Sql.likeContains(f.q().trim()));
        }
        String from = " FROM content c JOIN issue i ON i.id = c.origin_issue_id"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where));
        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        List<ListItem> items = jdbc.sql("""
                SELECT c.id, c.content_type, c.status, c.title, c.version, c.origin_issue_id, i.title AS issue_title,
                       i.status AS issue_status, c.updated_by, c.updated_at""" + from
                        + " ORDER BY " + page.orderBy() + " LIMIT :limit OFFSET :offset")
                .params(params).param("limit", page.size()).param("offset", page.offset())
                .query((rs, n) -> new ListItem(rs.getLong("id"), rs.getString("content_type"), rs.getString("status"),
                        rs.getString("title"), rs.getLong("version"), rs.getLong("origin_issue_id"),
                        rs.getString("issue_title"), rs.getString("issue_status"), rs.getString("updated_by"),
                        Sql.odt(rs, "updated_at")))
                .list();
        return PageResponse.of(items, total, page);
    }

    public Optional<Detail> detail(long id) {
        List<Source> sources = jdbc.sql("""
                SELECT id, source_kind, issue_id, news_article_id, external_url, source_label, source_role, source_checked_at
                FROM content_source WHERE content_id = :id
                ORDER BY CASE source_role WHEN 'PRIMARY' THEN 0 WHEN 'SUPPORTING' THEN 1 ELSE 2 END, id""")
                .param("id", id)
                .query((rs, n) -> new Source(rs.getLong("id"), rs.getString("source_kind"), Sql.longOrNull(rs, "issue_id"),
                        Sql.longOrNull(rs, "news_article_id"), rs.getString("external_url"), rs.getString("source_label"),
                        rs.getString("source_role"), Sql.odt(rs, "source_checked_at")))
                .list();
        return jdbc.sql("""
                SELECT c.id, c.content_type, c.status, c.title, CAST(c.body AS text) AS body, c.version, c.origin_issue_id,
                       i.title AS issue_title, i.status AS issue_status, i.merged_into_issue_id, c.origin_checked_at,
                       c.status_reason, c.created_by, c.created_at, c.updated_by, c.updated_at, c.submitted_at,
                       c.approved_by, c.approved_at
                FROM content c JOIN issue i ON i.id = c.origin_issue_id WHERE c.id = :id""").param("id", id)
                .query((rs, n) -> new Detail(rs.getLong("id"), rs.getString("content_type"), rs.getString("status"),
                        rs.getString("title"), json.parse(rs.getString("body")), rs.getLong("version"),
                        new OriginIssue(rs.getLong("origin_issue_id"), rs.getString("issue_title"),
                                rs.getString("issue_status"), Sql.longOrNull(rs, "merged_into_issue_id")),
                        Sql.odt(rs, "origin_checked_at"), sources, rs.getString("status_reason"),
                        rs.getString("created_by"), Sql.odt(rs, "created_at"), rs.getString("updated_by"),
                        Sql.odt(rs, "updated_at"), Sql.odt(rs, "submitted_at"), rs.getString("approved_by"),
                        Sql.odt(rs, "approved_at")))
                .optional();
    }
}
