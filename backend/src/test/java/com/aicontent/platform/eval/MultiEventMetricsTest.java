package com.aicontent.platform.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiEventMetricsTest {

    private static EvalCase c(String id, boolean multi) {
        return new EvalCase(id, "pub", "2000-01-01T00:00:00Z", "t " + id, "s " + id, List.of("f " + id), List.of(), "G", multi, false, "");
    }

    @Test
    void countsEachOutcome() {
        var cases = List.of(c("b1", true), c("b2", true), c("s1", false), c("s2", false), c("s3", false));
        var predicted = Map.of("b1", true, "b2", false, "s1", true, "s2", false);   // s3: analysis failed

        var r = MultiEventMetrics.compute(cases, predicted);

        assertEquals(1, r.truePositive());
        assertEquals(1, r.falseNegative());
        assertEquals(1, r.falsePositive());
        assertEquals(1, r.trueNegative());
        assertEquals(1, r.failed());
        assertEquals(List.of("b2"), r.missed());
        assertEquals(List.of("s1"), r.falseAlarm());
        assertEquals(0.5, r.recall(), 1e-9);
    }

    @Test
    void recallIsUnknownWithoutAPredictedBundle() {
        assertNull(MultiEventMetrics.compute(List.of(c("b1", true)), Map.of()).recall());
    }

    @Test
    void detectorTextIsSummaryThenFacts() {
        assertEquals("s x\nf x", LlmBundleDetector.text(c("x", false)));
    }
}
