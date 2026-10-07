package com.aicontent.platform.admin;

import com.aicontent.platform.common.ApiResponse;
import com.aicontent.platform.config.AppProperties;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only operational overview. Connection credentials and filesystem paths are never returned. */
@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardAdminController {
    public record Counts(long articles, long issues, long reviewIssues, long enabledSources,
                         long successfulJobs, long failedJobs, long waitingJobs, long auditEvents) {}
    public record Stage(String jobType, long total, long success, long failed, long pending, long running) {}
    public record SystemInfo(String database, String postgresVersion, int port, String vectorVersion,
                             String schemaVersion, String aiProvider, boolean schedulerEnabled) {}
    public record Overview(Counts counts, List<Stage> pipeline, SystemInfo system, OffsetDateTime checkedAt) {}
    private final JdbcClient jdbc;
    private final AppProperties props;

    public DashboardAdminController(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @GetMapping
    public ApiResponse<Overview> overview() {
        Counts counts = jdbc.sql("""
                SELECT (SELECT count(*) FROM news_article) AS articles,
                       (SELECT count(*) FROM issue) AS issues,
                       (SELECT count(*) FROM issue WHERE status='REVIEW') AS review_issues,
                       (SELECT count(*) FROM news_source WHERE enabled AND status <> 'DISABLED') AS enabled_sources,
                       (SELECT count(*) FROM async_job WHERE status='SUCCESS') AS successful_jobs,
                       (SELECT count(*) FROM async_job WHERE status='FAILED') AS failed_jobs,
                       (SELECT count(*) FROM async_job WHERE status IN ('PENDING','RUNNING')) AS waiting_jobs,
                       (SELECT count(*) FROM admin_audit_log) AS audit_events
                """).query((rs, n) -> new Counts(rs.getLong("articles"), rs.getLong("issues"),
                        rs.getLong("review_issues"), rs.getLong("enabled_sources"), rs.getLong("successful_jobs"),
                        rs.getLong("failed_jobs"), rs.getLong("waiting_jobs"), rs.getLong("audit_events"))).single();
        List<Stage> pipeline = jdbc.sql("""
                SELECT job_type, count(*) AS total,
                       count(*) FILTER (WHERE status='SUCCESS') AS success,
                       count(*) FILTER (WHERE status='FAILED') AS failed,
                       count(*) FILTER (WHERE status='PENDING') AS pending,
                       count(*) FILTER (WHERE status='RUNNING') AS running
                FROM async_job GROUP BY job_type
                """).query((rs, n) -> new Stage(rs.getString("job_type"), rs.getLong("total"), rs.getLong("success"),
                        rs.getLong("failed"), rs.getLong("pending"), rs.getLong("running"))).list();
        SystemInfo system = jdbc.sql("""
                SELECT current_database() AS database, current_setting('server_version') AS postgres_version,
                       inet_server_port() AS port,
                       (SELECT extversion FROM pg_extension WHERE extname='vector') AS vector_version,
                       (SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1) AS schema_version
                """).query((rs, n) -> new SystemInfo(rs.getString("database"), rs.getString("postgres_version"),
                        rs.getInt("port"), rs.getString("vector_version"), rs.getString("schema_version"),
                        props.ai().provider(), props.news().schedulerEnabled())).single();
        return ApiResponse.ok(new Overview(counts, pipeline, system, OffsetDateTime.now()));
    }
}
