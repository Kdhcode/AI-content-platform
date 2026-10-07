package com.aicontent.platform.eval;

import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Method;

/**
 * Outcome of one evaluation case.
 * {@code gold}: SAME = an issue of the same event already existed, NEW = first report of its event,
 * BUNDLE = multi-event article.
 */
public record CaseResult(String caseId, Gold gold, boolean ambiguous, Decision decision, Method method,
                         boolean sameIssueCorrect) {

    public enum Gold { SAME, NEW, BUNDLE }
}
