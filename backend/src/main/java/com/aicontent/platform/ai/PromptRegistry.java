package com.aicontent.platform.ai;

import com.aicontent.platform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads {@code prompts/<key>/<version>/{system.md,user.md,schema.json,meta.json}} from the classpath. A prompt
 * change is a new version directory, never an edit of a released one, so ai_log/ai_job can always point at the
 * exact text that produced a stored result.
 */
@Component
public class PromptRegistry {

    private final Json json;
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public PromptRegistry(Json json) {
        this.json = json;
    }

    public PromptTemplate get(String key, String version) {
        return cache.computeIfAbsent(key + "/" + version, k -> load(key, version));
    }

    private PromptTemplate load(String key, String version) {
        String base = "prompts/" + key + "/" + version + "/";
        try {
            JsonNode meta = json.parse(read(base + "meta.json"));
            return new PromptTemplate(
                    key, version,
                    meta.path("schemaVersion").asText(key + "." + version),
                    meta.path("temperature").asDouble(0.0),
                    read(base + "system.md").trim(),
                    read(base + "user.md").trim(),
                    json.parse(read(base + "schema.json")));
        } catch (IOException e) {
            throw new IllegalStateException("prompt " + key + "/" + version + " cannot be loaded: " + e.getMessage(), e);
        }
    }

    private static String read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
