package com.aicontent.platform.news;

import com.aicontent.platform.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.aicontent.platform.common.Sql;

@Repository
public class NewsSourceRepository {

    private final JdbcClient jdbc;
    private final Json json;

    public NewsSourceRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private NewsSource map(ResultSet rs, int row) throws SQLException {
        Map<String, String> config = json.mapper().convertValue(
                json.parse(rs.getString("config")), new TypeReference<Map<String, String>>() {});
        return new NewsSource(rs.getLong("id"), rs.getString("name"), NewsSourceType.valueOf(rs.getString("source_type")),
                rs.getString("base_url"), rs.getBoolean("enabled"), rs.getString("status"),
                rs.getInt("collection_interval_seconds"), config,
                Sql.odt(rs, "last_attempt_at"), Sql.odt(rs, "last_success_at"),
                rs.getInt("failure_count"), rs.getString("last_error"));
    }

    private static final String COLUMNS = """
            id, name, source_type, base_url, enabled, status, collection_interval_seconds,
            CAST(config AS text) AS config, last_attempt_at, last_success_at, failure_count, last_error""";

    public Optional<NewsSource> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM news_source WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public List<NewsSource> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM news_source ORDER BY id").query(this::map).list();
    }

    /**
     * Sources whose next run is due. A failing source is polled less often: the interval is multiplied by
     * (1 + min(failure_count, 5)) so a broken feed is not hammered. DISABLED sources are never due.
     */
    public List<Long> findDueIds() {
        return jdbc.sql("""
                SELECT id FROM news_source
                WHERE enabled AND status <> 'DISABLED'
                  AND (last_attempt_at IS NULL
                       OR last_attempt_at + make_interval(secs => collection_interval_seconds * (1 + LEAST(failure_count, 5))) <= now())
                ORDER BY last_attempt_at NULLS FIRST, id""").query(Long.class).list();
    }

    /** Upsert by unique name; never changes failure bookkeeping. */
    public void upsertDefinition(String name, NewsSourceType type, String baseUrl, boolean enabled, int intervalSeconds,
                                 Map<String, String> config) {
        jdbc.sql("""
                INSERT INTO news_source (name, source_type, base_url, enabled, collection_interval_seconds, config)
                VALUES (:name, :type, :url, :enabled, :interval, CAST(:config AS jsonb))
                ON CONFLICT (name) DO UPDATE SET source_type = EXCLUDED.source_type, base_url = EXCLUDED.base_url,
                    enabled = EXCLUDED.enabled, collection_interval_seconds = EXCLUDED.collection_interval_seconds,
                    config = EXCLUDED.config""")
                .param("name", name).param("type", type.name()).param("url", baseUrl).param("enabled", enabled)
                .param("interval", intervalSeconds).param("config", json.write(config == null ? Map.of() : config))
                .update();
    }

    public void markAttempt(long id) {
        jdbc.sql("UPDATE news_source SET last_attempt_at = now() WHERE id = :id").param("id", id).update();
    }

    public void markSuccess(long id) {
        jdbc.sql("""
                UPDATE news_source SET last_success_at = now(), failure_count = 0, last_error = NULL,
                    status = CASE WHEN status = 'DISABLED' THEN status ELSE 'ACTIVE' END
                WHERE id = :id""").param("id", id).update();
    }

    public void markFailure(long id, String error, int degradedAfter) {
        jdbc.sql("""
                UPDATE news_source SET failure_count = failure_count + 1, last_error = :error,
                    status = CASE WHEN status = 'DISABLED' THEN status
                                  WHEN failure_count + 1 >= :degradedAfter THEN 'DEGRADED' ELSE status END
                WHERE id = :id""")
                .param("id", id).param("error", truncate(error)).param("degradedAfter", degradedAfter).update();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
