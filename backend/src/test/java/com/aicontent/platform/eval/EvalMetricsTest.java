package com.aicontent.platform.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aicontent.platform.eval.CaseResult.Gold;
import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvalMetricsTest {

    private static CaseResult r(String id, Gold gold, boolean amb, Decision d, Method m, boolean sameOk) {
        return new CaseResult(id, gold, amb, d, m, sameOk);
    }

    @Test
    void hand_computed_example() {
        var results = List.of(
                r("1", Gold.NEW, false, Decision.NEW_ISSUE, Method.NO_CANDIDATE, false),     // correct auto NEW
                r("2", Gold.SAME, false, Decision.SAME_ISSUE, Method.LLM, true),              // correct auto SAME
                r("3", Gold.SAME, false, Decision.SAME_ISSUE, Method.LLM, false),             // FALSE_MERGE (wrong issue)
                r("4", Gold.NEW, false, Decision.SAME_ISSUE, Method.LLM, false),              // FALSE_MERGE (first report)
                r("5", Gold.SAME, false, Decision.NEW_ISSUE, Method.LLM, false),              // MISSED_MERGE
                r("6", Gold.SAME, false, Decision.REVIEW, Method.GUARD_DOWNGRADE, false),     // review could be same
                r("7", Gold.BUNDLE, false, Decision.REVIEW, Method.MULTI_EVENT, false),       // fine
                r("8", Gold.SAME, true, Decision.REVIEW, Method.LLM, false),                  // ambiguous review: fine
                r("9", Gold.NEW, false, Decision.REVIEW, Method.INVALID_OUTPUT, false));      // review could be new + invalid
        var m = EvalMetrics.compute(results);

        assertEquals(9, m.total());
        assertEquals(3, m.autoSame());
        assertEquals(1, m.correctAutoSame());
        assertEquals(4, m.goldSame());                       // cases 2,3,5,6 (8 is ambiguous)
        assertEquals(1.0 / 3, m.precision(), 1e-9);
        assertEquals(1.0 / 4, m.recall(), 1e-9);
        assertEquals(2.0 / 9, m.wrongAutoSameRate(), 1e-9);
        assertEquals(4.0 / 9, m.reviewRate(), 1e-9);         // cases 6,7,8,9
        assertEquals(5, m.auto());                           // 1,2,3,4,5
        assertEquals(3, m.wrongAuto());                      // 3,4,5
        assertEquals(3.0 / 5, m.adminEditRate(), 1e-9);
        assertEquals(2, m.errorTypes().get("FALSE_MERGE"));
        assertEquals(1, m.errorTypes().get("MISSED_MERGE"));
        assertEquals(1, m.errorTypes().get("REVIEW_COULD_BE_SAME"));
        assertEquals(1, m.errorTypes().get("REVIEW_COULD_BE_NEW"));
        assertEquals(1, m.errorTypes().get("INVALID_LLM_OUTPUT"));
        assertEquals(1, m.errorTypes().get("GUARD_DOWNGRADE"));
    }

    @Test
    void ambiguousAutoDecisionIsNotCountedWrong() {
        var m = EvalMetrics.compute(List.of(r("1", Gold.SAME, true, Decision.NEW_ISSUE, Method.LLM, false)));
        assertEquals(0, m.wrongAuto());
        assertEquals(0.0, m.adminEditRate(), 1e-9);
    }

    @Test
    void bundleAutoLinkedIsAnError() {
        var m = EvalMetrics.compute(List.of(r("1", Gold.BUNDLE, false, Decision.SAME_ISSUE, Method.LLM, false)));
        assertEquals(1, m.wrongAutoSame());
        assertEquals(1, m.errorTypes().get("BUNDLE_NOT_REVIEWED"));
    }

    @Test
    void emptyAndNoAutoCasesGiveNullRatios() {
        var empty = EvalMetrics.compute(List.of());
        assertEquals(0, empty.total());
        assertNull(empty.precision());
        assertNull(empty.recall());
        assertNull(empty.adminEditRate());
        var onlyReview = EvalMetrics.compute(List.of(r("1", Gold.NEW, false, Decision.REVIEW, Method.LLM, false)));
        assertNull(onlyReview.precision());
        assertNull(onlyReview.adminEditRate());
        assertEquals(1.0, onlyReview.reviewRate(), 1e-9);
    }
}
