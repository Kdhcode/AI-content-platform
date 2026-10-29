package com.aicontent.platform.life;

import com.aicontent.platform.common.Sql;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for life_judgment / life_exclusion (V8). Rules and validation live in {@link LifeJudgmentService}. */
@Repository
public class LifeJudgmentRepository {

    /**
     * What a judgment is made from, read in one statement so the snapshot and {@code changeSeq} always describe the
     * same issue state. {@code status} is the issue status at that moment (not part of the snapshot).
     */
    public record JudgmentInput(long issueId, long changeSeq, String status, String snapshotJson, String hash) {}

    public record Judgment(long id, long issueId, long inputChangeSeq, String inputSnapshotJson, String inputHash,
                           boolean lifeRelated, String category, BigDecimal relevanceScore, String reason,
                           String promptVersion, String model, Long sourceJobId, String judgedBy,
                           OffsetDateTime judgedAt) {}

    public record Exclusion(boolean excluded, String reason, String updatedBy, OffsetDateTime updatedAt) {}

    /** The issue's change_seq with its current judgment (null when never judged) and exclusion (null when never set). */
    public record StateRow(long issueId, long issueChangeSeq, Judgment current, Exclusion exclusion) {}

    private static final String JUDGMENT_COLUMNS = """
            j.id, j.issue_id, j.input_change_seq, CAST(j.input_snapshot AS text) AS input_snapshot, j.input_hash,
            j.life_related, j.category, j.relevance_score, j.reason, j.prompt_version, j.model, j.source_job_id,
            j.judged_by, j.judged_at""";

    private final JdbcClient jdbc;

    public LifeJudgmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Version of the snapshot format below. Raise it whenever a field is added, removed or changes meaning. */
    public static final int SNAPSHOT_SCHEMA_VERSION = 1;

    /**
     * The judgment input (snapshot format v1, docs/LIFE_B_PLAN.md §4-1): {@code schemaVersion} plus the issue fields
     * that raise change_seq (title, summary, category, key facts, entities) and the primary member article ids in
     * ascending order. Values are copied as stored: NULL stays JSON null, "" stays "" (never merged).
     * <p>
     * Not in the snapshot, so not in the hash: issue id (stored in its own column), status and change_seq (returned
     * next to it), merge target, aggregates, embedding and article text. Re-analysing an article does not raise
     * change_seq, so article text must not reach the input either.
     * <p>
     * The hash is SHA-256 over the UTF-8 bytes of PostgreSQL's jsonb text output of the snapshot (jsonb sorts keys and
     * normalises whitespace, so equal snapshots always give equal text on the same server version).
     */
    public Optional<JudgmentInput> readInput(long issueId) {
        return jdbc.sql("""
                SELECT i.id, i.change_seq, i.status, CAST(s.snap AS text) AS snap,
                       encode(sha256(convert_to(CAST(s.snap AS text), 'UTF8')), 'hex') AS hash
                FROM issue i
                CROSS JOIN LATERAL (
                    SELECT jsonb_build_object(
                        'schemaVersion', :version,
                        'title', i.title,
                        'summary', i.summary,
                        'category', i.category,
                        'keyFacts', i.key_facts,
                        'entities', i.entities,
                        'articleIds', COALESCE((SELECT jsonb_agg(ia.article_id ORDER BY ia.article_id)
                                                FROM issue_article ia WHERE ia.issue_id = i.id AND ia.is_primary),
                                               '[]'::jsonb)) AS snap) s
                WHERE i.id = :id""")
                .param("id", issueId).param("version", SNAPSHOT_SCHEMA_VERSION)
                .query((rs, n) -> new JudgmentInput(rs.getLong("id"), rs.getLong("change_seq"), rs.getString("status"),
                        rs.getString("snap"), rs.getString("hash")))
                .optional();
    }

    /**
     * The hash {@link #readInput} would give for this snapshot text: SHA-256 of its jsonb text output. Only for text
     * that already parsed as JSON (an invalid cast would abort the caller's transaction).
     */
    public String hashOf(String snapshotJson) {
        return jdbc.sql("SELECT encode(sha256(convert_to(CAST(CAST(:snap AS jsonb) AS text), 'UTF8')), 'hex')")
                .param("snap", snapshotJson).query(String.class).single();
    }

    public Optional<Long> currentChangeSeq(long issueId) {
        return jdbc.sql("SELECT change_seq FROM issue WHERE id = :id").param("id", issueId).query(Long.class).optional();
    }

    /** @return the new row id, or empty when a row for {@code sourceJobId} already exists (a retried job) */
    public Optional<Long> insert(JudgmentInput input, boolean lifeRelated, String category, BigDecimal score,
                                 String reason, String promptVersion, String model, Long sourceJobId, String judgedBy) {
        return jdbc.sql("""
                INSERT INTO life_judgment (issue_id, input_change_seq, input_snapshot, input_hash, life_related, category,
                    relevance_score, reason, prompt_version, model, source_job_id, judged_by)
                VALUES (:issue, :seq, CAST(:snap AS jsonb), :hash, :related, :category, :score, :reason, :prompt,
                    :model, :job, :by)
                ON CONFLICT (source_job_id) DO NOTHING
                RETURNING id""")
                .param("issue", input.issueId()).param("seq", input.changeSeq())
                .param("snap", input.snapshotJson()).param("hash", input.hash())
                .param("related", lifeRelated).param("category", category).param("score", score)
                .param("reason", reason).param("prompt", promptVersion).param("model", model)
                .param("job", sourceJobId).param("by", judgedBy)
                .query(Long.class).optional();
    }

    public Optional<Judgment> findById(long id) {
        return jdbc.sql("SELECT " + JUDGMENT_COLUMNS + " FROM life_judgment j WHERE j.id = :id")
                .param("id", id).query((rs, n) -> judgment(rs)).optional();
    }

    public Optional<Judgment> findBySourceJob(long sourceJobId) {
        return jdbc.sql("SELECT " + JUDGMENT_COLUMNS + " FROM life_judgment j WHERE j.source_job_id = :job")
                .param("job", sourceJobId).query((rs, n) -> judgment(rs)).optional();
    }

    /** Newest first, in the same order that picks the current judgment. */
    public List<Judgment> history(long issueId) {
        return jdbc.sql("SELECT " + JUDGMENT_COLUMNS
                        + " FROM life_judgment j WHERE j.issue_id = :id ORDER BY j.input_change_seq DESC, j.id DESC")
                .param("id", issueId).query((rs, n) -> judgment(rs)).list();
    }

    public Optional<StateRow> state(long issueId) {
        return jdbc.sql("""
                SELECT i.id AS state_issue_id, i.change_seq AS issue_change_seq, c.*,
                       e.excluded AS e_excluded, e.reason AS e_reason, e.updated_by AS e_updated_by,
                       e.updated_at AS e_updated_at
                FROM issue i
                LEFT JOIN LATERAL (
                    SELECT %s FROM life_judgment j WHERE j.issue_id = i.id
                    ORDER BY j.input_change_seq DESC, j.id DESC LIMIT 1) c ON TRUE
                LEFT JOIN life_exclusion e ON e.issue_id = i.id
                WHERE i.id = :id""".formatted(JUDGMENT_COLUMNS))
                .param("id", issueId)
                .query((rs, n) -> new StateRow(rs.getLong("state_issue_id"), rs.getLong("issue_change_seq"),
                        Sql.longOrNull(rs, "id") == null ? null : judgment(rs),
                        rs.getObject("e_excluded") == null ? null : new Exclusion(rs.getBoolean("e_excluded"),
                                rs.getString("e_reason"), rs.getString("e_updated_by"), Sql.odt(rs, "e_updated_at"))))
                .optional();
    }

    /** Row lock on the issue; serialises exclusion changes of one issue (the exclusion row may not exist yet). */
    public boolean lockIssue(long issueId) {
        return jdbc.sql("SELECT id FROM issue WHERE id = :id FOR UPDATE").param("id", issueId)
                .query(Long.class).optional().isPresent();
    }

    public Optional<Exclusion> findExclusion(long issueId) {
        return jdbc.sql("SELECT excluded, reason, updated_by, updated_at FROM life_exclusion WHERE issue_id = :id")
                .param("id", issueId)
                .query((rs, n) -> new Exclusion(rs.getBoolean("excluded"), rs.getString("reason"),
                        rs.getString("updated_by"), Sql.odt(rs, "updated_at")))
                .optional();
    }

    public void saveExclusion(long issueId, boolean excluded, String reason, String actor) {
        jdbc.sql("""
                INSERT INTO life_exclusion (issue_id, excluded, reason, updated_by) VALUES (:id, :excluded, :reason, :by)
                ON CONFLICT (issue_id) DO UPDATE
                SET excluded = EXCLUDED.excluded, reason = EXCLUDED.reason, updated_by = EXCLUDED.updated_by,
                    updated_at = now()""")
                .param("id", issueId).param("excluded", excluded).param("reason", reason).param("by", actor)
                .update();
    }

    private static Judgment judgment(ResultSet rs) throws SQLException {
        return new Judgment(rs.getLong("id"), rs.getLong("issue_id"), rs.getLong("input_change_seq"),
                rs.getString("input_snapshot"), rs.getString("input_hash"), rs.getBoolean("life_related"),
                rs.getString("category"), rs.getBigDecimal("relevance_score"), rs.getString("reason"),
                rs.getString("prompt_version"), rs.getString("model"), Sql.longOrNull(rs, "source_job_id"),
                rs.getString("judged_by"), Sql.odt(rs, "judged_at"));
    }
}
