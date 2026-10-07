package com.aicontent.platform.news;

import com.aicontent.platform.job.AsyncJob;
import com.aicontent.platform.job.JobContext;
import com.aicontent.platform.job.JobHandler;
import com.aicontent.platform.job.JobType;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AnalyzeArticleHandler implements JobHandler {

    private final ArticleAnalysisService service;
    private final NewsArticleRepository articles;

    public AnalyzeArticleHandler(ArticleAnalysisService service, NewsArticleRepository articles) {
        this.service = service;
        this.articles = articles;
    }

    @Override
    public JobType type() {
        return JobType.ANALYZE_ARTICLE;
    }

    @Override
    public Map<String, Object> handle(JobContext context) {
        return service.analyze(context.requireLong("articleId"), context.job().id(), context.correlationId());
    }

    @Override
    public void onFinalFailure(AsyncJob job, String errorCode, String errorMessage) {
        try {
            var id = job.payload() == null ? null : job.payload().get("articleId");
            if (id != null && id.canConvertToLong()) {
                articles.markAnalysisFailed(id.asLong(), errorCode + ": " + errorMessage);
            }
        } catch (RuntimeException ignored) {
            // contract: must not throw
        }
    }
}
