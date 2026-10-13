package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.job.JobType;
import com.aicontent.platform.lock.DistributedLock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** With lock-scope=CATEGORY only the article's own category lock matters (the stub analysis says OTHER). */
@TestPropertySource(properties = {"app.classifier.lock-scope=CATEGORY", "app.classifier.lock-wait-seconds=0"})
class LockScopeIntegrationTest extends IntegrationTestBase {

    private static final String CLASSIFY = " FROM async_job WHERE job_type = 'CLASSIFY_ARTICLE'";

    @Autowired DistributedLock lock;

    @Test
    void anotherCategoryLockDoesNotBlock() throws Exception {
        try (var held = lock.acquire("issue-classification:ECONOMY", Duration.ofSeconds(60), Duration.ZERO).orElseThrow();
             var global = lock.acquire("issue-classification", Duration.ofSeconds(60), Duration.ZERO).orElseThrow()) {
            collectSample();
        }
        assertThat(count("SELECT count(*)" + CLASSIFY + " AND status = 'SUCCESS' AND defer_count = 0")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM news_article WHERE classification_status = 'CLASSIFIED'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM issue WHERE status = 'ACTIVE'")).isEqualTo(2);   // same result as GLOBAL
    }

    @Test
    void ownCategoryLockDefers() throws Exception {
        long sourceId = fixtureSource("src-a", "[" + article("가상시 A동 상가 화재 발생 주민 대피", "https://example.test/n/1",
                "가상일보", "2026-10-01T09:00:00+09:00") + "]");
        try (var held = lock.acquire("issue-classification:OTHER", Duration.ofSeconds(60), Duration.ZERO).orElseThrow()) {
            jobsService.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", sourceId), "t", "collect:" + sourceId, null);
            for (int round = 0; round < 20 && worker.runPending(20) > 0; round++) {
                // respects available_at: the deferred classification is not picked up again
            }
        }
        assertThat(count("SELECT count(*)" + CLASSIFY + """
                 AND status = 'PENDING' AND defer_count = 1 AND error_code = 'LOCK_TIMEOUT'
                 AND error_message LIKE '%issue-classification:OTHER%'""")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM issue")).isZero();
    }
}
