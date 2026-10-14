package com.aicontent.platform.eval;

import com.aicontent.platform.ai.AiCallContext;
import com.aicontent.platform.ai.AiCallType;
import com.aicontent.platform.ai.AiOutputInvalidException;
import com.aicontent.platform.ai.EmbeddingService;
import com.aicontent.platform.ai.PromptRegistry;
import com.aicontent.platform.ai.StructuredOutputService;
import com.aicontent.platform.config.AppProperties;
import com.aicontent.platform.news.ArticleAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Re-analyses selected real articles with the configured article-analysis prompt (production variables, including
 * input cleaning) and compares the category with the expected values written before the run and with the stored v1
 * category. Paid when the provider is real. Enabled with {@code -Dcompare.run=true -Darticles.json=<file>
 * -Dcompare.cases=<file> -Dcompare.out=<file>}; {@code -Dcompare.embed=N} also embeds the first N cases so the
 * embedding cost path is exercised.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "compare.run", matches = "true")
class AnalysisCompareIT {

    @Autowired PromptRegistry prompts;
    @Autowired StructuredOutputService structured;
    @Autowired EmbeddingService embeddings;
    @Autowired AppProperties props;

    @Test
    void comparesCategoriesOnRealArticles() throws Exception {
        Map<Long, RealArticles> articles = RealArticles.load(Path.of(System.getProperty("articles.json"))).stream()
                .collect(Collectors.toMap(RealArticles::id, Function.identity()));
        JsonNode cases = new ObjectMapper().readTree(Path.of(System.getProperty("compare.cases")).toFile()).path("cases");
        int embed = Integer.getInteger("compare.embed", 0);
        var prompt = prompts.get(ArticleAnalysisService.PROMPT_KEY, props.prompts().articleAnalysisVersion());
        List<String> out = new ArrayList<>();
        out.add("prompt=" + prompt.key() + "/" + prompt.version() + " provider=" + props.ai().provider());
        int ok = 0, n = 0;
        for (JsonNode c : cases) {
            RealArticles a = articles.get(c.path("id").asLong());
            var vars = ArticleAnalysisService.promptVariables(a.title(), a.publisher(), a.publishedAt(), a.sourceCategory(),
                    a.text(), props.ai().maxAnalysisTextChars());
            String category;
            try {
                var result = structured.execute(new StructuredOutputService.Call(AiCallType.ARTICLE_ANALYSIS, prompt, vars,
                        props.ai().analysisMaxAttempts(), AiCallContext.of(null, null)));
                category = result.value().path("category").asText();
            } catch (AiOutputInvalidException e) {
                category = "INVALID(" + String.join("; ", e.errors()) + ")";
            }
            List<String> expected = new ArrayList<>();
            c.path("expected").forEach(e -> expected.add(e.asText()));
            boolean pass = expected.contains(category);
            ok += pass ? 1 : 0;
            n++;
            out.add(String.join("\t", String.valueOf(a.id()), c.path("group").asText(), a.source(), "v1=" + a.v1Category(),
                    "v2=" + category, "expected=" + expected, pass ? "OK" : "MISS", a.title()));
            if (n <= embed) {
                var e = embeddings.embed(EmbeddingTextBuilderProbe.of(a), AiCallContext.of(null, null));
                out.add("\tembedding model=" + e.model() + " tokens=" + e.inputTokens());
            }
        }
        out.add("matched " + ok + "/" + n);
        Files.write(Path.of(System.getProperty("compare.out", "compare-out.txt")), out);
    }

    /** Embedding input for the cost check only: title plus the start of the text (not the production builder). */
    static final class EmbeddingTextBuilderProbe {
        static String of(RealArticles a) {
            String t = a.title() + "\n" + a.text();
            return t.length() > 1000 ? t.substring(0, 1000) : t;
        }
    }
}
