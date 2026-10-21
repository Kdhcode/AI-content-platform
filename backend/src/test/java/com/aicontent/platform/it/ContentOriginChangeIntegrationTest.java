package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.common.PageParams;
import com.aicontent.platform.content.ContentRelationService;
import com.aicontent.platform.content.ContentRepository;
import com.aicontent.platform.content.ContentService;
import com.aicontent.platform.content.ContentStatusPolicy.Action;
import com.aicontent.platform.issue.IssueAggregateService;
import com.aicontent.platform.issue.IssueManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** A6: the origin-issue change marker, its acknowledgement, and the MERGED / EXCLUDED notice. Sample: issue #1 (articles 1, 2), #2 (article 3). */
@AutoConfigureMockMvc
class ContentOriginChangeIntegrationTest extends IntegrationTestBase {

    @Autowired ContentService contents;
    @Autowired ContentRelationService relations;
    @Autowired ContentRepository query;
    @Autowired IssueManagementService management;
    @Autowired IssueAggregateService aggregates;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    private static final ObjectMapper M = new ObjectMapper();

    @BeforeEach
    void data() throws Exception {
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('op', :h) ON CONFLICT (lower(username)) DO NOTHING")
                .param("h", encoder.encode("op-pw")).update();
        jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, 'OPERATOR' FROM app_user WHERE username = 'op' ON CONFLICT DO NOTHING").update();
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('norole', :h) ON CONFLICT (lower(username)) DO NOTHING")
                .param("h", encoder.encode("norole-pw")).update();
        collectSample();
    }

    private long content(long issueId) throws Exception {
        var body = M.readTree("""
                {"schemaVersion": 1, "sections": [{"id": "s-1", "heading": "요약", "paragraphs": [{"id": "p-1", "text": "본문"}]}]}""");
        return contents.create(issueId, "NEWS", "원천 변경 테스트", body, "origin-" + System.nanoTime(), "op", null).content().id();
    }

    private ContentRepository.OriginChange origin(long id) {
        return query.detail(id).orElseThrow().originChange();
    }

    private long version(long id) {
        return query.detail(id).orElseThrow().version();
    }

    private void ack(long id) {
        contents.acknowledgeOrigin(id, version(id), origin(id).issueChangeSeq(), "op", null);
    }

    @Test
    void aFreshContentShowsNoChangeAndUnrelatedIssueWritesDoNotRaiseIt() throws Exception {
        long id = content(1);
        var o = origin(id);
        assertThat(o.unacknowledged()).isFalse();
        assertThat(o.notice()).isNull();
        assertThat(o.issueStatus()).isEqualTo("ACTIVE");
        // recomputing aggregates / centroid, or touching only updated_at, is not a change a writer needs to see
        aggregates.refresh(1);
        jdbc.sql("UPDATE issue SET embedding = embedding, updated_at = now() + interval '1 day' WHERE id = 1").update();
        assertThat(origin(id).unacknowledged()).isFalse();
        assertThat(origin(id).issueChangeSeq()).isEqualTo(o.issueChangeSeq());
    }

    @Test
    void articleMembershipTitleSummaryCategoryAndStatusChangesAreAllNoticed() throws Exception {
        long id = content(1);
        // an article joins (moved from issue #2), even though it is older than the issue's newest article
        jdbc.sql("UPDATE news_article SET published_at = '2000-01-01T00:00:00Z' WHERE id = 3").update();
        management.move(3, 1, "op", "같은 사건", null);
        assertThat(origin(id).unacknowledged()).isTrue();
        long afterMove = origin(id).issueChangeSeq();
        ack(id);
        assertThat(origin(id).unacknowledged()).isFalse();

        for (var patch : List.of(new IssueManagementService.IssuePatch("새 이슈 제목", null, null, null),
                new IssueManagementService.IssuePatch(null, "새 요약", null, null),
                new IssueManagementService.IssuePatch(null, null, "ECONOMY", null),
                new IssueManagementService.IssuePatch(null, null, null, "CLOSED"))) {
            management.update(1, patch, "op", null, null);
            assertThat(origin(id).unacknowledged()).as(patch.toString()).isTrue();
            ack(id);
            assertThat(origin(id).unacknowledged()).as(patch.toString()).isFalse();
        }
        assertThat(origin(id).issueChangeSeq()).isGreaterThan(afterMove);
        // an article leaving (split out of the origin issue) is a change too
        management.update(1, new IssueManagementService.IssuePatch(null, null, null, "ACTIVE"), "op", null, null);
        ack(id);
        management.split(1, List.of(3L), null, "op", "다른 사건", null);
        assertThat(origin(id).unacknowledged()).isTrue();
    }

    @Test
    void mergedAndExcludedOriginsKeepTheirNoticeAfterAcknowledgement() throws Exception {
        long merged = content(1), excluded = content(2);
        management.merge(2, List.of(1L), "op", "같은 사건", null);         // issue #1 merged into #2
        var m = origin(merged);
        assertThat(m.unacknowledged()).isTrue();
        assertThat(m.notice()).isEqualTo("MERGED");
        assertThat(m.mergedIntoIssueId()).isEqualTo(2L);
        ack(merged);
        assertThat(origin(merged).unacknowledged()).isFalse();
        assertThat(origin(merged).notice()).isEqualTo("MERGED");                    // the state notice stays
        assertThat(query.detail(merged).orElseThrow().originIssue().id()).isEqualTo(1L);   // origin is not re-pointed

        management.update(2, new IssueManagementService.IssuePatch(null, null, null, "EXCLUDED"), "op", "오보", null);
        assertThat(origin(excluded).notice()).isEqualTo("EXCLUDED");
        ack(excluded);
        assertThat(origin(excluded).unacknowledged()).isFalse();
        assertThat(origin(excluded).notice()).isEqualTo("EXCLUDED");

        // a merged / excluded origin does not block review (policy unchanged)
        contents.transition(merged, Action.SUBMIT, version(merged), null, "op", false, null);
        contents.transition(merged, Action.APPROVE, version(merged), null, "root", true, null);
        assertThat(query.detail(merged).orElseThrow().status()).isEqualTo("APPROVED");
    }

    @Test
    void listFilterAndDetailUseTheSameRuleBeforePaging() throws Exception {
        List<Long> changed = new ArrayList<>(), unchanged = new ArrayList<>();
        for (int i = 0; i < 5; i++) changed.add(content(1));
        for (int i = 0; i < 4; i++) unchanged.add(content(2));
        management.update(1, new IssueManagementService.IssuePatch("이슈 1 제목 변경", null, null, null), "op", null, null);

        Set<Long> seen = new HashSet<>();
        long total = -1;
        for (int page = 0; page < 3; page++) {
            var res = query.list(new ContentRepository.Filter(null, null, null, null, true), PageParams.of(page, 2, null, ContentRepository.SORTS, "c.id"));
            total = res.totalElements();
            res.items().forEach(item -> {
                assertThat(item.originChange().unacknowledged()).isTrue();
                assertThat(origin(item.id()).unacknowledged()).isTrue();             // detail agrees
                seen.add(item.id());
            });
        }
        assertThat(total).isEqualTo(5);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(changed);
        var rest = query.list(new ContentRepository.Filter(null, null, null, null, false), PageParams.of(0, 20, null, ContentRepository.SORTS, "c.id"));
        assertThat(rest.items().stream().map(ContentRepository.ListItem::id).toList()).containsExactlyElementsOf(unchanged);
        assertThat(query.list(new ContentRepository.Filter(null, null, null, null, null), PageParams.of(0, 20, null, ContentRepository.SORTS, "c.id"))
                .totalElements()).isEqualTo(9);

        mvc.perform(get("/api/admin/contents?updateNeeded=true&size=2").with(httpBasic("op", "op-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].originChange.unacknowledged").value(true));
        mvc.perform(get("/api/admin/contents?updateNeeded=maybe").with(httpBasic("op", "op-pw"))).andExpect(status().isBadRequest());
    }

    @Test
    void acknowledgingWhatWasNotSeenStaleVersionsAndRepeatsAreHandled() throws Exception {
        long id = content(1);
        management.update(1, new IssueManagementService.IssuePatch("첫 변경", null, null, null), "op", null, null);
        long shown = origin(id).issueChangeSeq();
        long v = version(id);
        // the issue changes again between showing and acknowledging
        management.update(1, new IssueManagementService.IssuePatch("두 번째 변경", null, null, null), "op", null, null);
        assertThatThrownBy(() -> contents.acknowledgeOrigin(id, v, shown, "op", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.CONTENT_ORIGIN_CHANGED);
                    assertThat(be.details().toString()).contains("currentChangeSeq=" + (shown + 1), "sentChangeSeq=" + shown);
                });
        assertThat(origin(id).unacknowledged()).isTrue();
        assertThat(version(id)).isEqualTo(v);

        // stale content version
        assertThatThrownBy(() -> contents.acknowledgeOrigin(id, v + 5, origin(id).issueChangeSeq(), "op", null))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.CONTENT_VERSION_CONFLICT));

        // the real acknowledgement, then the same request again: no second version bump, no second audit
        var first = contents.acknowledgeOrigin(id, v, shown + 1, "op", null);
        assertThat(first.changed()).isTrue();
        assertThat(first.content().version()).isEqualTo(v + 1);
        var again = contents.acknowledgeOrigin(id, v + 1, shown + 1, "op", null);
        assertThat(again.changed()).isFalse();
        assertThat(again.content().version()).isEqualTo(v + 1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_ORIGIN_ACKNOWLEDGED'")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT actor || '|' || (after_state ->> 'originAckSeq') || '|' || (after_state ->> 'originIssueId') FROM admin_audit_log WHERE action = 'CONTENT_ORIGIN_ACKNOWLEDGED'")
                .query(String.class).single()).isEqualTo("op|" + (shown + 1) + "|1");
    }

    @Test
    void anIssueChangeRacingAnAcknowledgementIsNeverLost() throws Exception {
        for (int round = 0; round < 6; round++) {
            long id = content(1);
            management.update(1, new IssueManagementService.IssuePatch("경쟁 전 " + round, null, null, null), "op", null, null);
            long shown = origin(id).issueChangeSeq();
            long v = version(id);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                final int r = round;
                Future<?> change = pool.submit(() -> { start.await(); management.update(1, new IssueManagementService.IssuePatch("경쟁 중 " + r, null, null, null), "op", null, null); return null; });
                Future<?> acknowledge = pool.submit(() -> { start.await(); return contents.acknowledgeOrigin(id, v, shown, "op", null); });
                start.countDown();
                change.get();
                try {
                    acknowledge.get();
                } catch (ExecutionException e) {
                    assertThat(((BusinessException) e.getCause()).errorCode()).isEqualTo(ErrorCode.CONTENT_ORIGIN_CHANGED);
                }
            } finally {
                pool.shutdownNow();
            }
            // whichever ran first, the change made during the race is still waiting to be acknowledged
            assertThat(origin(id).unacknowledged()).as("round " + round).isTrue();
        }
    }

    @Test
    void acknowledgingChangesNothingAboutTheContentAndRollsBackAsAWhole() throws Exception {
        long id = content(1);
        relations.addLink(id, version(id), new ContentRelationService.LinkInput("MAP", "지도", "https://map.example.test", null, null, null), "op", null);
        contents.transition(id, Action.SUBMIT, version(id), null, "op", false, null);
        contents.transition(id, Action.APPROVE, version(id), null, "root", true, null);
        management.update(1, new IssueManagementService.IssuePatch("승인 뒤 원천 변경", null, null, null), "op", null, null);
        String before = snapshot(id);

        // an audit failure rolls the acknowledgement back
        jdbc.sql("CREATE OR REPLACE FUNCTION test_fail_ack() RETURNS trigger AS $$ BEGIN IF NEW.action = 'CONTENT_ORIGIN_ACKNOWLEDGED' THEN RAISE EXCEPTION 'simulated audit failure'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql").update();
        jdbc.sql("CREATE TRIGGER test_fail_ack BEFORE INSERT ON admin_audit_log FOR EACH ROW EXECUTE FUNCTION test_fail_ack()").update();
        try {
            assertThatThrownBy(() -> ack(id)).hasStackTraceContaining("simulated audit failure");
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS test_fail_ack ON admin_audit_log").update();
            jdbc.sql("DROP FUNCTION IF EXISTS test_fail_ack()").update();
        }
        assertThat(snapshot(id)).isEqualTo(before);
        assertThat(origin(id).unacknowledged()).isTrue();

        ack(id);
        String after = snapshot(id);
        // only the acknowledgement and the version moved: still APPROVED with the same approval, body, sources, links
        assertThat(after.substring(after.indexOf('|'))).isEqualTo(before.substring(before.indexOf('|')));
        assertThat(query.detail(id).orElseThrow().status()).isEqualTo("APPROVED");
        assertThat(origin(id).unacknowledged()).isFalse();
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'CONTENT_APPROVAL_REVOKED_BY_EDIT'")).isZero();

        // allowed in a locked state too (acknowledging is not an edit)
        contents.transition(id, Action.HOLD, version(id), "검토", "op", false, null);
        management.update(1, new IssueManagementService.IssuePatch("보류 중 원천 변경", null, null, null), "op", null, null);
        ack(id);
        assertThat(origin(id).unacknowledged()).isFalse();
        assertThat(query.detail(id).orElseThrow().status()).isEqualTo("HOLD");
    }

    /** version | everything else about the content (status, approval, title, body, sources, links). */
    private String snapshot(long id) {
        return jdbc.sql("""
                SELECT c.version || '|' || c.status || '|' || coalesce(c.approved_by, '-') || '|' || coalesce(c.approved_at::text, '-')
                       || '|' || coalesce(c.submitted_at::text, '-') || '|' || c.title || '|' || c.body::text || '|' ||
                       (SELECT coalesce(string_agg(s.id || s.source_role, ',' ORDER BY s.id), '') FROM content_source s WHERE s.content_id = c.id) || '|' ||
                       (SELECT coalesce(string_agg(l.id || l.url || l.sort_order, ',' ORDER BY l.id), '') FROM content_link l WHERE l.content_id = c.id)
                FROM content c WHERE c.id = :id""").param("id", id).query(String.class).single();
    }

    @Test
    void onlyAdminsAcknowledgeAndNothingReachesThePublicApi() throws Exception {
        long id = content(1);
        management.update(1, new IssueManagementService.IssuePatch("공개 확인용 변경", null, null, null), "op", null, null);
        String body = "{\"version\":" + version(id) + ",\"originChangeSeq\":" + origin(id).issueChangeSeq() + "}";
        mvc.perform(post("/api/admin/contents/" + id + "/acknowledge-origin").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/contents/" + id + "/acknowledge-origin").with(httpBasic("norole", "norole-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/contents/" + id + "/acknowledge-origin").with(httpBasic("op", "op-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version(id) + "}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/contents/" + id + "/acknowledge-origin").with(httpBasic("op", "op-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.statusChanged").value(false))
                .andExpect(jsonPath("$.data.content.originChange.unacknowledged").value(false));
        mvc.perform(post("/api/admin/contents/999/acknowledge-origin").with(httpBasic("op", "op-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        String publicJson = mvc.perform(get("/api/public/issues/1")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
                + mvc.perform(get("/api/public/issues")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(publicJson).contains("공개 확인용 변경").doesNotContain("change_seq", "changeSeq", "원천 변경 테스트", "originChange");
    }
}
