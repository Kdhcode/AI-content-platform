package com.aicontent.platform.news;

import com.aicontent.platform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads articles from a local JSON file (array of {title,url,publisher,author,category,publishedAt,text}).
 * {@code news_source.base_url} is the file path. For development, demos and tests only; the data is whatever the
 * developer puts in the file - this project ships no invented news.
 */
public class FixtureNewsSourceAdapter implements NewsSourceAdapter {

    private final Json json;

    public FixtureNewsSourceAdapter(Json json) {
        this.json = json;
    }

    @Override
    public NewsSourceType type() {
        return NewsSourceType.FIXTURE;
    }

    @Override
    public List<CollectedArticle> fetchLatest(NewsSource source, int limit) throws SourceFetchException {
        if (source.baseUrl() == null || source.baseUrl().isBlank()) {
            throw new SourceFetchException(SourceFetchException.Kind.UNSUPPORTED, "fixture source has no base_url (file path)");
        }
        String text;
        try {
            text = Files.readString(Path.of(source.baseUrl()));
        } catch (IOException e) {
            throw new SourceFetchException(SourceFetchException.Kind.CLIENT_ERROR, "cannot read fixture file " + source.baseUrl(), e);
        }
        JsonNode root;
        try {
            root = json.mapper().readTree(text);
        } catch (IOException e) {
            throw new SourceFetchException(SourceFetchException.Kind.PARSE_ERROR, "fixture file is not valid JSON", e);
        }
        if (root == null || !root.isArray()) {
            throw new SourceFetchException(SourceFetchException.Kind.PARSE_ERROR, "fixture file must contain a JSON array");
        }
        String defaultPublisher = source.configValue("publisher", source.name());
        List<CollectedArticle> out = new ArrayList<>();
        for (JsonNode n : root) {
            if (out.size() >= limit) {
                break;
            }
            out.add(new CollectedArticle(
                    str(n, "title"), str(n, "url"),
                    str(n, "publisher") != null ? str(n, "publisher") : defaultPublisher,
                    str(n, "author"), str(n, "category"), str(n, "publishedAt"), str(n, "text")));
        }
        return out;
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
