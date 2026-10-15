package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.content.ContentStatusPolicy.Action;
import com.aicontent.platform.content.ContentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Edits and status actions racing on one content, and rollback when a status change fails halfway. */
class ContentStatusConcurrencyIntegrationTest extends IntegrationTestBase {

    @Autowired ContentService contents;
    private static final ObjectMapper M = new ObjectMapper();

    /** A content in REVIEW at version 1, ready to be approved. */
    private long inReview() throws Exception {
        long issue = jdbc.sql("INSERT INTO issue (title, status) VALUES ('동시 상태 이슈', 'ACTIVE') RETURNING id").query(Long.class).single();
        var body = M.readTree("""
                {"schemaVersion": 1, "sections": [{"id": "s-1", "heading": "요약", "paragraphs": [{"id": "p-1", "text": "검수본"}]}]}""");
        long id = contents.create(issue, "NEWS", "검수본 제목", body, "race-" + System.nanoTime(), "op", null).content().id();
        contents.transition(id, Action.SUBMIT, 0, null, "op", false, null);
        return id;
    }

    private List<Future<Object>> together(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> c : calls) {
                futures.add(pool.submit(() -> { start.await(); return c.call(); }));
            }
            start.countDown();
            for (Future<Object> f : futures) {
                try { f.get(); } catch (ExecutionException ignored) { /* inspected below */ }
            }
            return futures;
        } finally {
            pool.shutdownNow();
        }
    }

    private static ErrorCode outcome(Future<Object> f) throws InterruptedException {
        try {
            f.get();
            return null;
        } catch (ExecutionException e) {
            return ((BusinessException) e.getCause()).errorCode();
        }
    }

    @Test
    void anEditAndAnApprovalAtTheSameVersionNeverApproveTheOldText() throws Exception {
        for (int round = 0; round < 5; round++) {
            long id = inReview();
            var edited = M.readTree("""
                    {"schemaVersion": 1, "sections": [{"id": "s-1", "heading": "요약", "paragraphs": [{"id": "p-1", "text": "고친 본문"}]}]}""");
            var results = together(List.of(
                    () -> contents.patch(id, 1, null, edited, "op", null),
                    () -> contents.transition(id, Action.APPROVE, 1, null, "root", true, null)));
            ErrorCode edit = outcome(results.get(0)), approve = outcome(results.get(1));
            assertThat(edit == null ^ approve == null).as("exactly one of them succeeds").isTrue();
            assertThat(edit == null ? approve : edit).isEqualTo(ErrorCode.CONTENT_VERSION_CONFLICT);
            String row = jdbc.sql("SELECT status || '/' || version || '/' || (body #>> '{sections,0,paragraphs,0,text}') FROM content WHERE id = :id")
                    .param("id", id).query(String.class).single();
            // either the old text was approved (edit lost and must reload) or the new text sits in DRAFT; never "approved new text"
            assertThat(row).isIn("APPROVED/2/검수본", "DRAFT/2/고친 본문");
        }
    }

    @Test
    void twoActionsAtTheSameVersionLetOnlyOneThrough() throws Exception {
        long id = inReview();
        var results = together(List.of(
                () -> contents.transition(id, Action.APPROVE, 1, null, "root", true, null),
                () -> contents.transition(id, Action.RETURN, 1, "다시 확인", "op", false, null)));
        assertThat(outcome(results.get(0)) == null ^ outcome(results.get(1)) == null).isTrue();
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action IN ('CONTENT_APPROVE', 'CONTENT_RETURN')")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT version FROM content WHERE id = :id").param("id", id).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aStatusChangeThatFailsHalfwayLeavesNoTrace() throws Exception {
        long id = inReview();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION test_fail_approve_audit() RETURNS trigger AS $$
                BEGIN IF NEW.action = 'CONTENT_APPROVE' THEN RAISE EXCEPTION 'simulated audit failure'; END IF; RETURN NEW; END $$
                LANGUAGE plpgsql""").update();
        jdbc.sql("CREATE TRIGGER test_fail_approve_audit BEFORE INSERT ON admin_audit_log FOR EACH ROW EXECUTE FUNCTION test_fail_approve_audit()").update();
        try {
            assertThatThrownBy(() -> contents.transition(id, Action.APPROVE, 1, null, "root", true, null))
                    .hasStackTraceContaining("simulated audit failure");
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS test_fail_approve_audit ON admin_audit_log").update();
            jdbc.sql("DROP FUNCTION IF EXISTS test_fail_approve_audit()").update();
        }
        assertThat(jdbc.sql("SELECT status || '/' || version || '/' || (approved_at IS NULL) FROM content WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("REVIEW/1/true");
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_APPROVE'")).isZero();
        // nothing was used up: the same approval at the same version now works
        assertThat(contents.transition(id, Action.APPROVE, 1, null, "root", true, null).status()).isEqualTo("APPROVED");
    }
}
