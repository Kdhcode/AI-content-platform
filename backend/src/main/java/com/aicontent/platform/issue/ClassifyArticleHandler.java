package com.aicontent.platform.issue;

import com.aicontent.platform.job.AsyncJob;
import com.aicontent.platform.job.JobContext;
import com.aicontent.platform.job.JobHandler;
import com.aicontent.platform.job.JobType;
import com.aicontent.platform.news.NewsArticleRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ClassifyArticleHandler implements JobHandler {

    private final IssueClassificationService service;
    private final NewsArticleRepository articles;

    public ClassifyArticleHandler(IssueClassificationService service, NewsArticleRepository articles) {
        this.service = service;
        this.articles = articles;
    }

    @Override
    public JobType type() {
        return JobType.CLASSIFY_ARTICLE;
    }

    @Override
    public Map<String, Object> handle(JobContext context) {
        return service.classify(context.requireLong("articleId"), context.job().id());
    }

    /** An article whose classification failed for good is marked so the admin list can filter it; links are untouched. */
    @Override
    public void onFinalFailure(AsyncJob job, String errorCode, String errorMessage) {
        try {
            var id = job.payload() == null ? null : job.payload().get("articleId");
            if (id != null && id.canConvertToLong()) {
                var row = articles.findRow(id.asLong());
                if (row.isPresent() && "NOT_CLASSIFIED".equals(row.get().classificationStatus())) {
                    articles.setClassificationStatus(id.asLong(), "FAILED");
                }
            }
        } catch (RuntimeException ignored) {
            // contract: must not throw
        }
    }
}
