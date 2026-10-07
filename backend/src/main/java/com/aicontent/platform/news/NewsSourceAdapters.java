package com.aicontent.platform.news;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Lookup of adapters by source type. */
@Component
public class NewsSourceAdapters {

    private final Map<NewsSourceType, NewsSourceAdapter> byType = new EnumMap<>(NewsSourceType.class);

    public NewsSourceAdapters(List<NewsSourceAdapter> adapters) {
        for (NewsSourceAdapter a : adapters) {
            byType.put(a.type(), a);
        }
    }

    public Optional<NewsSourceAdapter> find(NewsSourceType type) {
        return Optional.ofNullable(byType.get(type));
    }
}
