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

    /** {@code updateNeeded}: null = all, true = only unacknowledged origin changes, false = only acknowledged ones. */
    public record Filter(String type, String status, Long issueId, String q, Boolean updateNeeded) {}

    /**
     * A6. {@code unacknowledged}: the origin issue changed (issue.change_seq) after what an admin last acknowledged.
     * {@code notice}: the origin issue's present state when it is no longer a live issue (MERGED / EXCLUDED, else
     * null) — shown regardless of acknowledgement. {@code issueChangeSeq} is what acknowledge-origin must send back.
     */
    public record OriginChange(boolean unacknowledged, long issueChangeSeq, long acknowledgedSeq,
                               OffsetDateTime issueChangedAt, OffsetDateTime acknowledgedAt, String issueStatus,
                               String notice, Long mergedIntoIssueId) {}

    public record ListItem(long id, String contentType, String status, String title, long version, long originIssueId,
                           String originIssueTitle, String originIssueStatus, OriginChange originChange, String updatedBy,
                           OffsetDateTime updatedAt) {}

    public record OriginIssue(long id, String title, String status, Long mergedIntoIssueId) {}

    public record Source(long id, String kind, Long issueId, Long newsArticleId, String externalUrl, String label,
                         String role, OffsetDateTime checkedAt) {}

    public record Link(long id, String linkType, String title, String url, boolean siteVisible, boolean blogVisible,
                       String displayPosition, int sortOrder, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    /** {@code sources}: PRIMARY, SUPPORTING, REFERENCE, then id. {@code links}: sort order, then id. */
    public record Detail(long id, String contentType, String status, String title, JsonNode body, long version,
                         OriginIssue originIssue, OffsetDateTime originCheckedAt, OriginChange originChange,
                         List<Source> sources, List<Link> links,
                         String statusReason,
                         String createdBy, OffsetDateTime createdAt, String updatedBy, OffsetDateTime updatedAt,
                         OffsetDateTime submittedAt, String approvedBy, OffsetDateTime approvedAt) {}

    /** What a create request asked for; replays with the same key must match it. */
    public record CreationRecord(long id, String fingerprint) {}

    /** Current state read under a row lock (FOR UPDATE) before a change. */
    record Locked(long id, String status, String title, JsonNode body, long version, String updatedBy,
                  OffsetDateTime updatedAt, long originIssueId, long originAckSeq) {}

    /** The origin issue read with a share lock: its changes wait until the acknowledging transaction ends. */
    record OriginState(long changeSeq, OffsetDateTime changedAt, String status, Long mergedIntoIssueId) {}

    /** The one rule for "origin changed, not yet acknowledged", used by list, filter and detail alike. */
    static final String UNACKNOWLEDGED_SQL = "(i.change_seq > c.origin_ack_seq)";
    private static final String ORIGIN_COLUMNS = """
            i.change_seq AS issue_change_seq, i.change_seq_at AS issue_changed_at, c.origin_ack_seq, c.origin_checked_at,
            i.status AS issue_status, i.merged_into_issue_id, """ + UNACKNOWLEDGED_SQL + " AS unacknowledged";

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
                INSERT INTO content (content_type, title, body, origin_issue_id, origin_ack_seq, creation_key,
                                     creation_fingerprint, created_by, updated_by)
                VALUES (:type, :title, CAST(:body AS jsonb), :issue, (SELECT change_seq FROM issue WHERE id = :issue),
                        :key, :fp, :actor, :actor)
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
                SELECT id, status, title, CAST(body AS text) AS body, version, updated_by, updated_at, origin_issue_id,
                       origin_ack_seq
                FROM content WHERE id = :id FOR UPDATE""").param("id", id)
                .query((rs, n) -> new Locked(rs.getLong("id"), rs.getString("status"), rs.getString("title"),
                        json.parse(rs.getString("body")), rs.getLong("version"), rs.getString("updated_by"),
                        Sql.odt(rs, "updated_at"), rs.getLong("origin_issue_id"), rs.getLong("origin_ack_seq")))
                .optional();
    }

    /** Lock order everywhere: content row (FOR UPDATE) first, then its origin issue. */
    OriginState shareLockOrigin(long issueId) {
        return jdbc.sql("""
                SELECT change_seq, change_seq_at, status, merged_into_issue_id FROM issue WHERE id = :id FOR SHARE""")
                .param("id", issueId)
                .query((rs, n) -> new OriginState(rs.getLong("change_seq"), Sql.odt(rs, "change_seq_at"),
                        rs.getString("status"), Sql.longOrNull(rs, "merged_into_issue_id")))
                .single();
    }

    /**
     * Records that an admin acknowledged the origin issue up to {@code changeSeq}: version +1 (editors holding the old
     * version reload), nothing else about the content changes — status and review record stay.
     */
    int acknowledgeOrigin(long id, long expectedVersion, long changeSeq, String actor) {
        return jdbc.sql("""
                UPDATE content SET origin_ack_seq = :seq, origin_checked_at = now(), version = version + 1,
                       updated_by = :actor, updated_at = now()
                WHERE id = :id AND version = :version""")
                .param("seq", changeSeq).param("actor", actor).param("id", id).param("version", expectedVersion).update();
    }

    private static OriginChange originChange(java.sql.ResultSet rs) throws java.sql.SQLException {
        String status = rs.getString("issue_status");
        return new OriginChange(rs.getBoolean("unacknowledged"), rs.getLong("issue_change_seq"), rs.getLong("origin_ack_seq"),
                Sql.odt(rs, "issue_changed_at"), Sql.odt(rs, "origin_checked_at"), status,
                "MERGED".equals(status) || "EXCLUDED".equals(status) ? status : null, Sql.longOrNull(rs, "merged_into_issue_id"));
    }

    void setTitleBody(long id, String title, JsonNode body) {
        jdbc.sql("UPDATE content SET title = :title, body = CAST(:body AS jsonb) WHERE id = :id")
                .param("title", title).param("body", json.write(body)).param("id", id).update();
    }

    /**
     * Records a change of the content or of its sources / links: version +1, who and when, guarded by the expected
     * version (the caller holds the row lock). With {@code backToDraft} a REVIEW or APPROVED content returns to DRAFT and
     * its review record (submitted, approved, status reason) is cleared, the same rule as the A3 return.
     */
    int bump(long id, long expectedVersion, boolean backToDraft, String actor) {
        return jdbc.sql("""
                UPDATE content SET version = version + 1, updated_by = :actor, updated_at = now(),
                       status = CASE WHEN :draft THEN 'DRAFT' ELSE status END,
                       submitted_at = CASE WHEN :draft THEN NULL ELSE submitted_at END,
                       approved_by = CASE WHEN :draft THEN NULL ELSE approved_by END,
                       approved_at = CASE WHEN :draft THEN NULL ELSE approved_at END,
                       status_reason = CASE WHEN :draft THEN NULL ELSE status_reason END
                WHERE id = :id AND version = :version""")
                .param("actor", actor).param("draft", backToDraft).param("id", id).param("version", expectedVersion).update();
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
        if (f.updateNeeded() != null) {
            where.add(f.updateNeeded() ? UNACKNOWLEDGED_SQL : "NOT " + UNACKNOWLEDGED_SQL);
        }
        String from = " FROM content c JOIN issue i ON i.id = c.origin_issue_id"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where));
        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        List<ListItem> items = jdbc.sql("""
                SELECT c.id, c.content_type, c.status, c.title, c.version, c.origin_issue_id, i.title AS issue_title,
                       c.updated_by, c.updated_at, """ + ORIGIN_COLUMNS + from
                        + " ORDER BY " + page.orderBy() + " LIMIT :limit OFFSET :offset")
                .params(params).param("limit", page.size()).param("offset", page.offset())
                .query((rs, n) -> new ListItem(rs.getLong("id"), rs.getString("content_type"), rs.getString("status"),
                        rs.getString("title"), rs.getLong("version"), rs.getLong("origin_issue_id"),
                        rs.getString("issue_title"), rs.getString("issue_status"), originChange(rs), rs.getString("updated_by"),
                        Sql.odt(rs, "updated_at")))
                .list();
        return PageResponse.of(items, total, page);
    }

    /**
     * The whole detail — content row, origin issue, sources and links — in ONE statement. A statement reads one
     * snapshot, so a concurrent commit can never leave a source list from before it next to a version or link list
     * from after it (separate queries could, even inside a READ COMMITTED transaction). Inside a changing transaction
     * the statement sees that transaction's own writes. No extra locks are taken.
     */
    public Optional<Detail> detail(long id) {
        return jdbc.sql("""
                SELECT c.id, c.content_type, c.status, c.title, CAST(c.body AS text) AS body, c.version, c.origin_issue_id,
                       i.title AS issue_title, c.status_reason, c.created_by, c.created_at, c.updated_by, c.updated_at,
                       c.submitted_at, c.approved_by, c.approved_at,
                       (SELECT CAST(coalesce(jsonb_agg(jsonb_build_object(
                                   'id', s.id, 'kind', s.source_kind, 'issueId', s.issue_id, 'newsArticleId', s.news_article_id,
                                   'externalUrl', s.external_url, 'label', s.source_label, 'role', s.source_role,
                                   'checkedAt', s.source_checked_at)
                               ORDER BY CASE s.source_role WHEN 'PRIMARY' THEN 0 WHEN 'SUPPORTING' THEN 1 ELSE 2 END, s.id),
                               '[]'::jsonb) AS text)
                        FROM content_source s WHERE s.content_id = c.id) AS sources_json,
                       (SELECT CAST(coalesce(jsonb_agg(jsonb_build_object(
                                   'id', l.id, 'linkType', l.link_type, 'title', l.title, 'url', l.url,
                                   'siteVisible', l.site_visible, 'blogVisible', l.blog_visible,
                                   'displayPosition', l.display_position, 'sortOrder', l.sort_order,
                                   'createdAt', l.created_at, 'updatedAt', l.updated_at)
                               ORDER BY l.sort_order, l.id), '[]'::jsonb) AS text)
                        FROM content_link l WHERE l.content_id = c.id) AS links_json,
                       """ + ORIGIN_COLUMNS + """

                FROM content c JOIN issue i ON i.id = c.origin_issue_id WHERE c.id = :id""").param("id", id)
                .query((rs, n) -> new Detail(rs.getLong("id"), rs.getString("content_type"), rs.getString("status"),
                        rs.getString("title"), json.parse(rs.getString("body")), rs.getLong("version"),
                        new OriginIssue(rs.getLong("origin_issue_id"), rs.getString("issue_title"),
                                rs.getString("issue_status"), Sql.longOrNull(rs, "merged_into_issue_id")),
                        Sql.odt(rs, "origin_checked_at"), originChange(rs), sources(json.parse(rs.getString("sources_json"))),
                        links(json.parse(rs.getString("links_json"))), rs.getString("status_reason"),
                        rs.getString("created_by"), Sql.odt(rs, "created_at"), rs.getString("updated_by"),
                        Sql.odt(rs, "updated_at"), Sql.odt(rs, "submitted_at"), rs.getString("approved_by"),
                        Sql.odt(rs, "approved_at")))
                .optional();
    }

    private static List<Source> sources(JsonNode array) {
        List<Source> out = new ArrayList<>();
        for (JsonNode s : array) {
            out.add(new Source(s.get("id").asLong(), s.get("kind").asText(), longOrNull(s, "issueId"),
                    longOrNull(s, "newsArticleId"), textOrNull(s, "externalUrl"), textOrNull(s, "label"),
                    s.get("role").asText(), timeOrNull(s, "checkedAt")));
        }
        return out;
    }

    private static List<Link> links(JsonNode array) {
        List<Link> out = new ArrayList<>();
        for (JsonNode l : array) {
            out.add(new Link(l.get("id").asLong(), l.get("linkType").asText(), l.get("title").asText(), l.get("url").asText(),
                    l.get("siteVisible").asBoolean(), l.get("blogVisible").asBoolean(), l.get("displayPosition").asText(),
                    l.get("sortOrder").asInt(), timeOrNull(l, "createdAt"), timeOrNull(l, "updatedAt")));
        }
        return out;
    }

    private static Long longOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asLong();
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** jsonb renders timestamptz as ISO-8601 with offset, e.g. 2026-10-15T17:00:00.123456+09:00. */
    private static OffsetDateTime timeOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : OffsetDateTime.parse(v.asText());
    }
}
