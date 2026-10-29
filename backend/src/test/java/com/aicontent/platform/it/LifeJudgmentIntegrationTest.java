package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.issue.IssueAggregateService;
import com.aicontent.platform.issue.IssueManagementService;
import com.aicontent.platform.issue.IssueManagementService.IssuePatch;
import com.aicontent.platform.life.LifeJudgmentRepository.Judgment;
import com.aicontent.platform.life.LifeJudgmentRepository.JudgmentInput;
import com.aicontent.platform.life.LifeJudgmentService;
import com.aicontent.platform.life.LifeJudgmentService.NewJudgment;
import com.aicontent.platform.life.LifeJudgmentService.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * B1 LIFE judgment storage (docs/LIFE_B_PLAN.md). Judgment results are written by the test itself (no AI call).
 * Sample: issue #1 (articles 1, 2), #2 (article 3).
 */
class LifeJudgmentIntegrationTest extends IntegrationTestBase {

    @Autowired LifeJudgmentService life;
    @Autowired IssueManagementService management;
    @Autowired IssueAggregateService aggregates;
    private static final ObjectMapper M = new ObjectMapper();

    @BeforeEach
    void data() throws Exception {
        collectSample();
    }

    private long job() {
        return jdbc.sql("""
                INSERT INTO async_job (job_type, status, correlation_id) VALUES ('LIFE_JUDGE_TEST', 'SUCCESS', 'test')
                RETURNING id""").query(Long.class).single();
    }

    private NewJudgment judgment(JudgmentInput input, String score, Long jobId) {
        return new NewJudgment(input, true, "생활안전", new BigDecimal(score), "가상 판정 이유", "life-classifier.test",
                "stub", jobId, "op");
    }

    private Judgment judge(long issueId, String score) {
        return life.record(judgment(life.readInput(issueId), score, job())).judgment();
    }

    private long changeSeq(long issueId) {
        return count("SELECT change_seq FROM issue WHERE id = " + issueId);
    }

    @Test
    void scoreMustBeBetweenZeroAndOneInTheServiceAndTheDatabase() {
        JudgmentInput input = life.readInput(1);
        assertThat(life.record(judgment(input, "0", null)).judgment().relevanceScore()).isEqualByComparingTo("0");
        assertThat(life.record(judgment(input, "1", null)).judgment().relevanceScore()).isEqualByComparingTo("1");
        assertThat(life.record(judgment(input, "0.91", null)).judgment().relevanceScore()).isEqualByComparingTo("0.910");
        for (String bad : List.of("-0.001", "1.001", "0.9105")) {
            assertThatThrownBy(() -> life.record(judgment(input, bad, null))).as(bad)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        }
        // the DB CHECK holds even for writers that skip the service
        for (String bad : List.of("-0.001", "1.001")) {
            assertThatThrownBy(() -> jdbc.sql("""
                    INSERT INTO life_judgment (issue_id, input_change_seq, input_snapshot, input_hash, life_related,
                        relevance_score, reason, prompt_version, judged_by)
                    VALUES (1, 0, '{}', repeat('a', 64), TRUE, CAST(:s AS numeric), 'r', 'p', 'op')""")
                    .param("s", bad).update()).as(bad).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(count("SELECT count(*) FROM life_judgment")).isEqualTo(3);
    }

    @Test
    void requiredFieldsAreChecked() {
        JudgmentInput input = life.readInput(1);
        for (NewJudgment bad : List.of(
                new NewJudgment(input, true, "c", BigDecimal.ONE, " ", "p", null, null, "op"),
                new NewJudgment(input, true, "c", BigDecimal.ONE, "r", "", null, null, "op"),
                new NewJudgment(input, true, "c", BigDecimal.ONE, "r", "p", null, null, null),
                new NewJudgment(input, true, "가".repeat(51), BigDecimal.ONE, "r", "p", null, null, "op"),
                new NewJudgment(input, true, "c", null, "r", "p", null, null, "op"),
                new NewJudgment(null, true, "c", BigDecimal.ONE, "r", "p", null, null, "op"))) {
            assertThatThrownBy(() -> life.record(bad)).isInstanceOf(BusinessException.class);
        }
        // not life related: no category
        var none = life.record(new NewJudgment(input, false, " ", new BigDecimal("0.05"), "r", "p", null, null, "op"));
        assertThat(none.judgment().category()).isNull();
        assertThat(count("SELECT count(*) FROM life_judgment")).isEqualTo(1);
    }

    @Test
    void unjudgedThenJudgedThenStaleAfterAnIssueChangeThenJudgedAgainKeepingHistory() {
        var s0 = life.state(1);
        assertThat(s0.status()).isEqualTo(Status.UNJUDGED);
        assertThat(s0.current()).isNull();

        Judgment first = judge(1, "0.8");
        assertThat(life.state(1).status()).isEqualTo(Status.JUDGED);
        assertThat(life.state(1).current().id()).isEqualTo(first.id());

        management.update(1, new IssuePatch("가상시 상가 화재 수습", null, null, null), "op", null, null);
        var stale = life.state(1);
        assertThat(stale.status()).isEqualTo(Status.STALE);
        assertThat(stale.current().id()).isEqualTo(first.id());   // the old judgment stays visible (D4)

        Judgment second = judge(1, "0.6");
        assertThat(life.state(1).status()).isEqualTo(Status.JUDGED);
        assertThat(life.state(1).current().id()).isEqualTo(second.id());
        // D2: a new row; the first one is unchanged
        assertThat(life.history(1)).extracting(Judgment::id).containsExactly(second.id(), first.id());
        assertThat(life.history(1).get(1)).isEqualTo(first);
        assertThat(second.inputChangeSeq()).isGreaterThan(first.inputChangeSeq());
    }

    @Test
    void anOlderInputFinishingLateDoesNotReplaceTheCurrentJudgment() {
        JudgmentInput older = life.readInput(1);
        management.update(1, new IssuePatch(null, "새 요약", null, null), "op", null, null);
        JudgmentInput newer = life.readInput(1);
        assertThat(newer.changeSeq()).isGreaterThan(older.changeSeq());

        Judgment b = life.record(judgment(newer, "0.7", job())).judgment();
        Judgment a = life.record(judgment(older, "0.2", job())).judgment();   // saved later, read earlier
        assertThat(a.id()).isGreaterThan(b.id());

        var state = life.state(1);
        assertThat(state.current().id()).isEqualTo(b.id());
        assertThat(state.status()).isEqualTo(Status.JUDGED);
        assertThat(life.history(1)).extracting(Judgment::id).containsExactly(b.id(), a.id());
    }

    @Test
    void retryingTheSameJobKeepsOneRow() {
        long jobId = job();
        JudgmentInput input = life.readInput(1);
        var first = life.record(judgment(input, "0.8", jobId));
        assertThat(first.created()).isTrue();
        assertThat(first.mismatch()).isFalse();
        // an identical retry returns the stored row and reports no mismatch
        var same = life.record(judgment(input, "0.800", jobId));
        assertThat(same.created()).isFalse();
        assertThat(same.judgment()).isEqualTo(first.judgment());
        assertThat(same.inputMatches()).isTrue();
        assertThat(same.resultMatches()).isTrue();
        // the retry may have read a newer input and got another answer; the stored row wins, the mismatch is reported
        management.update(1, new IssuePatch(null, null, "SOCIETY", null), "op", null, null);
        var retry = life.record(judgment(life.readInput(1), "0.1", jobId));
        assertThat(retry.created()).isFalse();
        assertThat(retry.judgment()).isEqualTo(first.judgment());
        assertThat(retry.inputMatches()).isFalse();
        assertThat(retry.resultMatches()).isFalse();
        // same input, different answer
        var otherAnswer = life.record(judgment(input, "0.1", jobId));
        assertThat(otherAnswer.inputMatches()).isTrue();
        assertThat(otherAnswer.resultMatches()).isFalse();
        // the job id reused for another issue
        var otherIssue = life.record(judgment(life.readInput(2), "0.8", jobId));
        assertThat(otherIssue.inputMatches()).isFalse();
        assertThat(otherIssue.judgment()).isEqualTo(first.judgment());
        assertThat(count("SELECT count(*) FROM life_judgment")).isEqualTo(1);
        // a new request is a new job and a new row (D2)
        assertThat(life.record(judgment(life.readInput(1), "0.1", job())).created()).isTrue();
        assertThat(count("SELECT count(*) FROM life_judgment")).isEqualTo(2);
    }

    @Test
    void exclusionNeedsAReasonSurvivesReJudgmentAndIsAudited() {
        for (String blank : new String[] {null, "", "  ", "\t\n "}) {
            assertThatThrownBy(() -> life.exclude(1, "op", blank, null)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> life.exclude(1, "op", "가".repeat(501), null)).isInstanceOf(BusinessException.class);
        assertThat(life.exclude(2, "op", "가".repeat(500), null).reason()).hasSize(500);
        // the DB rejects them too, for writers that skip the service
        for (String blank : new String[] {null, "", " ", "\t\n "}) {
            assertThatThrownBy(() -> jdbc.sql("INSERT INTO life_exclusion (issue_id, excluded, reason, updated_by) VALUES (1, TRUE, :r, 'op')")
                    .param("r", blank).update()).as(String.valueOf(blank)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO life_exclusion (issue_id, excluded, reason, updated_by) VALUES (1, TRUE, :r, 'op')")
                .param("r", "가".repeat(501)).update()).isInstanceOf(DataIntegrityViolationException.class);

        judge(1, "0.9");
        var ex = life.exclude(1, "op", " 광고성 이슈 ", "corr-1");
        assertThat(ex.excluded()).isTrue();
        assertThat(ex.reason()).isEqualTo("광고성 이슈");
        // D5: re-judgments never lift it, and the judgment status is still computed next to it
        judge(1, "0.95");
        management.update(1, new IssuePatch("제목 변경", null, null, null), "op", null, null);
        judge(1, "0.97");
        var state = life.state(1);
        assertThat(state.excluded()).isTrue();
        assertThat(state.status()).isEqualTo(Status.JUDGED);
        assertThat(state.exclusion().reason()).isEqualTo("광고성 이슈");

        // the same exclusion again changes nothing and adds no audit row
        life.exclude(1, "op", "광고성 이슈", null);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action LIKE 'LIFE_%' AND target_id = '1'")).isEqualTo(1);

        var in = life.include(1, "op2", null, "corr-2");
        assertThat(in.excluded()).isFalse();
        assertThat(in.reason()).isNull();
        assertThat(in.updatedBy()).isEqualTo("op2");
        assertThat(life.state(1).excluded()).isFalse();
        life.include(1, "op2", null, null);   // already included: no-op

        var audit = jdbc.sql("""
                SELECT action || '|' || actor || '|' || target_type || '|' || target_id || '|' || CAST(before_state AS text)
                       || '|' || CAST(after_state AS text) || '|' || COALESCE(reason, '-') || '|' || COALESCE(correlation_id, '-')
                FROM admin_audit_log WHERE action LIKE 'LIFE_%' AND target_id = '1' ORDER BY id""").query(String.class).list();
        assertThat(audit).containsExactly(
                "LIFE_EXCLUDE|op|ISSUE|1|{\"reason\": null, \"lifeExcluded\": false}|{\"reason\": \"광고성 이슈\", \"lifeExcluded\": true}|광고성 이슈|corr-1",
                "LIFE_INCLUDE|op2|ISSUE|1|{\"reason\": \"광고성 이슈\", \"lifeExcluded\": true}|{\"reason\": null, \"lifeExcluded\": false}|-|corr-2");
    }

    @Test
    void aFailedAuditWriteRollsTheExclusionBack() {
        // test-only trigger: makes the audit insert fail inside the exclusion transaction
        jdbc.sql("""
                CREATE FUNCTION test_fail_life_audit() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'audit write failed (test)'; END $$ LANGUAGE plpgsql""").update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_life_audit BEFORE INSERT ON admin_audit_log FOR EACH ROW
                WHEN (NEW.action LIKE 'LIFE_%') EXECUTE FUNCTION test_fail_life_audit()""").update();
        try {
            assertThatThrownBy(() -> life.exclude(1, "op", "사유", null)).isInstanceOf(RuntimeException.class);
            assertThat(count("SELECT count(*) FROM life_exclusion")).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER test_fail_life_audit ON admin_audit_log").update();
            jdbc.sql("DROP FUNCTION test_fail_life_audit()").update();
        }
        assertThat(life.exclude(1, "op", "사유", null).excluded()).isTrue();
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'LIFE_EXCLUDE'")).isEqualTo(1);
    }

    @Test
    void judgingAndExcludingNeverTouchTheIssue() {
        var before = jdbc.sql("SELECT change_seq || '|' || status || '|' || updated_at FROM issue WHERE id = 1")
                .query(String.class).single();
        judge(1, "0.5");
        life.exclude(1, "op", "사유", null);
        life.include(1, "op", null, null);
        assertThat(jdbc.sql("SELECT change_seq || '|' || status || '|' || updated_at FROM issue WHERE id = 1")
                .query(String.class).single()).isEqualTo(before);
        // and LIFE exclusion is not issue status EXCLUDED
        life.exclude(1, "op", "사유", null);
        assertThat(jdbc.sql("SELECT status FROM issue WHERE id = 1").query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void inputSnapshotMatchesItsChangeSeqAndCarriesNoArticleText() throws Exception {
        JudgmentInput input = life.readInput(1);
        assertThat(input.changeSeq()).isEqualTo(changeSeq(1));
        assertThat(input.status()).isEqualTo("ACTIVE");
        JsonNode snap = M.readTree(input.snapshotJson());
        assertThat(M.convertValue(snap, java.util.Map.class).keySet())
                .containsExactlyInAnyOrder("title", "summary", "category", "keyFacts", "entities", "articleIds");
        assertThat(snap.get("title").asText()).isEqualTo(
                jdbc.sql("SELECT title FROM issue WHERE id = 1").query(String.class).single());
        assertThat(snap.get("articleIds").toString()).isEqualTo("[1,2]");
        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.snapshotJson().getBytes(StandardCharsets.UTF_8)));
        assertThat(input.hash()).isEqualTo(sha);

        // stored as read
        Judgment j = life.record(judgment(input, "0.5", null)).judgment();
        assertThat(j.inputChangeSeq()).isEqualTo(input.changeSeq());
        assertThat(j.inputHash()).isEqualTo(input.hash());
        assertThat(M.readTree(j.inputSnapshotJson())).isEqualTo(snap);

        // writes that do not raise change_seq leave the input as it was
        aggregates.refresh(1);
        jdbc.sql("UPDATE issue SET embedding = embedding, updated_at = now() + interval '1 day' WHERE id = 1").update();
        jdbc.sql("UPDATE news_article SET analysis_json = '{\"summary\":\"재분석\"}' WHERE id = 1").update();
        JudgmentInput same = life.readInput(1);
        assertThat(same.changeSeq()).isEqualTo(input.changeSeq());
        assertThat(same.hash()).isEqualTo(input.hash());

        // a membership change moves both
        management.move(3, 1, "op", "같은 사건", null);
        JudgmentInput moved = life.readInput(1);
        assertThat(moved.changeSeq()).isGreaterThan(input.changeSeq());
        assertThat(moved.hash()).isNotEqualTo(input.hash());
        assertThat(M.readTree(moved.snapshotJson()).get("articleIds").toString()).isEqualTo("[1,2,3]");
    }

    @Test
    void aStatusOnlyChangeMarksTheJudgmentStale() {
        judge(1, "0.8");
        management.update(1, new IssuePatch(null, null, null, "CLOSED"), "op", null, null);
        assertThat(life.state(1).status()).isEqualTo(Status.STALE);
    }

    @Test
    void mergeRaisesTheTargetAndKeepsTheSourceJudgmentAsHistory() {
        Judgment target = judge(1, "0.8");
        Judgment source = judge(2, "0.3");
        long targetSeq = changeSeq(1);
        management.merge(1, List.of(2L), "op", "같은 사건", null);
        assertThat(changeSeq(1)).isGreaterThan(targetSeq);
        assertThat(life.state(1).status()).isEqualTo(Status.STALE);
        assertThat(life.state(1).current().id()).isEqualTo(target.id());
        // the merged issue keeps its row; nothing is copied to the target
        assertThat(life.history(2)).containsExactly(source);
        assertThat(life.history(1)).containsExactly(target);
        assertThat(life.state(2).status()).isEqualTo(Status.STALE);
    }

    @Test
    void splitLeavesTheNewIssueUnjudgedAndTheSourceStale() {
        judge(1, "0.8");
        var result = management.split(1, List.of(2L), null, "op", "다른 사건", null);
        assertThat(life.state(result.newIssueId()).status()).isEqualTo(Status.UNJUDGED);
        assertThat(changeSeq(result.newIssueId())).isPositive();
        assertThat(life.state(1).status()).isEqualTo(Status.STALE);
    }

    @Test
    void moveRaisesTheIssueTheArticleLeft() {
        judge(1, "0.8");
        judge(2, "0.4");
        long leftSeq = changeSeq(1);
        management.move(2, 2, "op", "다른 사건", null);
        assertThat(changeSeq(1)).isGreaterThan(leftSeq);
        assertThat(life.state(1).status()).isEqualTo(Status.STALE);
        assertThat(life.state(2).status()).isEqualTo(Status.STALE);
    }

    @Test
    void unknownIssuesAreNotFound() {
        for (Runnable call : List.<Runnable>of(() -> life.readInput(99), () -> life.state(99), () -> life.history(99),
                () -> life.exclude(99, "op", "사유", null), () -> life.include(99, "op", null, null))) {
            assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ISSUE_NOT_FOUND);
        }
    }
}
