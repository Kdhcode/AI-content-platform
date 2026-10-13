package com.aicontent.platform.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How well the article-analysis model flags bundle articles ({@code multiEvent}) against the gold label. A missed
 * bundle is the costly error: it reaches the same-event judge and may be linked to an issue automatically. A false
 * alarm only sends a normal article to REVIEW (lost automation).
 */
public final class MultiEventMetrics {

    /**
     * @param failed     cases with no prediction (analysis output failed the schema or the call failed)
     * @param missed     gold bundles predicted as single-event (ids)
     * @param falseAlarm single-event articles predicted as bundles (ids)
     */
    public record Report(int truePositive, int falsePositive, int falseNegative, int trueNegative, int failed,
                         List<String> missed, List<String> falseAlarm) {

        /** Share of gold bundles that were flagged; null when the set has no bundle with a prediction. */
        public Double recall() {
            int bundles = truePositive + falseNegative;
            return bundles == 0 ? null : (double) truePositive / bundles;
        }
    }

    private MultiEventMetrics() {}

    public static Report compute(List<EvalCase> cases, Map<String, Boolean> predicted) {
        int tp = 0, fp = 0, fn = 0, tn = 0, failed = 0;
        List<String> missed = new ArrayList<>();
        List<String> falseAlarm = new ArrayList<>();
        for (EvalCase c : cases) {
            Boolean p = predicted.get(c.id());
            if (p == null) {
                failed++;
            } else if (c.multiEvent() && p) {
                tp++;
            } else if (c.multiEvent()) {
                fn++;
                missed.add(c.id());
            } else if (p) {
                fp++;
                falseAlarm.add(c.id());
            } else {
                tn++;
            }
        }
        return new Report(tp, fp, fn, tn, failed, missed, falseAlarm);
    }

    public static String format(Report r) {
        return String.format("bundles flagged=%d missed=%d %s  falseAlarms=%d %s  correctSingles=%d  analysisFailed=%d  bundleRecall=%s",
                r.truePositive(), r.falseNegative(), r.missed(), r.falsePositive(), r.falseAlarm(), r.trueNegative(), r.failed(),
                r.recall() == null ? "n/a" : String.format("%.3f", r.recall()));
    }
}
