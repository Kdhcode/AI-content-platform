package com.aicontent.platform.it;

import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.job.JobType;
import com.aicontent.platform.job.JobWorker;
import com.aicontent.platform.news.NewsSourceRepository;
import com.aicontent.platform.news.NewsSourceType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests need PostgreSQL with pgvector: {@code docker compose up -d} creates database aicontent_test
 * (see docker/init-test-db.sql). Every test starts from empty tables; the worker is driven synchronously.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired protected JdbcClient jdbc;
    @Autowired protected JobWorker worker;
    @Autowired protected NewsSourceRepository sources;
    @Autowired protected AsyncJobService jobsService;

    @TempDir protected Path tmp;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("""
                TRUNCATE ai_log, ai_job, classification_run, issue_article, issue, news_article, news_source, async_job,
                         admin_audit_log RESTART IDENTITY CASCADE""").update();
    }

    /** Runs jobs until the queue is empty (backoff delays are skipped so retries run immediately). */
    protected int drain() {
        int total = 0;
        for (int round = 0; round < 100; round++) {
            jdbc.sql("UPDATE async_job SET available_at = now() WHERE status = 'PENDING'").update();
            int n = worker.runPending(20);
            if (n == 0) {
                return total;
            }
            total += n;
        }
        throw new IllegalStateException("queue did not drain in 100 rounds");
    }

    /** Creates a FIXTURE source reading the given JSON array (content written by the test itself). */
    protected long fixtureSource(String name, String jsonArray) throws IOException {
        Path file = tmp.resolve(name + ".json");
        Files.writeString(file, jsonArray);
        sources.upsertDefinition(name, NewsSourceType.FIXTURE, file.toString(), true, 600, Map.of("publisher", name));
        return jdbc.sql("SELECT id FROM news_source WHERE name = :n").param("n", name).query(Long.class).single();
    }

    protected static String article(String title, String url, String publisher, String publishedAt) {
        return """
                {"title":"%s","url":"%s","publisher":"%s","publishedAt":"%s","text":""}""".formatted(title, url, publisher, publishedAt);
    }

    protected long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /**
     * Collects three articles through the whole chain (stub AI): articles 1+2 describe one event, article 3 another.
     * Result: issue #1 with articles 1,2 and issue #2 with article 3.
     */
    protected long collectSample() throws IOException {
        long id = fixtureSource("src-a", "[" + String.join(",",
                article("가상시 A동 상가 화재 발생 주민 대피", "https://example.test/n/1", "가상일보", "2026-10-01T09:00:00+09:00"),
                article("가상시 A동 상가 화재 발생 소방 출동", "https://example.test/n/2", "샘플뉴스", "2026-10-01T10:00:00+09:00"),
                article("허구군 물류창고 정전 사고 복구 완료", "https://example.test/n/3", "예시방송", "2026-10-01T11:00:00+09:00")) + "]");
        jobsService.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", id), "t", "collect:" + id, null);
        drain();
        return id;
    }
}
