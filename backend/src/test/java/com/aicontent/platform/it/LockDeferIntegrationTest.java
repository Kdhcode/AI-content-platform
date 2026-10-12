package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.job.JobType;
import com.aicontent.platform.lock.DistributedLock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** A busy classification lock must put the job back (defer_count) instead of consuming retries. */
@TestPropertySource(properties = {"app.classifier.lock-wait-seconds=0", "app.worker.max-defers=2"})
class LockDeferIntegrationTest extends IntegrationTestBase {

    private static final String LOCK_KEY = "issue-classification";
    private static final String CLASSIFY = " FROM async_job WHERE job_type = 'CLASSIFY_ARTICLE'";

    @Autowired DistributedLock lock;

    @Test
    void lockTimeoutDefersWithoutConsumingARetryAndTheJobSucceedsLater() throws Exception {
        long sourceId = sampleSource();
        try (var held = lock.acquire(LOCK_KEY, Duration.ofSeconds(60), Duration.ZERO).orElseThrow()) {
            jobsService.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", sourceId), "t", "collect:" + sourceId, null);
            // deferred jobs are due 30s from now, so the queue runs dry once every classification has been deferred
            runUntilIdle();

            assertThat(count("SELECT count(*)" + CLASSIFY)).isEqualTo(3);
            assertThat(count("SELECT count(*)" + CLASSIFY + """
                     AND status = 'PENDING' AND retry_count = 0 AND defer_count = 1 AND error_code = 'LOCK_TIMEOUT'
                     AND available_at > now() + interval '20 seconds' AND finished_at IS NULL AND locked_by IS NULL"""))
                    .isEqualTo(3);
            assertThat(count("SELECT count(*) FROM issue")).isZero();
        }

        drain();

        assertThat(count("SELECT count(*)" + CLASSIFY + " AND status = 'SUCCESS' AND retry_count = 0 AND defer_count = 1"))
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM news_article WHERE classification_status = 'CLASSIFIED'")).isEqualTo(3);
    }

    @Test
    void reachingMaxDefersFailsTheJobAndMarksTheArticleLikeAnyFinalFailure() throws Exception {
        long sourceId = sampleSource();
        try (var held = lock.acquire(LOCK_KEY, Duration.ofSeconds(60), Duration.ZERO).orElseThrow()) {
            jobsService.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", sourceId), "t", "collect:" + sourceId, null);
            drain();   // skips the delay: every classification is deferred twice, then the third attempt fails for good
        }

        assertThat(count("SELECT count(*)" + CLASSIFY + """
                 AND status = 'FAILED' AND retry_count = 0 AND defer_count = 2 AND error_code = 'LOCK_TIMEOUT'
                 AND finished_at IS NOT NULL""")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM news_article WHERE classification_status = 'FAILED'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM issue")).isZero();
    }

    private long sampleSource() throws Exception {
        return fixtureSource("src-a", "[" + String.join(",",
                article("가상시 A동 상가 화재 발생 주민 대피", "https://example.test/n/1", "가상일보", "2026-10-01T09:00:00+09:00"),
                article("가상시 A동 상가 화재 발생 소방 출동", "https://example.test/n/2", "샘플뉴스", "2026-10-01T10:00:00+09:00"),
                article("허구군 물류창고 정전 사고 복구 완료", "https://example.test/n/3", "예시방송", "2026-10-01T11:00:00+09:00")) + "]");
    }

    /** Unlike {@link #drain()} this respects available_at, so deferred jobs are not picked up again. */
    private void runUntilIdle() {
        for (int round = 0; round < 20; round++) {
            if (worker.runPending(20) == 0) {
                return;
            }
        }
        throw new IllegalStateException("queue did not go idle in 20 rounds");
    }
}
