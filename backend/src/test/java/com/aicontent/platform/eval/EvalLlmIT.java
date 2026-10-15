package com.aicontent.platform.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.ai.PromptRegistry;
import com.aicontent.platform.ai.StructuredOutputService;
import com.aicontent.platform.config.AppProperties;
import com.aicontent.platform.issue.DecisionPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs the fixed set against whatever {@code app.ai.provider} is configured and PRINTS the metrics.
 *
 * <pre>mvn -Dtest=EvalLlmIT -Deval.run=true -Dspring.profiles.active=test -Dapp.ai.provider=&lt;provider&gt; test</pre>
 *
 * Add {@code -Deval.analysis=true} to also run the article-analysis prompt on every case and measure bundle detection
 * ({@code multiEvent}) plus the end-to-end metrics with the predicted flags (one extra call per case).
 *
 * With {@code stub} it only proves the harness works (the stub is not a model). With a real provider the numbers are
 * the baseline to compare prompt versions and thresholds against. It asserts nothing about quality on purpose: the
 * acceptance thresholds are an OPEN ITEM that must be set from the first real-provider baseline.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "eval.run", matches = "true")
class EvalLlmIT {

    @Autowired PromptRegistry prompts;
    @Autowired StructuredOutputService structured;
    @Autowired AppProperties props;

    /** {@code -Deval.dataset=eval/issue-eval-v2.json} picks another fixed set; v1 by default. */
    private static String dataset() {
        return System.getProperty("eval.dataset", "eval/issue-eval-v1.json");
    }

    @Test
    void printsMetricsForTheFixedSet() throws Exception {
        var cases = EvalDatasetLoader.load(dataset());
        var judge = new LlmJudge(prompts, structured, props.prompts().issueClassifierVersion(), props.ai().classifierMaxAttempts());
        var thresholds = new DecisionPolicy.Thresholds(props.classifier().sameIssueMinConfidence(), props.classifier().newIssueMinConfidence());

        var results = new EvalRunner(judge, thresholds, props.classifier().candidateLimit()).run(cases);
        var report = EvalMetrics.compute(results);

        System.out.println("EVAL " + dataset() + " provider=" + props.ai().provider() + " prompt=" + props.prompts().issueClassifierVersion());
        System.out.println(EvalMetrics.format(report));
        results.forEach(r -> System.out.println("  " + r));
        assertThat(report.total()).isEqualTo(cases.size());
    }

    /** Bundle detection by the analysis model, then classification using those predictions instead of gold labels. */
    @Test
    @EnabledIfSystemProperty(named = "eval.analysis", matches = "true")
    void printsBundleDetectionAndEndToEndMetrics() throws Exception {
        var cases = EvalDatasetLoader.load(dataset());
        var detector = new LlmBundleDetector(prompts, structured, props.prompts().articleAnalysisVersion(),
                props.ai().analysisMaxAttempts(), props.ai().maxAnalysisTextChars());
        var predicted = detector.detect(cases);
        var bundles = MultiEventMetrics.compute(cases, predicted);

        var judge = new LlmJudge(prompts, structured, props.prompts().issueClassifierVersion(), props.ai().classifierMaxAttempts());
        var thresholds = new DecisionPolicy.Thresholds(props.classifier().sameIssueMinConfidence(), props.classifier().newIssueMinConfidence());
        var results = new EvalRunner(judge, thresholds, props.classifier().candidateLimit()).run(cases, predicted);

        System.out.println("EVAL " + dataset() + " (analysis + classification) provider=" + props.ai().provider()
                + " analysisPrompt=" + props.prompts().articleAnalysisVersion() + " classifierPrompt=" + props.prompts().issueClassifierVersion());
        System.out.println(MultiEventMetrics.format(bundles));
        System.out.println(EvalMetrics.format(EvalMetrics.compute(results)));
        results.forEach(r -> System.out.println("  " + r));
        assertThat(bundles.truePositive() + bundles.falsePositive() + bundles.falseNegative() + bundles.trueNegative()
                + bundles.failed()).isEqualTo(cases.size());
    }
}
