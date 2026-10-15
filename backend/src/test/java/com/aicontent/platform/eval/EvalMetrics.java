package com.aicontent.platform.eval;

import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metric definitions (also documented in docs/EVALUATION.md).
 *
 * <p>The headline metrics use only the <b>clear</b> cases (not {@code ambiguous}): a case with no single right
 * answer can neither count as a hit nor as a miss. Ambiguous cases are reported separately ({@link Ambiguous}).
 * Numerator and denominator of every ratio come from the same set, so precision and recall stay within 0..1.
 *
 * <ul>
 *   <li><b>precision</b> = clear cases auto-linked (SAME_ISSUE) to the right issue / clear cases auto-linked (null when none)</li>
 *   <li><b>recall</b> = clear gold-SAME cases auto-linked to the right issue / clear gold-SAME cases (null when none)</li>
 *   <li><b>goldSame breakdown</b> (clear gold-SAME cases, sums to goldSame): linkedCorrect, linkedWrongIssue (auto SAME to
 *       another event's issue), splitAsNew (auto NEW: a missed merge), heldForReview (REVIEW; of those, heldWithSameEvent
 *       were attached to a pending issue of their own event)</li>
 *   <li><b>wrongAutoSameRate</b> = clear wrong auto SAME / N  - the costly error: it pollutes an issue</li>
 *   <li><b>reviewRate</b> = REVIEW decisions / N (all cases) - manual workload</li>
 *   <li><b>adminEditRate</b> = clear wrong auto decisions / clear auto decisions</li>
 *   <li><b>errorTypes</b> (clear cases unless stated): FALSE_MERGE (auto SAME to a wrong issue, or SAME for a first
 *       report), MISSED_MERGE (auto NEW although an issue of the event existed), BUNDLE_NOT_REVIEWED,
 *       REVIEW_COULD_BE_SAME / REVIEW_COULD_BE_NEW (REVIEW where a clear answer existed: lost automation, not wrong),
 *       HELD_WITH_OTHER_EVENT (attached, still REVIEW, to a pending issue of a different event - a grouping the admin
 *       has to undo), INVALID_LLM_OUTPUT and GUARD_DOWNGRADE (all cases, by method)</li>
 * </ul>
 */
public final class EvalMetrics {

    /** Ambiguous cases: only how they were decided, never right or wrong. */
    public record Ambiguous(int total, int autoSame, int autoNew, int review) {}

    public record Report(int total, int clear, int autoSame, int correctAutoSame, int goldSame, int linkedCorrect,
                         int linkedWrongIssue, int splitAsNew, int heldForReview, int heldWithSameEvent, int review,
                         int auto, int wrongAuto, int wrongAutoSame, Double precision, Double recall,
                         double wrongAutoSameRate, double reviewRate, Double adminEditRate, Ambiguous ambiguous,
                         Map<String, Integer> errorTypes) {}

    private EvalMetrics() {}

    public static Report compute(List<CaseResult> results) {
        int total = results.size();
        int clear = 0, autoSame = 0, correctAutoSame = 0, goldSame = 0, review = 0, auto = 0, wrongAuto = 0, wrongAutoSame = 0;
        int linkedCorrect = 0, linkedWrongIssue = 0, splitAsNew = 0, heldForReview = 0, heldWithSameEvent = 0;
        int ambTotal = 0, ambSame = 0, ambNew = 0, ambReview = 0;
        Map<String, Integer> errors = new LinkedHashMap<>();

        for (CaseResult r : results) {
            boolean isReview = r.decision() == Decision.REVIEW;
            if (isReview) {
                review++;
            }
            if (r.method() == Method.INVALID_OUTPUT) {
                bump(errors, "INVALID_LLM_OUTPUT");
            }
            if (r.method() == Method.GUARD_DOWNGRADE) {
                bump(errors, "GUARD_DOWNGRADE");
            }
            if (r.ambiguous()) {
                ambTotal++;
                switch (r.decision()) {
                    case SAME_ISSUE -> ambSame++;
                    case NEW_ISSUE -> ambNew++;
                    default -> ambReview++;
                }
                continue;
            }

            clear++;
            boolean goldIsSame = r.gold() == CaseResult.Gold.SAME;
            if (goldIsSame) {
                goldSame++;
            }
            if (isReview) {
                if (goldIsSame) {
                    heldForReview++;
                    if (r.held() == CaseResult.Held.SAME_EVENT) {
                        heldWithSameEvent++;
                    }
                    bump(errors, "REVIEW_COULD_BE_SAME");
                } else if (r.gold() == CaseResult.Gold.NEW) {
                    bump(errors, "REVIEW_COULD_BE_NEW");
                }
                if (r.held() == CaseResult.Held.OTHER_EVENT) {
                    bump(errors, "HELD_WITH_OTHER_EVENT");
                }
                continue;
            }

            auto++;
            boolean wrong = false;
            if (r.decision() == Decision.SAME_ISSUE) {
                autoSame++;
                if (goldIsSame && r.sameIssueCorrect()) {
                    correctAutoSame++;
                    linkedCorrect++;
                } else {
                    wrong = true;
                    wrongAutoSame++;
                    bump(errors, "FALSE_MERGE");
                    if (goldIsSame) {
                        linkedWrongIssue++;
                    }
                }
                if (r.gold() == CaseResult.Gold.BUNDLE) {
                    bump(errors, "BUNDLE_NOT_REVIEWED");
                }
            } else if (goldIsSame) {
                wrong = true;
                splitAsNew++;
                bump(errors, "MISSED_MERGE");
            } else if (r.gold() == CaseResult.Gold.BUNDLE) {
                wrong = true;
                bump(errors, "BUNDLE_NOT_REVIEWED");
            }
            if (wrong) {
                wrongAuto++;
            }
        }
        Double precision = autoSame == 0 ? null : (double) correctAutoSame / autoSame;
        Double recall = goldSame == 0 ? null : (double) correctAutoSame / goldSame;
        double wrongAutoSameRate = total == 0 ? 0 : (double) wrongAutoSame / total;
        double reviewRate = total == 0 ? 0 : (double) review / total;
        Double adminEditRate = auto == 0 ? null : (double) wrongAuto / auto;
        return new Report(total, clear, autoSame, correctAutoSame, goldSame, linkedCorrect, linkedWrongIssue, splitAsNew,
                heldForReview, heldWithSameEvent, review, auto, wrongAuto, wrongAutoSame, precision, recall,
                wrongAutoSameRate, reviewRate, adminEditRate, new Ambiguous(ambTotal, ambSame, ambNew, ambReview), errors);
    }

    private static void bump(Map<String, Integer> m, String key) {
        m.merge(key, 1, Integer::sum);
    }

    public static String format(Report r) {
        return String.format("N=%d clear=%d  precision=%s (%d/%d)  recall=%s (%d/%d)  goldSame: linked=%d wrongIssue=%d "
                        + "splitAsNew=%d heldForReview=%d (heldWithSameEvent=%d)  wrongAutoSameRate=%.3f  reviewRate=%.3f (%d)  "
                        + "adminEditRate=%s  ambiguous: n=%d same=%d new=%d review=%d  errors=%s",
                r.total(), r.clear(), pct(r.precision()), r.correctAutoSame(), r.autoSame(), pct(r.recall()),
                r.correctAutoSame(), r.goldSame(), r.linkedCorrect(), r.linkedWrongIssue(), r.splitAsNew(),
                r.heldForReview(), r.heldWithSameEvent(), r.wrongAutoSameRate(), r.reviewRate(), r.review(),
                pct(r.adminEditRate()), r.ambiguous().total(), r.ambiguous().autoSame(), r.ambiguous().autoNew(),
                r.ambiguous().review(), r.errorTypes());
    }

    private static String pct(Double v) {
        return v == null ? "n/a" : String.format("%.3f", v);
    }
}
