package com.aicontent.platform.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Guards the fixed evaluation set against accidental edits: it is a measuring instrument, not test data. */
class EvalDatasetTest {

    @Test
    void datasetIsConsistent() throws Exception {
        var cases = EvalDatasetLoader.load("eval/issue-eval-v1.json");

        assertThat(cases).hasSize(27);
        assertThat(cases.stream().map(EvalCase::id).collect(Collectors.toSet())).hasSize(cases.size());
        cases.forEach(c -> OffsetDateTime.parse(c.publishedAt()));                       // parseable timestamps

        var groups = cases.stream().filter(c -> !c.multiEvent()).collect(Collectors.groupingBy(EvalCase::eventGroup));
        groups.forEach((group, members) -> {
            var first = members.stream().min(Comparator.comparing(c -> OffsetDateTime.parse(c.publishedAt()))).orElseThrow();
            assertThat(first.ambiguous()).as("first report of " + group + " must be unambiguous").isFalse();
        });
        assertThat(cases.stream().filter(EvalCase::multiEvent)).hasSize(2);
        assertThat(cases.stream().filter(EvalCase::ambiguous)).hasSize(2);
        // contains the counter-example families the classifier prompt talks about
        assertThat(groups.keySet()).contains("G1", "G2", "G3", "G4");
    }
}
