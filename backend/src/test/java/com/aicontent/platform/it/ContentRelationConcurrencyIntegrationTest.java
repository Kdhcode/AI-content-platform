package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.content.ContentRelationService;
import com.aicontent.platform.content.ContentService;
import com.aicontent.platform.content.ContentStatusPolicy.Action;
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

/** Source / link changes racing with approval, and rollback when such a change fails halfway. */
class ContentRelationConcurrencyIntegrationTest extends IntegrationTestBase {

    @Autowired ContentService contents;
    @Autowired ContentRelationService relations;
    private static final ObjectMapper M = new ObjectMapper();

    private long content() throws Exception {
        long issue = jdbc.sql("INSERT INTO issue (title, status) VALUES ('출처 경쟁 이슈', 'ACTIVE') RETURNING id").query(Long.class).single();
        var body = M.readTree("""
                {"schemaVersion": 1, "sections": [{"id": "s-1", "heading": "요약", "paragraphs": [{"id": "p-1", "text": "본문"}]}]}""");
        return contents.create(issue, "NEWS", "출처 경쟁", body, "rel-" + System.nanoTime(), "op", null).content().id();
    }

    private List<Future<Object>> together(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> c : calls) futures.add(pool.submit(() -> { start.await(); return c.call(); }));
            start.countDown();
            for (Future<Object> f : futures) { try { f.get(); } catch (ExecutionException ignored) { /* below */ } }
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
    void aSourceOrLinkChangeAndAnApprovalAtTheSameVersionLetOnlyOneThrough() throws Exception {
        for (int round = 0; round < 4; round++) {
            long id = content();
            contents.transition(id, Action.SUBMIT, 0, null, "op", false, null);
            boolean source = round % 2 == 0;
            Callable<Object> change = source
                    ? () -> relations.addSource(id, 1, new ContentRelationService.SourceInput("MANUAL", null, null, null, "늦게 온 자료", null), "op", null)
                    : () -> relations.addLink(id, 1, new ContentRelationService.LinkInput("MAP", "", "https://map.example.test", null, null, null), "op", null);
            var results = together(List.of(change, () -> contents.transition(id, Action.APPROVE, 1, null, "root", true, null)));
            ErrorCode c = outcome(results.get(0)), a = outcome(results.get(1));
            assertThat(c == null ^ a == null).as("exactly one succeeds").isTrue();
            assertThat(c == null ? a : c).isEqualTo(ErrorCode.CONTENT_VERSION_CONFLICT);
            String row = jdbc.sql("""
                    SELECT status || '/' || version || '/' ||
                           (SELECT count(*) FROM content_source s WHERE s.content_id = c.id) + (SELECT count(*) FROM content_link l WHERE l.content_id = c.id)
                    FROM content c WHERE id = :id""").param("id", id).query(String.class).single();
            // approved without the new item, or the new item in DRAFT: never "approved with an unreviewed item"
            assertThat(row).isIn("APPROVED/2/1", "DRAFT/2/2");
        }
    }

    private void failAuditFor(String action, Runnable body) {
        jdbc.sql("CREATE OR REPLACE FUNCTION test_fail_rel_audit() RETURNS trigger AS $$ BEGIN IF NEW.action = '" + action
                + "' THEN RAISE EXCEPTION 'simulated audit failure'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql").update();
        jdbc.sql("CREATE TRIGGER test_fail_rel_audit BEFORE INSERT ON admin_audit_log FOR EACH ROW EXECUTE FUNCTION test_fail_rel_audit()").update();
        try {
            body.run();
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS test_fail_rel_audit ON admin_audit_log").update();
            jdbc.sql("DROP FUNCTION IF EXISTS test_fail_rel_audit()").update();
        }
    }

    private String snapshot(long id) {
        return jdbc.sql("""
                SELECT c.status || '/' || c.version || '|' ||
                       coalesce((SELECT string_agg(s.id || ':' || s.source_role || ':' || coalesce(s.source_label, ''), ',' ORDER BY s.id) FROM content_source s WHERE s.content_id = c.id), '') || '|' ||
                       coalesce((SELECT string_agg(l.id || ':' || l.sort_order || ':' || l.display_position, ',' ORDER BY l.id) FROM content_link l WHERE l.content_id = c.id), '') || '|' ||
                       (SELECT count(*) FROM admin_audit_log a WHERE a.target_type = 'CONTENT' AND a.target_id = c.id::text)
                FROM content c WHERE c.id = :id""").param("id", id).query(String.class).single();
    }

    @Test
    void aFailedSourceOrLinkChangeLeavesContentItemsAndAuditAsTheyWere() throws Exception {
        long id = content();
        long a = relations.addLink(id, 0, new ContentRelationService.LinkInput("MAP", "a", "https://a.test", null, null, null), "op", null).itemId();
        long b = relations.addLink(id, 1, new ContentRelationService.LinkInput("MAP", "b", "https://b.test", null, null, null), "op", null).itemId();
        String before = snapshot(id);

        failAuditFor("CONTENT_LINKS_REORDER", () -> assertThatThrownBy(() -> relations.reorderLinks(id, 2, List.of(b, a), "op", null))
                .hasStackTraceContaining("simulated audit failure"));
        failAuditFor("CONTENT_SOURCE_ADD", () -> assertThatThrownBy(() -> relations.addSource(id, 2,
                new ContentRelationService.SourceInput("EXTERNAL_URL", null, null, "https://x.test", null, "REFERENCE"), "op", null))
                .hasStackTraceContaining("simulated audit failure"));
        failAuditFor("CONTENT_LINK_UPDATE", () -> assertThatThrownBy(() -> relations.updateLink(id, a, 2,
                new ContentRelationService.LinkInput(null, null, null, null, null, "BODY_TOP"), "op", null))
                .hasStackTraceContaining("simulated audit failure"));
        assertThat(snapshot(id)).isEqualTo(before);

        // nothing was used up: the same reorder now goes through
        assertThat(relations.reorderLinks(id, 2, List.of(b, a), "op", null).changed()).isTrue();
    }
}
