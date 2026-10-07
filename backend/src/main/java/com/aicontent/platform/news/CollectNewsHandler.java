package com.aicontent.platform.news;

import com.aicontent.platform.job.JobContext;
import com.aicontent.platform.job.JobHandler;
import com.aicontent.platform.job.JobType;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CollectNewsHandler implements JobHandler {

    private final NewsCollectionService service;

    public CollectNewsHandler(NewsCollectionService service) {
        this.service = service;
    }

    @Override
    public JobType type() {
        return JobType.COLLECT_NEWS;
    }

    @Override
    public Map<String, Object> handle(JobContext context) {
        return service.collect(context.requireLong("sourceId"), context.correlationId());
    }
}
