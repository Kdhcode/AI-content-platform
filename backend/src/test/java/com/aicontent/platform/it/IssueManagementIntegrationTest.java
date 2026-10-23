package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.aicontent.platform.issue.IssueManagementService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Starts from issue #1 {articles 1,2} and issue #2 {article 3} (see IntegrationTestBase.collectSample). */
class IssueManagementIntegrationTest extends IntegrationTestBase {

    @Autowired IssueManagementService management;

    @BeforeEach
    void sample() throws Exception {
        collectSample();
        assertThat(count("SELECT count(*) FROM issue")).isEqualTo(2);
    }

    private int articleCount(long issueId) {
        return jdbc.sql("SELECT article_count FROM issue WHERE id = :i").param("i", issueId).query(Integer.class).single();
    }

    @Test
    void mergeMovesAllMembersKeepsTheSourceAsMergedAndRecalculates() {
        var result = management.merge(1, List.of(2L), "admin", "same story", "c1");

        assertThat(result.movedArticleIds()).containsExactly(3L);
        assertThat(articleCount(1)).isEqualTo(3);
        assertThat(articleCount(2)).isEqualTo(0);
        assertThat(jdbc.sql("SELECT status || '/' || merged_into_issue_id FROM issue WHERE id = 2").query(String.class).single())
                .isEqualTo("MERGED/1");
        assertThat(jdbc.sql("SELECT publisher_count FROM issue WHERE id = 1").query(Integer.class).single()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM issue_article WHERE issue_id = 1 AND is_primary")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'ISSUE_MERGE' AND correlation_id = 'c1'")).isEqualTo(1);
    }

    @Test
    void mergeRejectsSelfMissingAndAlreadyMergedSources() {
        assertThatThrownBy(() -> management.merge(1, List.of(1L), "a", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_MERGE_SELF));
        assertThatThrownBy(() -> management.merge(1, List.of(99L), "a", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_NOT_FOUND));
        management.merge(1, List.of(2L), "a", null, null);
        assertThatThrownBy(() -> management.merge(1, List.of(2L), "a", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_MERGE_SOURCE_INVALID));
        assertThat(articleCount(1)).isEqualTo(3);        // failed attempts changed nothing
    }

    @Test
    void splitCreatesANewIssueAndRecalculatesBoth() {
        var result = management.split(1, List.of(2L), "새 이슈", "admin", null, null);

        assertThat(articleCount(1)).isEqualTo(1);
        assertThat(articleCount(result.newIssueId())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT title FROM issue WHERE id = :i").param("i", result.newIssueId()).query(String.class).single()).isEqualTo("새 이슈");
        assertThat(jdbc.sql("SELECT classification_method || '/' || manually_corrected FROM issue_article WHERE article_id = 2").query(String.class).single())
                .isEqualTo("SPLIT/true");
        assertThat(jdbc.sql("SELECT publisher_count FROM issue WHERE id = 1").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void splitCannotEmptyTheSourceOrTakeForeignArticles() {
        assertThatThrownBy(() -> management.split(1, List.of(1L, 2L), null, "a", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_SPLIT_WOULD_EMPTY));
        assertThatThrownBy(() -> management.split(1, List.of(3L), null, "a", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ARTICLE_NOT_IN_ISSUE));
        assertThat(count("SELECT count(*) FROM issue")).isEqualTo(2);
    }

    @Test
    void moveRepointsOneArticleAndClosesAnIssueThatBecomesEmpty() {
        management.move(3, 1, "admin", "wrong issue", null);

        assertThat(articleCount(1)).isEqualTo(3);
        assertThat(articleCount(2)).isEqualTo(0);
        assertThat(jdbc.sql("SELECT status FROM issue WHERE id = 2").query(String.class).single()).isEqualTo("CLOSED");
        assertThat(jdbc.sql("SELECT manually_corrected FROM issue_article WHERE article_id = 3").query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> management.move(3, 1, "admin", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void manualSummaryIsNotOverwrittenByLaterRecalculation() {
        management.update(1, new IssueManagementService.IssuePatch(null, "관리자가 쓴 요약", null, null), "admin", null, null);
        management.split(1, List.of(2L), null, "admin", null, null);          // triggers a refresh of issue 1
        assertThat(jdbc.sql("SELECT summary || '/' || summary_source FROM issue WHERE id = 1").query(String.class).single())
                .isEqualTo("관리자가 쓴 요약/MANUAL");
    }

    private static final String TITLE_1 = "가상시 A동 상가 화재 발생 주민 대피";   // article 1, 09:00
    private static final String TITLE_2 = "가상시 A동 상가 화재 발생 소방 출동";   // article 2, 10:00

    private String titleAndSource(long issueId) {
        return jdbc.sql("SELECT title || '/' || title_source FROM issue WHERE id = :i").param("i", issueId).query(String.class).single();
    }

    @Test
    void autoTitleFollowsTheEarliestArticleAfterASplit() {
        assertThat(titleAndSource(1)).isEqualTo(TITLE_1 + "/AUTO");

        var result = management.split(1, List.of(1L), null, "admin", null, null);   // the article that gave the title leaves

        assertThat(titleAndSource(1)).isEqualTo(TITLE_2 + "/AUTO");
        assertThat(titleAndSource(result.newIssueId())).isEqualTo(TITLE_1 + "/AUTO");
    }

    @Test
    void adminTitleIsNeverReplaced() {
        management.update(1, new IssueManagementService.IssuePatch("관리자 제목", null, null, null), "admin", null, null);
        management.split(1, List.of(1L), null, "admin", null, null);
        assertThat(titleAndSource(1)).isEqualTo("관리자 제목/MANUAL");
    }

    @Test
    void titleGivenWhenSplittingIsKept() {
        var result = management.split(1, List.of(2L), "분리 제목", "admin", null, null);
        management.move(1, result.newIssueId(), "admin", null, null);   // an earlier article joins: still the admin's title
        assertThat(titleAndSource(result.newIssueId())).isEqualTo("분리 제목/MANUAL");
    }

    @Test
    void mergedIssuesCannotBeEditedAndMergedStatusCannotBeSetByHand() {
        management.merge(1, List.of(2L), "admin", null, null);
        assertThatThrownBy(() -> management.update(2, new IssueManagementService.IssuePatch("x", null, null, null), "a", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ISSUE_STATE_INVALID));
        assertThatThrownBy(() -> management.update(1, new IssueManagementService.IssuePatch(null, null, null, "MERGED"), "a", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private String issueRow(long id) {
        return jdbc.sql("""
                SELECT title || '|' || coalesce(summary, '') || '|' || coalesce(category, '') || '|' || status || '|'
                       || title_source || '|' || summary_source || '|' || updated_at
                FROM issue WHERE id = :i""").param("i", id).query(String.class).single();
    }

    @Test
    void anEditWithNoFieldIsRefusedAndChangesNothing() {
        String before = issueRow(1);
        assertThatThrownBy(() -> management.update(1, new IssueManagementService.IssuePatch(null, null, null, null), "admin", "r", null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThat(issueRow(1)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'ISSUE_UPDATE'")).isZero();
    }

    @Test
    void sendingTheCurrentValuesSucceedsWithoutTouchingTheIssueOrTheAuditLog() {
        var head = jdbc.sql("SELECT title, summary, category, status FROM issue WHERE id = 1")
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}).single();
        String before = issueRow(1);
        // title is compared after trimming, as it is stored
        management.update(1, new IssueManagementService.IssuePatch("  " + head[0] + " ", head[1], head[2], head[3]), "admin", "r", null);
        assertThat(issueRow(1)).isEqualTo(before);   // same data, same sources (still AUTO), same updated_at
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'ISSUE_UPDATE'")).isZero();
    }

    @Test
    void theAuditRecordListsOnlyTheFieldsThatChanged() {
        String oldTitle = jdbc.sql("SELECT title FROM issue WHERE id = 1").query(String.class).single();
        String oldSummary = jdbc.sql("SELECT summary FROM issue WHERE id = 1").query(String.class).single();
        // summary sent unchanged, category and status omitted
        management.update(1, new IssueManagementService.IssuePatch("새 제목", oldSummary, null, null), "admin", "r", "c-audit");
        assertThat(jdbc.sql("SELECT title || '/' || title_source || '/' || summary_source FROM issue WHERE id = 1")
                .query(String.class).single()).isEqualTo("새 제목/MANUAL/AUTO");
        assertThat(jdbc.sql("SELECT before_state::text FROM admin_audit_log WHERE correlation_id = 'c-audit'").query(String.class).single())
                .isEqualTo("{\"title\": \"" + oldTitle + "\"}");
        assertThat(jdbc.sql("SELECT after_state::text FROM admin_audit_log WHERE correlation_id = 'c-audit'").query(String.class).single())
                .isEqualTo("{\"title\": \"새 제목\"}");                      // no "null" strings, no summaryChanged
        management.update(1, new IssueManagementService.IssuePatch(null, "바뀐 요약", null, "EXCLUDED"), "admin", null, "c-audit-2");
        assertThat(jdbc.sql("SELECT after_state::text FROM admin_audit_log WHERE correlation_id = 'c-audit-2'").query(String.class).single())
                .isEqualTo("{\"status\": \"EXCLUDED\", \"summaryChanged\": true}");
    }
}
