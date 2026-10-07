package com.aicontent.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Provider-neutral completion request. {@code variables} are the structured inputs the prompt was rendered from;
 * real providers ignore them (they only send system/user text), the deterministic stub reads them.
 */
public record AiRequest(
        AiCallType type,
        String system,
        String user,
        JsonNode jsonSchema,
        double temperature,
        Map<String, Object> variables) {}
