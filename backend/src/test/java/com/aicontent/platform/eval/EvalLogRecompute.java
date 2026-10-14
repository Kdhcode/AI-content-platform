package com.aicontent.platform.eval;

import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Recomputes {@link EvalMetrics} from the per-case lines an earlier {@code EvalLlmIT} run printed ("CaseResult[...]"),
 * so a metric fix can be applied to past runs without calling the model again. No AI call.
 * Enabled with {@code -Deval.logs=<log1>,<log2>,...}; the report goes to {@code -Deval.recompute.out=<file>}.
 */
@EnabledIfSystemProperty(named = "eval.logs", matches = ".+")
class EvalLogRecompute {

    private static final Pattern LINE = Pattern.compile("CaseResult\\[caseId=([^,]+), gold=(\\w+), ambiguous=(\\w+), "
            + "decision=(\\w+), method=(\\w+), sameIssueCorrect=(\\w+)(?:, held=(\\w+))?]");

    static List<CaseResult> parse(List<String> lines) {
        List<CaseResult> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = LINE.matcher(line);
            if (m.find()) {
                out.add(new CaseResult(m.group(1), CaseResult.Gold.valueOf(m.group(2)), Boolean.parseBoolean(m.group(3)),
                        Decision.valueOf(m.group(4)), Method.valueOf(m.group(5)), Boolean.parseBoolean(m.group(6)),
                        m.group(7) == null ? CaseResult.Held.NONE : CaseResult.Held.valueOf(m.group(7))));
            }
        }
        return out;
    }

    @Test
    void recomputesSavedRuns() throws Exception {
        List<String> report = new ArrayList<>();
        for (String file : System.getProperty("eval.logs").split(",")) {
            Path path = Path.of(file.trim());
            List<CaseResult> results = parse(Files.readAllLines(path));
            String old = Files.readAllLines(path).stream().filter(l -> l.startsWith("N=")).findFirst().orElse("(no summary line)");
            report.add(path.getFileName() + "  cases=" + results.size());
            report.add("  printed then: " + old);
            report.add("  recomputed:   " + EvalMetrics.format(EvalMetrics.compute(results)));
        }
        Files.write(Path.of(System.getProperty("eval.recompute.out", "eval-recompute.txt")), report);
    }
}
