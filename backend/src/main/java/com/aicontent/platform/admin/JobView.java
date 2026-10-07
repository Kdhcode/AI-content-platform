package com.aicontent.platform.admin;

import com.aicontent.platform.job.AsyncJob;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** Admin-facing view of an async job (no worker internals such as lock owner). */
public record JobView(long id, String jobType, String status, JsonNode payload, JsonNode result, int retryCount,
                      int maxRetries, String errorCode, String errorMessage, String requestedBy,
                      OffsetDateTime requestedAt, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                      String correlationId) {

    public static JobView from(AsyncJob j) {
        return new JobView(j.id(), j.jobType(), j.status().name(), j.payload(), j.result(), j.retryCount(), j.maxRetries(),
                j.errorCode(), j.errorMessage(), j.requestedBy(), j.requestedAt(), j.startedAt(), j.finishedAt(),
                j.correlationId());
    }
}
