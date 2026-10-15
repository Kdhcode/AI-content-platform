package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.admin.IssueQueryRepository;
import com.aicontent.platform.issue.IssueManagementService;
import com.aicontent.platform.job.JobType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * First report held for review -> follow-up arrives -> admin decides. The stub judge says SAME_ISSUE when titles
 * overlap enough, NEW otherwise; the stub embedding puts similar titles close together.
 */
class PendingReviewFollowUpIntegrationTest extends IntegrationTestBase {

    @Autowired IssueManagementService management;
    @Autowired IssueQueryRepository issueQueries;

    private static final String FIRST = article("가상시 B동 공장 폭발 사고 발생 주민 대피", "https://example.test/p/1", "가상일보", "2026-10-01T09:00:00+09:00");
    private static final String FOLLOW = article("가상시 B동 공장 폭발 사고 발생 부상자 이송", "https://example.test/p/2", "샘플뉴스", "2026-10-01T11:00:00+09:00");
    private static final String OTHER = article("허구군 시립도서관 야간 개방 시범 운영 시작", "https://example.test/p/3", "예시방송", "2026-10-01T12:00:00+09:00");

    /** Collects the given articles and runs the whole chain. */
    private void collect(String source, String... articles) throws Exception {
        long id = fixtureSource(source, "[" + String.join(",", articles) + "]");
        jobsService.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", id), "t", "collect:" + id, null);
        drain();
    }

    /** The first report is held: its issue and the article are REVIEW, as after a guard downgrade. */
    private long holdFirstReport() throws Exception {
        collect("src-a", FIRST);
        long issueId = count("SELECT id FROM issue");
        jdbc.sql("UPDATE issue SET status = 'REVIEW' WHERE id = :id").param("id", issueId).update();
        jdbc.sql("UPDATE news_article SET classification_status = 'REVIEW'").update();
        return issueId;
    }

    private String run(long articleId) {
        return jdbc.sql("""
                SELECT decision || '/' || coalesce(raw_decision, '-') || '/' || method || '/' || coalesce(matched_issue_id::text, '-')
                       || '/' || applied || '/' || coalesce(applied_issue_id::text, '-')
                FROM classification_run WHERE article_id = :a ORDER BY id DESC LIMIT 1""").param("a", articleId).query(String.class).single();
    }

    @Test
    void followUpIsHeldWithThePendingIssueAndTheAdminDecisionCoversBoth() throws Exception {
        long pending = holdFirstReport();
        collect("src-b", FOLLOW, OTHER);
        long follow = count("SELECT id FROM news_article WHERE original_url = 'https://example.test/p/2'");
        long other = count("SELECT id FROM news_article WHERE original_url = 'https://example.test/p/3'");

        // the follow-up found the pending issue as a candidate and joined it, still REVIEW
        assertThat(run(follow)).isEqualTo("REVIEW/SAME_ISSUE/LLM/" + pending + "/true/" + pending);
        assertThat(jdbc.sql("SELECT candidates::text FROM classification_run WHERE article_id = :a").param("a", follow)
                .query(String.class).single()).contains("\"status\": \"REVIEW\"");
        assertThat(jdbc.sql("SELECT classification_method FROM issue_article WHERE article_id = :a").param("a", follow)
                .query(String.class).single()).isEqualTo("LLM_REVIEW");
        assertThat(jdbc.sql("SELECT classification_status FROM news_article WHERE id = :a").param("a", follow)
                .query(String.class).single()).isEqualTo("REVIEW");
        assertThat(jdbc.sql("SELECT status || '/' || article_count FROM issue WHERE id = :id").param("id", pending)
                .query(String.class).single()).isEqualTo("REVIEW/2");       // not made ACTIVE automatically

        // the admin detail tells the held follow-up from the first report and lists what it was compared with
        var rows = issueQueries.detail(pending).orElseThrow().articles();
        var heldRow = rows.stream().filter(a -> a.articleId() == follow).findFirst().orElseThrow();
        assertThat(heldRow.heldWithPendingIssue()).isTrue();
        assertThat(heldRow.runMethod()).isEqualTo("LLM");
        assertThat(heldRow.llmReason()).startsWith("검수 대기 ISSUE #" + pending);
        assertThat(heldRow.candidates().get(0).get("issueId").asLong()).isEqualTo(pending);
        assertThat(heldRow.candidates().get(0).get("status").asText()).isEqualTo("REVIEW");
        assertThat(rows.stream().filter(a -> a.articleId() != follow)).allMatch(a -> !a.heldWithPendingIssue());

        // a different event did not join the pending issue
        assertThat(count("SELECT count(*) FROM issue_article WHERE article_id = " + other + " AND issue_id = " + pending)).isZero();
        assertThat(jdbc.sql("SELECT i.status FROM issue i JOIN issue_article ia ON ia.issue_id = i.id WHERE ia.article_id = :a")
                .param("a", other).query(String.class).single()).isEqualTo("ACTIVE");

        // running the classification again only records a run: no second link, no new issue
        long issues = count("SELECT count(*) FROM issue");
        jobsService.enqueue(JobType.CLASSIFY_ARTICLE, Map.of("articleId", follow), "t", null, null);
        drain();
        assertThat(count("SELECT count(*) FROM issue_article WHERE article_id = " + follow)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM issue")).isEqualTo(issues);
        assertThat(count("SELECT count(*) FROM classification_run WHERE article_id = " + follow)).isEqualTo(2);

        // the admin confirms the pending issue: both articles become CLASSIFIED together
        management.update(pending, new IssueManagementService.IssuePatch(null, null, null, "ACTIVE"), "admin", "같은 사건 확인", null);
        assertThat(count("SELECT count(*) FROM news_article a JOIN issue_article ia ON ia.article_id = a.id "
                + "WHERE ia.issue_id = " + pending + " AND a.classification_status = 'CLASSIFIED'")).isEqualTo(2);
    }

    @Test
    void adminCanStillSplitAWronglyHeldArticleOut() throws Exception {
        long pending = holdFirstReport();
        collect("src-b", FOLLOW);
        long follow = count("SELECT id FROM news_article WHERE original_url = 'https://example.test/p/2'");
        assertThat(run(follow)).startsWith("REVIEW/SAME_ISSUE/LLM/" + pending);

        var split = management.split(pending, java.util.List.of(follow), null, "admin", "다른 사건", null);
        assertThat(split.movedArticleIds()).containsExactly(follow);
        assertThat(jdbc.sql("SELECT classification_status FROM news_article WHERE id = :a").param("a", follow)
                .query(String.class).single()).isEqualTo("CLASSIFIED");
        assertThat(jdbc.sql("SELECT status FROM issue WHERE id = :id").param("id", pending).query(String.class).single())
                .isEqualTo("REVIEW");                                          // the first report still waits for the admin
    }

    @Test
    void aPendingIssueOpenedForABundleArticleIsNotACandidate() throws Exception {
        long pending = holdFirstReport();
        // mark the pending issue as opened for a multi-event (bundle) article
        jdbc.sql("UPDATE classification_run SET method = 'MULTI_EVENT', decision = 'REVIEW', applied_issue_id = :id")
                .param("id", pending).update();
        collect("src-b", FOLLOW);
        long follow = count("SELECT id FROM news_article WHERE original_url = 'https://example.test/p/2'");

        assertThat(count("SELECT count(*) FROM issue_article WHERE article_id = " + follow + " AND issue_id = " + pending)).isZero();
        assertThat(run(follow)).startsWith("NEW_ISSUE/-/NO_CANDIDATE");
    }

    @Test
    void anActiveMatchIsStillAutoLinkedAsBefore() throws Exception {
        collect("src-a", FIRST);
        collect("src-b", FOLLOW);
        long follow = count("SELECT id FROM news_article WHERE original_url = 'https://example.test/p/2'");
        assertThat(run(follow)).isEqualTo("SAME_ISSUE/SAME_ISSUE/LLM/1/true/1");
        assertThat(jdbc.sql("SELECT classification_status FROM news_article WHERE id = :a").param("a", follow)
                .query(String.class).single()).isEqualTo("CLASSIFIED");
    }
}
