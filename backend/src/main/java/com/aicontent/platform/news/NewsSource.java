package com.aicontent.platform.news;

import java.time.OffsetDateTime;
import java.util.Map;

public record NewsSource(
        long id,
        String name,
        NewsSourceType type,
        String baseUrl,
        boolean enabled,
        String status,
        int collectionIntervalSeconds,
        Map<String, String> config,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime lastSuccessAt,
        int failureCount,
        String lastError) {

    public String configValue(String key, String defaultValue) {
        String v = config == null ? null : config.get(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }
}
