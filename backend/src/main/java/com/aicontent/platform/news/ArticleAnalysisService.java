package com.aicontent.platform.news;

import com.aicontent.platform.ai.AiCallContext;
import com.aicontent.platform.ai.AiCallType;
import com.aicontent.platform.ai.AiException;
import com.aicontent.platform.ai.AiOutputInvalidException;
import com.aicontent.platform.ai.PromptRegistry;
import com.aicontent.platform.ai.PromptTemplate;
import com.aicontent.platform.ai.StructuredOutputService;
import com.aicontent.platform.common.Json;
import com.aicontent.platform.config.AppProperties;
import com.aicontent.platform.job.JobExecutionException;
import com.aicontent.platform.job.JobType;
import com.aicontent.platform.job.AsyncJobService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** ANALYZE_ARTICLE: LLM analysis -> schema-validated JSON stored on the article -> EMBED_ARTICLE enqueued. */
@Service
public class ArticleAnalysisService {

    private static final String PROMPT_KEY = "article-analysis";

    private final NewsArticleRepository articles;
    private final PromptRegistry prompts;
    private final StructuredOutputService structured;
    private final AsyncJobService jobs;
    private final Json json;
    private final AppProperties props;

    public ArticleAnalysisService(NewsArticleRepository articles, PromptRegistry prompts, StructuredOutputService structured,
                                  AsyncJobService jobs, Json json, AppProperties props) {
        this.articles = articles;
        this.prompts = prompts;
        this.structured = structured;
        this.jobs = jobs;
        this.json = json;
        this.props = props;
    }

    public Map<String, Object> analyze(long articleId, long asyncJobId, String correlationId) {
        var article = articles.findRow(articleId).orElseThrow(() ->
                new JobExecutionException("ARTICLE_NOT_FOUND", "article " + articleId + " not found", false));
        if ("DUPLICATE".equals(article.status())) {
            return Map.of("skipped", "duplicate article is not analysed");
        }
        PromptTemplate prompt = prompts.get(PROMPT_KEY, props.prompts().articleAnalysisVersion());

        String text = article.analysisText() == null ? "" : article.analysisText().trim();
        int max = props.ai().maxAnalysisTextChars();
        if (text.length() > max) {
            text = text.substring(0, max);
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", article.title());
        vars.put("publisher", article.publisherName());
        vars.put("publishedAt", article.publishedAt() == null ? "알 수 없음" : article.publishedAt().toString());
        vars.put("category", article.category() == null ? "없음" : article.category());
        vars.put("text", text.isEmpty() ? "(본문 없음 - 제목만 사용)" : text);

        try {
            var result = structured.execute(new StructuredOutputService.Call(AiCallType.ARTICLE_ANALYSIS, prompt, vars,
                    props.ai().analysisMaxAttempts(), AiCallContext.of(asyncJobId, articleId)));
            articles.saveAnalysis(articleId, json.write(result.value()), prompt.version());
        } catch (AiException e) {
            throw new JobExecutionException(e.code(), e.getMessage(), e.retryable(), e);
        } catch (AiOutputInvalidException e) {
            // already retried inside StructuredOutputService; a further job retry would repeat the same prompt
            throw new JobExecutionException("AI_OUTPUT_INVALID", e.getMessage() + ": " + String.join("; ", e.errors()), false, e);
        }
        jobs.enqueue(JobType.EMBED_ARTICLE, Map.of("articleId", articleId), AsyncJobService.SYSTEM,
                "embed:" + articleId, correlationId);
        return Map.of("articleId", articleId, "promptVersion", prompt.version());
    }
}
