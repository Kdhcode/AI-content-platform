package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicontent.platform.issue.IssueManagementService;
import com.aicontent.platform.job.JobType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Review items stay out of the public newsroom until an admin decides. The stub judge says SAME_ISSUE on title overlap,
 * so the Q3 report is matched to the Q2 issue and the period rule turns it into a REVIEW issue; its follow-ups are then
 * held with that pending issue. Only after split / confirm / merge do they show up publicly.
 */
@AutoConfigureMockMvc
class PublicNewsReviewFlowIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired IssueManagementService management;

    private static final String Q2 = article("예시전자 2026년 2분기 영업이익 1조원 발표", "https://example.test/r/1", "가상경제", "2026-10-01T08:00:00+09:00");
    private static final String FIRE = article("가상시 A동 상가 화재 발생 주민 대피", "https://example.test/r/2", "가상일보", "2026-10-01T09:00:00+09:00");
    private static final String Q3 = article("예시전자 2026년 3분기 영업이익 2조원 발표", "https://example.test/r/3", "샘플통신", "2026-10-01T10:00:00+09:00");
    private static final String FIRE_CAUSE = article("가상시 A동 상가 화재 원인 조사 착수", "https://example.test/r/4", "샘플뉴스", "2026-10-01T11:00:00+09:00");
    private static final String Q3_FOLLOW = article("예시전자 2026년 3분기 영업이익 2조원 발표 주가 상승", "https://example.test/r/5", "예시일보", "2026-10-01T12:00:00+09:00");
    private static final String Q3_REACTION = article("예시전자 2026년 3분기 영업이익 발표 협력사 반응", "https://example.test/r/6", "허구방송", "2026-10-01T13:00:00+09:00");

    private void collect(String source, String... articles) throws Exception {
        long id = fixtureSource(source, "[" + String.join(",", articles) + "]");
        jobsService.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", id), "t", "collect:" + id, null);
        drain();
    }

    private long article(int n) {
        return count("SELECT id FROM news_article WHERE original_url = 'https://example.test/r/" + n + "'");
    }

    private long issueOf(long articleId) {
        return count("SELECT issue_id FROM issue_article WHERE is_primary AND article_id = " + articleId);
    }

    /** Public article ids of an issue, or null when the issue is not public (404). */
    private List<Long> publicArticles(long issueId) throws Exception {
        var res = mvc.perform(get("/api/public/issues/" + issueId)).andReturn().getResponse();
        if (res.getStatus() == 404) {
            return null;
        }
        assertThat(res.getStatus()).isEqualTo(200);
        return com.jayway.jsonpath.JsonPath.<List<Number>>read(res.getContentAsString(), "$.data.articles[*].id")
                .stream().map(Number::longValue).sorted().toList();
    }

    private List<Long> publicIssueIds() throws Exception {
        String body = mvc.perform(get("/api/public/issues?size=100")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.<List<Number>>read(body, "$.data.items[*].id")
                .stream().map(Number::longValue).sorted().toList();
    }

    @Test
    void reviewAndHeldArticlesStayHiddenUntilTheAdminSplitsConfirmsOrMerges() throws Exception {
        collect("round1", Q2, FIRE);
        collect("round2", Q3, FIRE_CAUSE);
        collect("round3", Q3_FOLLOW, Q3_REACTION);

        long q2 = issueOf(article(1)), fire = issueOf(article(2)), pending = issueOf(article(3)), cause = issueOf(article(4));
        // the period rule sent the Q3 report to review, and both follow-ups were held with it
        assertThat(jdbc.sql("SELECT status FROM issue WHERE id = :id").param("id", pending).query(String.class).single()).isEqualTo("REVIEW");
        assertThat(jdbc.sql("SELECT method || '/' || left(reason, 5) FROM classification_run WHERE article_id = :a")
                .param("a", article(3)).query(String.class).single()).isEqualTo("GUARD_DOWNGRADE/기간 규칙");
        assertThat(issueOf(article(5))).isEqualTo(pending);
        assertThat(issueOf(article(6))).isEqualTo(pending);

        // public: the pending issue and its held articles appear nowhere
        assertThat(publicIssueIds()).containsExactlyInAnyOrder(q2, fire, cause).doesNotContain(pending);
        assertThat(publicArticles(pending)).isNull();
        for (long visible : List.of(q2, fire, cause)) {
            assertThat(publicArticles(visible)).doesNotContain(article(3), article(5), article(6));
        }

        // split: the reaction piece becomes its own (ACTIVE) issue and is public; the rest still waits
        long split = management.split(pending, List.of(article(6)), null, "admin", "별도 사건", null).newIssueId();
        assertThat(publicArticles(split)).containsExactly(article(6));
        assertThat(publicArticles(pending)).isNull();

        // confirm: the pending issue becomes public with its articles
        management.update(pending, new IssueManagementService.IssuePatch(null, null, null, "ACTIVE"), "admin", "같은 사건 확인", null);
        assertThat(publicArticles(pending)).containsExactly(article(3), article(5));

        // merge: the fire follow-up issue moves into the first fire issue; the merged issue disappears
        management.merge(fire, List.of(cause), "admin", "같은 화재", null);
        assertThat(publicArticles(fire)).containsExactly(article(2), article(4));
        assertThat(publicArticles(cause)).isNull();
        assertThat(publicIssueIds()).containsExactlyInAnyOrder(q2, fire, pending, split);
        assertThat(count("SELECT count(*) FROM news_article WHERE classification_status <> 'CLASSIFIED'")).isZero();
    }
}
