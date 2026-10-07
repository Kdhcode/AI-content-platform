package com.aicontent.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** One versioned prompt: system text, user template, output schema, metadata. Immutable; loaded from /prompts. */
public record PromptTemplate(
        String key,
        String version,
        String schemaVersion,
        double temperature,
        String system,
        String userTemplate,
        JsonNode schema) {

    public String renderUser(Map<String, ?> variables) {
        return TemplateRenderer.render(userTemplate, variables);
    }
}
