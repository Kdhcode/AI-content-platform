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

    @Test
    void printsMetricsForTheFixedSet() throws Exception {
        var cases = EvalDatasetLoader.load("eval/issue-eval-v1.json");
        var judge = new LlmJudge(prompts, structured, props.prompts().issueClassifierVersion(), props.ai().classifierMaxAttempts());
        var thresholds = new DecisionPolicy.Thresholds(props.classifier().sameIssueMinConfidence(), props.classifier().newIssueMinConfidence());

        var results = new EvalRunner(judge, thresholds, props.classifier().candidateLimit()).run(cases);
        var report = EvalMetrics.compute(results);

        System.out.println("EVAL issue-eval-v1 provider=" + props.ai().provider() + " prompt=" + props.prompts().issueClassifierVersion());
        System.out.println(EvalMetrics.format(report));
        results.forEach(r -> System.out.println("  " + r));
        assertThat(report.total()).isEqualTo(cases.size());
    }
}
