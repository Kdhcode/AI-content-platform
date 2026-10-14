package com.aicontent.platform.eval;

import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Method;

/**
 * Outcome of one evaluation case.
 * {@code gold}: SAME = an issue of the same event already existed, NEW = first report of its event,
 * BUNDLE = multi-event article.
 * {@code held}: the case was attached, still REVIEW, to an issue that is itself waiting for review (see
 * IssueClassificationService): to an issue of the same event, of another event, or not at all.
 */
public record CaseResult(String caseId, Gold gold, boolean ambiguous, Decision decision, Method method,
                         boolean sameIssueCorrect, Held held) {

    public enum Gold { SAME, NEW, BUNDLE }

    public enum Held { NONE, SAME_EVENT, OTHER_EVENT }

    public CaseResult(String caseId, Gold gold, boolean ambiguous, Decision decision, Method method, boolean sameIssueCorrect) {
        this(caseId, gold, ambiguous, decision, method, sameIssueCorrect, Held.NONE);
    }
}
