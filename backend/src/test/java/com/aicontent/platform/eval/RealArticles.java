package com.aicontent.platform.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Real collected articles exported from a verification database to a LOCAL file (never committed: the texts belong
 * to their publishers). Export: {@code json_agg(json_build_object('id', a.id, 'source', s.name, 'publisher', ...,
 * 'title', ..., 'publishedAt', ..., 'sourceCategory', a.category, 'text', a.analysis_text, 'v1Category',
 * a.analysis_json::jsonb->>'category'))} — see docs/AI_QUALITY_20261014.md.
 */
record RealArticles(long id, String source, String publisher, String title, String publishedAt, String sourceCategory,
                    String text, String v1Category) {

    static List<RealArticles> load(Path file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        List<RealArticles> out = new ArrayList<>();
        for (JsonNode n : root) {
            out.add(new RealArticles(n.path("id").asLong(), n.path("source").asText(), n.path("publisher").asText(),
                    n.path("title").asText(), n.path("publishedAt").isNull() ? null : n.path("publishedAt").asText(),
                    n.path("sourceCategory").isNull() ? null : n.path("sourceCategory").asText(), n.path("text").asText(""),
                    n.path("v1Category").isNull() ? null : n.path("v1Category").asText()));
        }
        return out;
    }
}
