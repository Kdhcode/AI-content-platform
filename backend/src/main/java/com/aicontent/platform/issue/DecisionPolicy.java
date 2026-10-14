package com.aicontent.platform.issue;

import java.util.Set;

/**
 * Guard rails between the LLM's raw verdict and what the system actually does. The LLM proposes; this class decides
 * whether the proposal is strong and consistent enough to act on automatically. Anything doubtful becomes REVIEW,
 * because a wrong automatic SAME_ISSUE is the costly error (it silently pollutes an issue).
 *
 * <p>Pure logic, no framework: see DecisionPolicyTest.
 */
public final class DecisionPolicy {

    public enum Decision { SAME_ISSUE, NEW_ISSUE, REVIEW }

    /** Matches classification_run.method. */
    public enum Method { LLM, NO_CANDIDATE, GUARD_DOWNGRADE, MULTI_EVENT, INVALID_OUTPUT }

    public record Thresholds(double sameIssueMinConfidence, double newIssueMinConfidence) {}

    /** How the article compares with the chosen (or closest) candidate on each part of "the same event". */
    public enum Match { SAME, DIFFERENT, UNKNOWN, NOT_APPLICABLE }

    /** Itemised comparison reported by classifier prompts from v3 on; null for older prompt versions. */
    public record Comparison(Match actor, Match action, Match period, Match place) {}

    /** Schema-valid LLM answer. */
    public record RawDecision(Decision decision, double confidence, Long matchedIssueId, String reason, Comparison comparison) {
        public RawDecision(Decision decision, double confidence, Long matchedIssueId, String reason) {
            this(decision, confidence, matchedIssueId, reason, null);
        }
    }

    public record Outcome(Decision decision, Method method, Long matchedIssueId, Double confidence, String reason,
                          Decision rawDecision) {}

    private DecisionPolicy() {}

    /** No candidate passed the retrieval filters: nothing to compare with, a new issue is the only possibility. */
    public static Outcome noCandidate() {
        return new Outcome(Decision.NEW_ISSUE, Method.NO_CANDIDATE, null, null, "후보 ISSUE 없음", null);
    }

    /** A bundle article describing several unrelated events cannot belong to one issue. */
    public static Outcome multiEvent() {
        return new Outcome(Decision.REVIEW, Method.MULTI_EVENT, null, null, "여러 사건을 묶은 기사(multiEvent)라 자동 연결하지 않음", null);
    }

    public static Outcome invalidOutput(String why) {
        return new Outcome(Decision.REVIEW, Method.INVALID_OUTPUT, null, null, "LLM 출력 검증 실패: " + why, null);
    }

    public static Outcome evaluate(RawDecision raw, Set<Long> candidateIds, Thresholds t) {
        switch (raw.decision()) {
            case SAME_ISSUE -> {
                if (raw.matchedIssueId() == null || !candidateIds.contains(raw.matchedIssueId())) {
                    return downgrade(raw, "SAME_ISSUE의 matchedIssueId가 후보 목록에 없음");
                }
                if (raw.confidence() < t.sameIssueMinConfidence()) {
                    return downgrade(raw, "SAME_ISSUE 신뢰도 " + raw.confidence() + " < " + t.sameIssueMinConfidence());
                }
                String conflict = comparisonConflict(raw.comparison());
                if (conflict != null) {
                    return downgrade(raw, conflict);
                }
                return llm(raw, raw.matchedIssueId());
            }
            case NEW_ISSUE -> {
                if (raw.confidence() < t.newIssueMinConfidence()) {
                    return downgrade(raw, "NEW_ISSUE 신뢰도 " + raw.confidence() + " < " + t.newIssueMinConfidence());
                }
                return llm(raw, null);
            }
            default -> {
                return llm(raw, null);
            }
        }
    }

    /**
     * A SAME_ISSUE the model's own comparison contradicts (a part marked DIFFERENT) or does not support (actor or action
     * UNKNOWN) is not applied automatically. Period and place may be UNKNOWN: follow-up reports often omit them.
     */
    static String comparisonConflict(Comparison c) {
        if (c == null) {
            return null;
        }
        StringBuilder different = new StringBuilder();
        if (c.actor() == Match.DIFFERENT) different.append(" 행위자");
        if (c.action() == Match.DIFFERENT) different.append(" 행위");
        if (c.period() == Match.DIFFERENT) different.append(" 대상 기간");
        if (c.place() == Match.DIFFERENT) different.append(" 장소");
        if (!different.isEmpty()) {
            return "SAME_ISSUE인데 LLM 비교에서 다름:" + different;
        }
        if (c.actor() == Match.UNKNOWN || c.action() == Match.UNKNOWN) {
            return "SAME_ISSUE인데 행위자·행위 일치 근거 부족(UNKNOWN)";
        }
        return null;
    }

    private static Outcome llm(RawDecision raw, Long matched) {
        return new Outcome(raw.decision(), Method.LLM, matched, raw.confidence(), raw.reason(), raw.decision());
    }

    private static Outcome downgrade(RawDecision raw, String why) {
        return new Outcome(Decision.REVIEW, Method.GUARD_DOWNGRADE, null, raw.confidence(),
                why + " | LLM 사유: " + raw.reason(), raw.decision());
    }
}
