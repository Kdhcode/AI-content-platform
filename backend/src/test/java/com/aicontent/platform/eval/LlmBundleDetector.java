package com.aicontent.platform.eval;

import com.aicontent.platform.ai.AiCallContext;
import com.aicontent.platform.ai.AiCallType;
import com.aicontent.platform.ai.AiOutputInvalidException;
import com.aicontent.platform.ai.PromptRegistry;
import com.aicontent.platform.ai.StructuredOutputService;
import com.aicontent.platform.news.ArticleAnalysisService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the production article-analysis prompt on each evaluation case and returns the model's {@code multiEvent}.
 * The set has no article bodies, so the text is the summary followed by the key facts (one per line). That is less
 * than a real article gives the model, so a weak result here is a lower bound, not a verdict.
 */
public class LlmBundleDetector {

    private final PromptRegistry prompts;
    private final StructuredOutputService structured;
    private final String promptVersion;
    private final int maxAttempts;
    private final int maxTextChars;

    public LlmBundleDetector(PromptRegistry prompts, StructuredOutputService structured, String promptVersion,
                             int maxAttempts, int maxTextChars) {
        this.prompts = prompts;
        this.structured = structured;
        this.promptVersion = promptVersion;
        this.maxAttempts = maxAttempts;
        this.maxTextChars = maxTextChars;
    }

    /** @return case id -> predicted multiEvent; cases whose analysis failed validation are absent. */
    public Map<String, Boolean> detect(List<EvalCase> cases) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        var prompt = prompts.get(ArticleAnalysisService.PROMPT_KEY, promptVersion);
        for (EvalCase c : cases) {
            var vars = ArticleAnalysisService.promptVariables(c.title(), c.publisher(), c.publishedAt(), null,
                    text(c), maxTextChars);
            try {
                var result = structured.execute(new StructuredOutputService.Call(AiCallType.ARTICLE_ANALYSIS, prompt, vars,
                        maxAttempts, AiCallContext.of(null, null)));
                out.put(c.id(), result.value().path("multiEvent").asBoolean(false));
            } catch (AiOutputInvalidException e) {
                // production marks such an article FAILED; here it is reported as "analysisFailed"
            }
        }
        return out;
    }

    static String text(EvalCase c) {
        return c.summary() + (c.keyFacts().isEmpty() ? "" : "\n" + String.join("\n", c.keyFacts()));
    }
}
