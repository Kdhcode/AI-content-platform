package com.aicontent.platform.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

/** Thin unchecked wrapper around the shared ObjectMapper, used for JSONB columns. */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    public JsonNode parse(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON parse failed", e);
        }
    }

    /** Null / blank -> null; otherwise parsed node. */
    public JsonNode parseOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return parse(text);
    }

    public JsonNode emptyObject() {
        return JsonNodeFactory.instance.objectNode();
    }

    public JsonNode emptyArray() {
        return JsonNodeFactory.instance.arrayNode();
    }

    public JsonNode valueToTree(Object value) {
        return mapper.valueToTree(value);
    }

    public <T> T treeToValue(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON mapping failed", e);
        }
    }
}
