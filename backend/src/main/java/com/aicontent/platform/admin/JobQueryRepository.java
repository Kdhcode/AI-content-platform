package com.aicontent.platform.admin;

import com.aicontent.platform.common.Json;
import com.aicontent.platform.common.PageParams;
import com.aicontent.platform.common.PageResponse;
import com.aicontent.platform.common.Sql;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobQueryRepository {

    public static final Map<String, String> SORTS = Map.of("id", "j.id", "requestedAt", "j.requested_at",
            "finishedAt", "j.finished_at");

    private final JdbcClient jdbc;
    private final Json json;

    public JobQueryRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public PageResponse<JobView> list(String status, String type, Long articleId, PageParams page) {
        QueryHelper q = new QueryHelper();
        if (status != null) {
            q.add("j.status = :status", "status", status);
        }
        if (type != null) {
            q.add("j.job_type = :type", "type", type);
        }
        if (articleId != null) {
            q.add("(j.payload ->> 'articleId') = :articleId", "articleId", String.valueOf(articleId));
        }
        String from = " FROM async_job j" + q.where();
        long total = jdbc.sql("SELECT count(*)" + from).params(q.params()).query(Long.class).single();
        List<JobView> items = jdbc.sql("""
                SELECT j.id, j.job_type, j.status, CAST(j.payload AS text) AS payload, CAST(j.result AS text) AS result,
                       j.retry_count, j.max_retries, j.error_code, j.error_message, j.requested_by, j.requested_at,
                       j.started_at, j.finished_at, j.correlation_id""" + from
                        + " ORDER BY " + page.orderBy() + " LIMIT :limit OFFSET :offset")
                .params(q.params()).param("limit", page.size()).param("offset", page.offset())
                .query((rs, n) -> new JobView(rs.getLong("id"), rs.getString("job_type"), rs.getString("status"),
                        json.parseOrNull(rs.getString("payload")), json.parseOrNull(rs.getString("result")),
                        rs.getInt("retry_count"), rs.getInt("max_retries"), rs.getString("error_code"),
                        rs.getString("error_message"), rs.getString("requested_by"), Sql.odt(rs, "requested_at"),
                        Sql.odt(rs, "started_at"), Sql.odt(rs, "finished_at"), rs.getString("correlation_id")))
                .list();
        return PageResponse.of(items, total, page);
    }
}
