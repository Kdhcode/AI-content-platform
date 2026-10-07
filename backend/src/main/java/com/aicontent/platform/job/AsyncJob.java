package com.aicontent.platform.job;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record AsyncJob(
        long id,
        String jobType,
        AsyncJobStatus status,
        JsonNode payload,
        JsonNode result,
        String requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime availableAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime heartbeatAt,
        String lockedBy,
        int retryCount,
        int maxRetries,
        String errorCode,
        String errorMessage,
        String correlationId,
        String dedupeKey,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
