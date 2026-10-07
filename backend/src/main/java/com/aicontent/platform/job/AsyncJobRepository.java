package com.aicontent.platform.job;

import com.aicontent.platform.common.Json;
import com.aicontent.platform.common.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All state transitions of ASYNC_JOB. Every transition that a worker performs is guarded by
 * {@code status = 'RUNNING' AND locked_by = :worker}, so a worker that lost its job (stale recovery
 * handed it to someone else) can never overwrite the new owner's result.
 */
@Repository
public class AsyncJobRepository {

    private static final List<String> COLUMN_LIST = List.of(
            "id", "job_type", "status", "payload", "result", "requested_by", "requested_at", "available_at",
            "started_at", "finished_at", "heartbeat_at", "locked_by", "retry_count", "max_retries",
            "error_code", "error_message", "correlation_id", "dedupe_key", "created_at", "updated_at");
    private static final String COLUMNS = String.join(", ", COLUMN_LIST);
    private static final String COLUMNS_J = COLUMN_LIST.stream().map(c -> "j." + c).collect(Collectors.joining(", "));

    private final JdbcClient jdbc;
    private final Json json;

    public AsyncJobRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record NewJob(
            String jobType, String payloadJson, String requestedBy, int maxRetries, String correlationId, String dedupeKey) {}

    public record RecoveredJob(long id, AsyncJobStatus status) {}

    /** Inserts the job unless an active (PENDING/RUNNING) job with the same dedupe key exists. */
    public Optional<AsyncJob> insertIfNoActiveDuplicate(NewJob job) {
        return jdbc.sql("""
                        INSERT INTO async_job (job_type, payload, requested_by, max_retries, correlation_id, dedupe_key)
                        VALUES (:type, CAST(:payload AS jsonb), :by, :max, :corr, CAST(:dedupe AS text))
                        ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL AND status IN ('PENDING', 'RUNNING')
                        DO NOTHING
                        RETURNING\s""" + COLUMNS)
                .param("type", job.jobType())
                .param("payload", job.payloadJson())
                .param("by", job.requestedBy())
                .param("max", job.maxRetries())
                .param("corr", job.correlationId())
                .param("dedupe", job.dedupeKey())
                .query(this::map)
                .optional();
    }

    public Optional<AsyncJob> findActiveByDedupeKey(String dedupeKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM async_job WHERE dedupe_key = :k AND status IN ('PENDING', 'RUNNING')")
                .param("k", dedupeKey)
                .query(this::map)
                .optional();
    }

    public Optional<AsyncJob> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM async_job WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** Most recent jobs whose payload references the article (for the admin article detail). */
    public List<AsyncJob> findRecentByArticleId(long articleId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM async_job WHERE (payload ->> 'articleId') = :a ORDER BY id DESC LIMIT :n")
                .param("a", Long.toString(articleId))
                .param("n", limit)
                .query(this::map)
                .list();
    }

    /** Atomically claims up to {@code limit} due PENDING jobs; concurrent workers never receive the same job. */
    public List<AsyncJob> claimNext(String workerId, Collection<String> types, int limit) {
        if (types.isEmpty() || limit <= 0) {
            return List.of();
        }
        return jdbc.sql("""
                        WITH next AS (
                            SELECT id FROM async_job
                            WHERE status = 'PENDING' AND available_at <= now() AND job_type IN (:types)
                            ORDER BY available_at, id
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE async_job j
                        SET status = 'RUNNING', started_at = now(), heartbeat_at = now(), locked_by = :worker
                        FROM next
                        WHERE j.id = next.id
                        RETURNING\s""" + COLUMNS_J)
                .param("types", types)
                .param("limit", limit)
                .param("worker", workerId)
                .query(this::map)
                .list()
                .stream()
                .sorted(Comparator.comparingLong(AsyncJob::id))
                .toList();
    }

    public int heartbeat(Collection<Long> ids, String workerId) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE async_job SET heartbeat_at = now() WHERE id IN (:ids) AND status = 'RUNNING' AND locked_by = :w")
                .param("ids", ids)
                .param("w", workerId)
                .update();
    }

    public boolean markSuccess(long id, String workerId, String resultJson) {
        return jdbc.sql("""
                        UPDATE async_job
                        SET status = 'SUCCESS', finished_at = now(), result = CAST(:result AS jsonb),
                            error_code = NULL, error_message = NULL, locked_by = NULL
                        WHERE id = :id AND status = 'RUNNING' AND locked_by = :w""")
                .param("id", id)
                .param("w", workerId)
                .param("result", resultJson)
                .update() == 1;
    }

    /**
     * Records a failure. If {@code retryable} and retries remain the job goes back to PENDING after an
     * exponential delay (base * 2^retry_count, capped); otherwise it becomes FAILED.
     *
     * @return the new status, or empty if this worker no longer owns the job
     */
    public Optional<AsyncJobStatus> markFailure(long id, String workerId, String errorCode, String errorMessage,
                                                boolean retryable, long baseDelaySeconds, long maxDelaySeconds) {
        return jdbc.sql("""
                        UPDATE async_job SET
                            status       = CASE WHEN :retryable AND retry_count < max_retries THEN 'PENDING' ELSE 'FAILED' END,
                            retry_count  = CASE WHEN :retryable AND retry_count < max_retries THEN retry_count + 1 ELSE retry_count END,
                            available_at = CASE WHEN :retryable AND retry_count < max_retries
                                                THEN now() + make_interval(secs => CAST(LEAST(:maxDelay, :baseDelay * power(2, retry_count)) AS double precision))
                                                ELSE available_at END,
                            finished_at  = CASE WHEN :retryable AND retry_count < max_retries THEN NULL ELSE now() END,
                            error_code   = :code,
                            error_message = :msg,
                            locked_by    = NULL
                        WHERE id = :id AND status = 'RUNNING' AND locked_by = :w
                        RETURNING status""")
                .param("id", id)
                .param("w", workerId)
                .param("code", errorCode)
                .param("msg", errorMessage)
                .param("retryable", retryable)
                .param("baseDelay", baseDelaySeconds)
                .param("maxDelay", maxDelaySeconds)
                .query((rs, i) -> AsyncJobStatus.valueOf(rs.getString("status")))
                .optional();
    }

    /** Re-queues RUNNING jobs whose heartbeat expired (worker crash / restart); FAILED when no retries are left. */
    public List<RecoveredJob> recoverStale(long staleAfterSeconds) {
        return jdbc.sql("""
                        UPDATE async_job SET
                            status        = CASE WHEN retry_count < max_retries THEN 'PENDING' ELSE 'FAILED' END,
                            retry_count   = CASE WHEN retry_count < max_retries THEN retry_count + 1 ELSE retry_count END,
                            available_at  = now(),
                            finished_at   = CASE WHEN retry_count < max_retries THEN NULL ELSE now() END,
                            error_code    = 'WORKER_LOST',
                            error_message = 'worker heartbeat expired (locked_by=' || coalesce(locked_by, '?') || ')',
                            locked_by     = NULL
                        WHERE status = 'RUNNING' AND heartbeat_at < now() - make_interval(secs => CAST(:stale AS double precision))
                        RETURNING id, status""")
                .param("stale", staleAfterSeconds)
                .query((rs, i) -> new RecoveredJob(rs.getLong("id"), AsyncJobStatus.valueOf(rs.getString("status"))))
                .list();
    }

    public boolean cancelPending(long id) {
        return jdbc.sql("""
                        UPDATE async_job SET status = 'CANCELLED', finished_at = now()
                        WHERE id = :id AND status = 'PENDING'""")
                .param("id", id)
                .update() == 1;
    }

    /** Manual retry of a FAILED job: grants {@code extraRetries} more attempts on top of those already used. */
    public boolean requeueFailed(long id, int extraRetries) {
        return jdbc.sql("""
                        UPDATE async_job SET status = 'PENDING', max_retries = retry_count + :extra, available_at = now(),
                            finished_at = NULL, locked_by = NULL
                        WHERE id = :id AND status = 'FAILED'""")
                .param("id", id)
                .param("extra", extraRetries)
                .update() == 1;
    }

    public long countByStatus(AsyncJobStatus status) {
        return jdbc.sql("SELECT count(*) FROM async_job WHERE status = :s")
                .param("s", status.name())
                .query(Long.class)
                .single();
    }

    private AsyncJob map(ResultSet rs, int rowNum) throws SQLException {
        return new AsyncJob(
                rs.getLong("id"),
                rs.getString("job_type"),
                AsyncJobStatus.valueOf(rs.getString("status")),
                json.parseOrNull(rs.getString("payload")),
                json.parseOrNull(rs.getString("result")),
                rs.getString("requested_by"),
                Sql.odt(rs, "requested_at"),
                Sql.odt(rs, "available_at"),
                Sql.odt(rs, "started_at"),
                Sql.odt(rs, "finished_at"),
                Sql.odt(rs, "heartbeat_at"),
                rs.getString("locked_by"),
                rs.getInt("retry_count"),
                rs.getInt("max_retries"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("correlation_id"),
                rs.getString("dedupe_key"),
                Sql.odt(rs, "created_at"),
                Sql.odt(rs, "updated_at"));
    }
}
