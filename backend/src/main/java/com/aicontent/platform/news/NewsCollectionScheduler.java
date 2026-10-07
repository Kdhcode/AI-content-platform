package com.aicontent.platform.news;

import com.aicontent.platform.config.AppProperties;
import com.aicontent.platform.job.AsyncJobService;
import com.aicontent.platform.job.JobType;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enqueues COLLECT_NEWS for every due source. It only enqueues; the work happens in the job worker. The dedupe key
 * {@code collect:<sourceId>} keeps at most one active collect job per source, so a slow source never piles up jobs.
 */
@Component
public class NewsCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectionScheduler.class);

    private final NewsSourceRepository sources;
    private final AsyncJobService jobs;
    private final AppProperties props;

    public NewsCollectionScheduler(NewsSourceRepository sources, AsyncJobService jobs, AppProperties props) {
        this.sources = sources;
        this.jobs = jobs;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.news.scheduler-interval-ms:30000}", initialDelayString = "${app.news.scheduler-interval-ms:30000}")
    public void tick() {
        if (!props.news().schedulerEnabled()) {
            return;
        }
        try {
            enqueueDue();
        } catch (RuntimeException e) {
            log.warn("collection scheduler tick failed: {}", e.getMessage());
        }
    }

    /** @return number of newly created jobs (public for tests and an admin "collect now" later). */
    public int enqueueDue() {
        int created = 0;
        for (long id : sources.findDueIds()) {
            var r = jobs.enqueue(JobType.COLLECT_NEWS, Map.of("sourceId", id), AsyncJobService.SYSTEM, "collect:" + id, null);
            if (r.created()) {
                created++;
            }
        }
        return created;
    }
}
