package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.job.AsyncJobRepository;
import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.job.JobType;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AsyncJobIntegrationTest extends IntegrationTestBase {

    @Autowired AsyncJobService jobs;
    @Autowired AsyncJobRepository repository;

    @Test
    void duplicateEnqueueWhileActiveReturnsTheExistingJob() {
        var first = jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", 1), "t", "collect:1", null);
        var second = jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", 1), "t", "collect:1", null);
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.job().id()).isEqualTo(first.job().id());
        assertThat(count("SELECT count(*) FROM async_job")).isEqualTo(1);
    }

    @Test
    void retryableFailureIsRetriedUpToTheCapThenFailsAndMarksSourceDegraded() {
        // nothing listens on port 1 -> NETWORK error, which is retryable
        sources.upsertDefinition("dead-feed", com.aicontent.platform.news.NewsSourceType.RSS, "http://127.0.0.1:1/feed", true, 600, Map.of());
        long sourceId = jdbc.sql("SELECT id FROM news_source").query(Long.class).single();
        jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", sourceId), "t", "collect:" + sourceId, null);

        drain();

        var row = jdbc.sql("SELECT status, retry_count, max_retries, error_code FROM async_job")
                .query((rs, n) -> Map.of("status", rs.getString(1), "retry", rs.getInt(2), "max", rs.getInt(3), "code", rs.getString(4)))
                .single();
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("retry")).isEqualTo(row.get("max"));
        assertThat((String) row.get("code")).startsWith("SOURCE_");
        assertThat(jdbc.sql("SELECT status FROM news_source").query(String.class).single()).isEqualTo("DEGRADED");
    }

    @Test
    void nonRetryableFailureFailsImmediately() {
        jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", 999), "t", null, null);   // unknown source
        drain();
        var row = jdbc.sql("SELECT status, retry_count, error_code FROM async_job")
                .query((rs, n) -> rs.getString(1) + "/" + rs.getInt(2) + "/" + rs.getString(3)).single();
        assertThat(row).isEqualTo("FAILED/0/SOURCE_NOT_FOUND");
    }

    @Test
    void staleRunningJobIsRequeuedAfterAWorkerCrash() {
        var job = jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", 999), "t", null, null).job();
        jdbc.sql("""
                UPDATE async_job SET status = 'RUNNING', locked_by = 'dead-worker', started_at = now() - interval '1 hour',
                       heartbeat_at = now() - interval '1 hour' WHERE id = :id""").param("id", job.id()).update();

        var recovered = repository.recoverStale(300);

        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).status().name()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT error_code FROM async_job").query(String.class).single()).isEqualTo("WORKER_LOST");
        assertThat(worker.runPending(5)).isEqualTo(1);       // it runs again (and fails for the unknown source)
    }

    @Test
    void twoWorkersNeverRunTheSameJobTwice() throws Exception {
        for (int i = 0; i < 40; i++) {
            jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", 900 + i), "t", null, null);
        }
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                go.await();
                while (true) {
                    var claimed = repository.claimNext("w-" + Thread.currentThread().getName(), Set.of("COLLECT_NEWS"), 3);
                    if (claimed.isEmpty()) {
                        return null;
                    }
                    executed.addAndGet(claimed.size());
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM async_job WHERE status = 'RUNNING'")).isEqualTo(40);
    }
}
