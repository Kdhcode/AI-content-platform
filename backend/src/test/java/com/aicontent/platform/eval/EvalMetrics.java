package com.aicontent.platform.eval;

import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metric definitions (also documented in docs/EVALUATION.md). N = all cases, "auto" = decision is not REVIEW.
 *
 * <ul>
 *   <li><b>precision</b> = correct auto SAME_ISSUE / all auto SAME_ISSUE (null when there is none)</li>
 *   <li><b>recall</b> = correct auto SAME_ISSUE / cases whose gold is SAME, ambiguous ones excluded (null when none)</li>
 *   <li><b>wrongAutoSameRate</b> = auto SAME_ISSUE that is wrong / N  - the costly error: it pollutes an issue</li>
 *   <li><b>reviewRate</b> = REVIEW decisions / N  - manual workload</li>
 *   <li><b>adminEditRate</b> = wrong auto decisions / auto decisions  - how often an admin would have to fix an
 *       automatic result (ambiguous cases are not counted as wrong)</li>
 *   <li><b>errorTypes</b>: FALSE_MERGE (auto SAME to a wrong issue, or SAME for a first report),
 *       MISSED_MERGE (auto NEW although an issue of the event existed), BUNDLE_NOT_REVIEWED,
 *       REVIEW_COULD_BE_SAME / REVIEW_COULD_BE_NEW (REVIEW where a clear answer existed: lost automation, not wrong),
 *       INVALID_LLM_OUTPUT (method INVALID_OUTPUT), GUARD_DOWNGRADE (policy overrode the LLM)</li>
 * </ul>
 */
public final class EvalMetrics {

    public record Report(int total, int autoSame, int correctAutoSame, int goldSame, int review, int auto, int wrongAuto,
                         int wrongAutoSame, Double precision, Double recall, double wrongAutoSameRate, double reviewRate,
                         Double adminEditRate, Map<String, Integer> errorTypes) {}

    private EvalMetrics() {}

    public static Report compute(List<CaseResult> results) {
        int total = results.size();
        int autoSame = 0, correctAutoSame = 0, goldSame = 0, review = 0, auto = 0, wrongAuto = 0, wrongAutoSame = 0;
        Map<String, Integer> errors = new LinkedHashMap<>();

        for (CaseResult r : results) {
            boolean isReview = r.decision() == Decision.REVIEW;
            if (r.gold() == CaseResult.Gold.SAME && !r.ambiguous()) {
                goldSame++;
            }
            if (isReview) {
                review++;
                if (!r.ambiguous() && r.gold() == CaseResult.Gold.SAME) {
                    bump(errors, "REVIEW_COULD_BE_SAME");
                } else if (!r.ambiguous() && r.gold() == CaseResult.Gold.NEW) {
                    bump(errors, "REVIEW_COULD_BE_NEW");
                }
            } else {
                auto++;
                boolean wrong = false;
                if (r.decision() == Decision.SAME_ISSUE) {
                    autoSame++;
                    if (r.sameIssueCorrect() && r.gold() == CaseResult.Gold.SAME) {
                        correctAutoSame++;
                    } else if (!r.ambiguous()) {
                        wrong = true;
                        wrongAutoSame++;
                        bump(errors, "FALSE_MERGE");
                    }
                    if (r.gold() == CaseResult.Gold.BUNDLE) {
                        bump(errors, "BUNDLE_NOT_REVIEWED");
                    }
                } else if (r.gold() == CaseResult.Gold.SAME && !r.ambiguous()) {
                    wrong = true;
                    bump(errors, "MISSED_MERGE");
                } else if (r.gold() == CaseResult.Gold.BUNDLE) {
                    wrong = true;
                    bump(errors, "BUNDLE_NOT_REVIEWED");
                }
                if (wrong) {
                    wrongAuto++;
                }
            }
            if (r.method() == Method.INVALID_OUTPUT) {
                bump(errors, "INVALID_LLM_OUTPUT");
            }
            if (r.method() == Method.GUARD_DOWNGRADE) {
                bump(errors, "GUARD_DOWNGRADE");
            }
        }
        Double precision = autoSame == 0 ? null : (double) correctAutoSame / autoSame;
        Double recall = goldSame == 0 ? null : (double) correctAutoSame / goldSame;
        double wrongAutoSameRate = total == 0 ? 0 : (double) wrongAutoSame / total;
        double reviewRate = total == 0 ? 0 : (double) review / total;
        Double adminEditRate = auto == 0 ? null : (double) wrongAuto / auto;
        return new Report(total, autoSame, correctAutoSame, goldSame, review, auto, wrongAuto, wrongAutoSame, precision,
                recall, wrongAutoSameRate, reviewRate, adminEditRate, errors);
    }

    private static void bump(Map<String, Integer> m, String key) {
        m.merge(key, 1, Integer::sum);
    }

    public static String format(Report r) {
        return String.format("N=%d  precision=%s  recall=%s  wrongAutoSameRate=%.3f  reviewRate=%.3f  adminEditRate=%s  errors=%s",
                r.total(), pct(r.precision()), pct(r.recall()), r.wrongAutoSameRate(), r.reviewRate(), pct(r.adminEditRate()),
                r.errorTypes());
    }

    private static String pct(Double v) {
        return v == null ? "n/a" : String.format("%.3f", v);
    }
}
