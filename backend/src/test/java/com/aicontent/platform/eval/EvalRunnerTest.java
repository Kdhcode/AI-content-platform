package com.aicontent.platform.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aicontent.platform.eval.CaseResult.Gold;
import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Method;
import com.aicontent.platform.issue.DecisionPolicy.RawDecision;
import com.aicontent.platform.issue.DecisionPolicy.Thresholds;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalRunnerTest {

    private static final Thresholds T = new Thresholds(0.85, 0.70);

    private static EvalCase c(String id, String time, String group, boolean multi) {
        return new EvalCase(id, "pub", time, "title " + id, "summary " + id, List.of("fact " + id), List.of(), group, multi, false, "");
    }

    /** Judge scripted per article id: an "LLM" with known behaviour. */
    private static Judge scripted(Map<String, RawDecision> byId) {
        return (article, candidates) -> byId.get(article.id());
    }

    @Test
    void replaysInTimeOrderAndAppliesDecisions() {
        var cases = List.of(
                c("b", "2000-01-02T00:00:00Z", "G1", false),     // follow-up of a
                c("a", "2000-01-01T00:00:00Z", "G1", false),     // first report -> no candidate
                c("d", "2000-01-04T00:00:00Z", "G2", false),     // new event, judge wrongly says SAME
                c("e", "2000-01-05T00:00:00Z", "X", true));      // bundle
        var judge = scripted(Map.of(
                "b", new RawDecision(Decision.SAME_ISSUE, 0.95, 1L, "same"),
                "d", new RawDecision(Decision.SAME_ISSUE, 0.90, 1L, "wrongly same")));
        var results = new EvalRunner(judge, T, 5).run(cases);

        assertEquals(List.of("a", "b", "d", "e"), results.stream().map(CaseResult::caseId).toList());
        assertEquals(Method.NO_CANDIDATE, results.get(0).method());
        assertEquals(Gold.NEW, results.get(0).gold());
        assertEquals(Gold.SAME, results.get(1).gold());
        assertEquals(true, results.get(1).sameIssueCorrect());
        assertEquals(Gold.NEW, results.get(2).gold());
        assertEquals(false, results.get(2).sameIssueCorrect());          // false merge into issue 1
        assertEquals(Gold.BUNDLE, results.get(3).gold());
        assertEquals(Decision.REVIEW, results.get(3).decision());

        var m = EvalMetrics.compute(results);
        assertEquals(1, m.errorTypes().get("FALSE_MERGE"));
    }

    @Test
    void guardDowngradesLowConfidenceSame() {
        var cases = List.of(c("a", "2000-01-01T00:00:00Z", "G1", false), c("b", "2000-01-02T00:00:00Z", "G1", false));
        var judge = scripted(Map.of("b", new RawDecision(Decision.SAME_ISSUE, 0.80, 1L, "unsure")));
        var results = new EvalRunner(judge, T, 5).run(cases);
        assertEquals(Decision.REVIEW, results.get(1).decision());
        assertEquals(Method.GUARD_DOWNGRADE, results.get(1).method());
    }

    @Test
    void candidateLimitCanMakeTheRightIssueUnreachable() {
        var cases = List.of(
                c("a", "2000-01-01T00:00:00Z", "G1", false),
                c("n1", "2000-01-02T00:00:00Z", "G2", false),
                c("n2", "2000-01-03T00:00:00Z", "G3", false),
                c("b", "2000-01-04T00:00:00Z", "G1", false));
        var judge = (Judge) (article, candidates) -> new RawDecision(Decision.NEW_ISSUE, 0.9, null, "none of the candidates");
        var results = new EvalRunner(judge, T, 2).run(cases);        // only the 2 newest issues are candidates
        assertEquals(Decision.NEW_ISSUE, results.get(3).decision());
        assertEquals(Gold.SAME, results.get(3).gold());
        assertEquals(1, EvalMetrics.compute(results).errorTypes().get("MISSED_MERGE"));
    }
}
