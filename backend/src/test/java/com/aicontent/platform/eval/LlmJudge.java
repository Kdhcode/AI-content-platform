package com.aicontent.platform.eval;

import com.aicontent.platform.ai.AiCallContext;
import com.aicontent.platform.ai.AiCallType;
import com.aicontent.platform.ai.AiOutputInvalidException;
import com.aicontent.platform.ai.PromptRegistry;
import com.aicontent.platform.ai.StructuredOutputService;
import com.aicontent.platform.issue.ClassifierPromptVariables;
import com.aicontent.platform.issue.DecisionPolicy.Decision;
import com.aicontent.platform.issue.DecisionPolicy.RawDecision;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Production prompt + production output validation, fed from the evaluation set instead of the database. */
public class LlmJudge implements Judge {

    private final PromptRegistry prompts;
    private final StructuredOutputService structured;
    private final String promptVersion;
    private final int maxAttempts;

    public LlmJudge(PromptRegistry prompts, StructuredOutputService structured, String promptVersion, int maxAttempts) {
        this.prompts = prompts;
        this.structured = structured;
        this.promptVersion = promptVersion;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public RawDecision judge(EvalCase a, List<CandidateIssue> candidates) {
        var input = new ClassifierPromptVariables.ArticleInput(a.id().hashCode(), a.title(), a.publisher(), a.publishedAt(),
                a.summary(), a.keyFacts(), a.entities(), "-");
        var cands = candidates.stream().map(c -> new ClassifierPromptVariables.CandidateInput(c.issueId(), c.title(), c.summary(),
                c.keyFacts(), "-", "-", 1, 1)).toList();
        try {
            var result = structured.execute(new StructuredOutputService.Call(AiCallType.ISSUE_CLASSIFY,
                    prompts.get("issue-classifier", promptVersion), ClassifierPromptVariables.build(input, cands), maxAttempts,
                    AiCallContext.of(null, null)));
            JsonNode v = result.value();
            return new RawDecision(Decision.valueOf(v.get("decision").asText()), v.get("confidence").asDouble(),
                    v.hasNonNull("matchedIssueId") ? v.get("matchedIssueId").asLong() : null, v.get("reason").asText());
        } catch (AiOutputInvalidException e) {
            // an unusable answer is a REVIEW with zero confidence, the same outcome production reaches
            return new RawDecision(Decision.REVIEW, 0.0, null, "invalid LLM output: " + String.join("; ", e.errors()));
        }
    }
}
