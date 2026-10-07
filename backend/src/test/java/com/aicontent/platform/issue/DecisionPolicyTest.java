package com.aicontent.platform.issue;

import static com.aicontent.platform.issue.DecisionPolicy.Decision.NEW_ISSUE;
import static com.aicontent.platform.issue.DecisionPolicy.Decision.REVIEW;
import static com.aicontent.platform.issue.DecisionPolicy.Decision.SAME_ISSUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicontent.platform.issue.DecisionPolicy.Method;
import com.aicontent.platform.issue.DecisionPolicy.RawDecision;
import com.aicontent.platform.issue.DecisionPolicy.Thresholds;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DecisionPolicyTest {

    private static final Thresholds T = new Thresholds(0.85, 0.70);
    private static final Set<Long> CANDIDATES = Set.of(10L, 20L);

    @Test
    void confidentSameIssueInCandidatesIsApplied() {
        var o = DecisionPolicy.evaluate(new RawDecision(SAME_ISSUE, 0.9, 10L, "r"), CANDIDATES, T);
        assertEquals(SAME_ISSUE, o.decision());
        assertEquals(Method.LLM, o.method());
        assertEquals(Long.valueOf(10L), o.matchedIssueId());
    }

    @Test
    void sameIssueExactlyAtThresholdIsApplied() {
        assertEquals(SAME_ISSUE, DecisionPolicy.evaluate(new RawDecision(SAME_ISSUE, 0.85, 20L, "r"), CANDIDATES, T).decision());
    }

    @Test
    void lowConfidenceSameIssueIsDowngradedToReview() {
        var o = DecisionPolicy.evaluate(new RawDecision(SAME_ISSUE, 0.84, 10L, "r"), CANDIDATES, T);
        assertEquals(REVIEW, o.decision());
        assertEquals(Method.GUARD_DOWNGRADE, o.method());
        assertEquals(SAME_ISSUE, o.rawDecision());
        assertNull(o.matchedIssueId());
    }

    @Test
    void sameIssueWithIdOutsideCandidatesIsDowngraded() {
        var o = DecisionPolicy.evaluate(new RawDecision(SAME_ISSUE, 0.99, 999L, "r"), CANDIDATES, T);
        assertEquals(REVIEW, o.decision());
        assertEquals(Method.GUARD_DOWNGRADE, o.method());
    }

    @Test
    void sameIssueWithoutIdIsDowngraded() {
        assertEquals(REVIEW, DecisionPolicy.evaluate(new RawDecision(SAME_ISSUE, 0.99, null, "r"), CANDIDATES, T).decision());
    }

    @Test
    void newIssueNeedsMinimumConfidence() {
        assertEquals(NEW_ISSUE, DecisionPolicy.evaluate(new RawDecision(NEW_ISSUE, 0.70, null, "r"), CANDIDATES, T).decision());
        var low = DecisionPolicy.evaluate(new RawDecision(NEW_ISSUE, 0.69, null, "r"), CANDIDATES, T);
        assertEquals(REVIEW, low.decision());
        assertEquals(Method.GUARD_DOWNGRADE, low.method());
    }

    @Test
    void reviewStaysReviewViaLlm() {
        var o = DecisionPolicy.evaluate(new RawDecision(REVIEW, 0.2, null, "애매"), CANDIDATES, T);
        assertEquals(REVIEW, o.decision());
        assertEquals(Method.LLM, o.method());
    }

    @Test
    void fixedOutcomes() {
        assertEquals(NEW_ISSUE, DecisionPolicy.noCandidate().decision());
        assertEquals(Method.NO_CANDIDATE, DecisionPolicy.noCandidate().method());
        assertEquals(REVIEW, DecisionPolicy.multiEvent().decision());
        assertEquals(Method.MULTI_EVENT, DecisionPolicy.multiEvent().method());
        var inv = DecisionPolicy.invalidOutput("x");
        assertEquals(REVIEW, inv.decision());
        assertTrue(inv.reason().contains("x"));
    }
}
