package com.aicontent.platform.ai;

import com.aicontent.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Clears raw model output ({@code ai_log.response}) once it is older than {@code app.retention.ai-log-response-days}
 * (OPEN ITEM #8). Off by default (0 = keep forever): the period is a policy decision, not a technical one.
 *
 * <p>Only the text is removed. The row with tokens, cost, model and success flag stays, so the AI usage screen and
 * cost history do not change. Each statement clears one batch in its own short transaction; a run stops after
 * {@value #MAX_BATCHES_PER_RUN} batches and the next run continues.
 */
@Service
public class AiLogRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AiLogRetentionService.class);
    static final int MAX_BATCHES_PER_RUN = 100;

    private final AiLogRepository logs;
    private final AppProperties.Retention config;

    @Autowired
    public AiLogRetentionService(AiLogRepository logs, AppProperties props) {
        this(logs, props.retention());
    }

    AiLogRetentionService(AiLogRepository logs, AppProperties.Retention config) {
        this.logs = logs;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void describe() {
        if (config.aiLogResponseDays() > 0) {
            log.info("ai_log.response retention: {} days", config.aiLogResponseDays());
        }
    }

    @Scheduled(fixedDelayString = "${app.retention.interval-ms:3600000}",
            initialDelayString = "${app.retention.initial-delay-ms:120000}")
    public void tick() {
        try {
            clearExpiredResponses();
        } catch (RuntimeException e) {
            log.warn("ai_log retention run failed: {}", e.getMessage());
        }
    }

    /** @return number of rows whose response was cleared in this run (0 when retention is off). */
    public int clearExpiredResponses() {
        int days = config.aiLogResponseDays();
        if (days <= 0) {
            return 0;
        }
        int batch = Math.max(1, config.batchSize());
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int cleared = logs.clearResponsesOlderThan(days, batch);
            total += cleared;
            if (cleared < batch) {
                break;
            }
        }
        if (total > 0) {
            log.info("ai_log retention: cleared response text of {} row(s) older than {} days", total, days);
        }
        return total;
    }
}
