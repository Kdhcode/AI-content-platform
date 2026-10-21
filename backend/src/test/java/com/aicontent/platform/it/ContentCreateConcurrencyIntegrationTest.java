package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.content.ContentService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Creating content from several threads at once, and rollback when part of the create fails. */
class ContentCreateConcurrencyIntegrationTest extends IntegrationTestBase {

    @Autowired ContentService contents;

    private long activeIssue(String title) {
        return jdbc.sql("INSERT INTO issue (title, status) VALUES (:t, 'ACTIVE') RETURNING id").param("t", title)
                .query(Long.class).single();
    }

    /** Runs the calls at the same moment, each in its own thread (and so its own transaction). */
    private <T> List<Future<T>> together(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            for (Future<T> f : futures) {
                try { f.get(); } catch (Exception ignored) { /* inspected by the caller */ }
            }
            return futures;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousRetriesOfOneRequestCreateOneContentOneSourceAndOneAuditRecord() throws Exception {
        long issue = activeIssue("동시 요청 이슈");
        List<Callable<ContentService.CreateResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> contents.create(issue, "NEWS", "동시 요청", null, "concurrent-key-0001", "op", null));
        }
        List<Long> ids = new ArrayList<>();
        int created = 0;
        for (Future<ContentService.CreateResult> f : together(calls)) {
            ContentService.CreateResult r = f.get();                 // none of them may fail
            ids.add(r.content().id());
            if (r.created()) created++;
        }
        assertThat(created).isEqualTo(1);
        assertThat(ids).containsOnly(ids.get(0));
        assertThat(count("SELECT count(*) FROM content")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM content_source")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_CREATE'")).isEqualTo(1);
    }

    @Test
    void simultaneousUsesOfOneKeyForDifferentIssuesLetExactlyOneWin() throws Exception {
        long a = activeIssue("이슈 A"), b = activeIssue("이슈 B");
        List<Future<ContentService.CreateResult>> results = together(List.of(
                () -> contents.create(a, "NEWS", "", null, "shared-key-0001", "op", null),
                () -> contents.create(b, "NEWS", "", null, "shared-key-0001", "op", null)));
        int ok = 0, conflicts = 0;
        for (Future<ContentService.CreateResult> f : results) {
            try {
                f.get();
                ok++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) e.getCause()).errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                conflicts++;
            }
        }
        assertThat(ok).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM content")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM content_source")).isEqualTo(1);
    }

    @Test
    void whenRegisteringThePrimarySourceFailsNoContentOrAuditRecordRemains() {
        long issue = activeIssue("출처 실패 이슈");
        // make the source insert fail for this test only
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION test_fail_content_source() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'simulated source failure'; END $$ LANGUAGE plpgsql""").update();
        jdbc.sql("CREATE TRIGGER test_fail_content_source BEFORE INSERT ON content_source FOR EACH ROW EXECUTE FUNCTION test_fail_content_source()").update();
        try {
            assertThatThrownBy(() -> contents.create(issue, "NEWS", "실패할 생성", null, "rollback-key-0001", "op", null))
                    .hasStackTraceContaining("simulated source failure");
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS test_fail_content_source ON content_source").update();
            jdbc.sql("DROP FUNCTION IF EXISTS test_fail_content_source()").update();
        }
        assertThat(count("SELECT count(*) FROM content")).isZero();
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_CREATE'")).isZero();
        // the key was not used up: the same request now succeeds
        assertThat(contents.create(issue, "NEWS", "실패할 생성", null, "rollback-key-0001", "op", null).created()).isTrue();
    }
}
