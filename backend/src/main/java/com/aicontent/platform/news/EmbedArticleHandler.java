package com.aicontent.platform.news;

import com.aicontent.platform.ai.AiCallContext;
import com.aicontent.platform.ai.AiException;
import com.aicontent.platform.ai.EmbeddingResult;
import com.aicontent.platform.ai.EmbeddingService;
import com.aicontent.platform.ai.EmbeddingTextBuilder;
import com.aicontent.platform.common.Json;
import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.job.JobContext;
import com.aicontent.platform.job.JobExecutionException;
import com.aicontent.platform.job.JobHandler;
import com.aicontent.platform.job.JobType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** EMBED_ARTICLE: embeds title+summary+keyFacts+entities, stores the vector, then enqueues CLASSIFY_ARTICLE. */
@Component
public class EmbedArticleHandler implements JobHandler {

    private final NewsArticleRepository articles;
    private final EmbeddingService embeddings;
    private final AsyncJobService jobs;
    private final Json json;

    public EmbedArticleHandler(NewsArticleRepository articles, EmbeddingService embeddings, AsyncJobService jobs, Json json) {
        this.articles = articles;
        this.embeddings = embeddings;
        this.jobs = jobs;
        this.json = json;
    }

    @Override
    public JobType type() {
        return JobType.EMBED_ARTICLE;
    }

    @Override
    public Map<String, Object> handle(JobContext context) {
        long articleId = context.requireLong("articleId");
        var article = articles.findRow(articleId).orElseThrow(() ->
                new JobExecutionException("ARTICLE_NOT_FOUND", "article " + articleId + " not found", false));
        if ("DUPLICATE".equals(article.status())) {
            return Map.of("skipped", "duplicate article");
        }
        if (article.analysisJson() == null) {
            throw new JobExecutionException("ARTICLE_NOT_ANALYZED", "article " + articleId + " has no analysis yet", false);
        }
        JsonNode analysis = json.parse(article.analysisJson());
        List<String> facts = new ArrayList<>();
        analysis.path("keyFacts").forEach(n -> facts.add(n.asText()));
        List<String> entities = new ArrayList<>();
        analysis.path("entities").forEach(n -> entities.add(n.path("name").asText()));
        String text = EmbeddingTextBuilder.build(article.title(), analysis.path("summary").asText(null), facts, entities);

        EmbeddingResult result;
        try {
            result = embeddings.embed(text, AiCallContext.of(context.job().id(), articleId));
        } catch (AiException e) {
            throw new JobExecutionException(e.code(), e.getMessage(), e.retryable(), e);
        }
        articles.saveEmbedding(articleId, result.vector(), result.model());
        jobs.enqueue(JobType.CLASSIFY_ARTICLE, Map.of("articleId", articleId), AsyncJobService.SYSTEM,
                "classify:" + articleId, context.correlationId());
        return Map.of("articleId", articleId, "model", String.valueOf(result.model()));
    }

    @Override
    public void onFinalFailure(com.aicontent.platform.job.AsyncJob job, String errorCode, String errorMessage) {
        // The analysis stays valid; the admin sees the FAILED job and can retry it. Article status is untouched.
    }
}
