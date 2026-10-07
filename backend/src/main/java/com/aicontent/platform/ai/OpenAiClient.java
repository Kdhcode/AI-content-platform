package com.aicontent.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class OpenAiClient implements AiClient {
    private final OpenAiProperties props;
    private final ObjectMapper mapper;
    private final OpenAiTransport transport;

    public OpenAiClient(OpenAiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.transport = new OpenAiTransport(props, mapper);
    }

    @Override public String provider() { return "openai"; }

    @Override public AiCompletion complete(AiRequest request) {
        var body = mapper.createObjectNode();
        body.put("model", props.chatModel());
        body.put("temperature", request.temperature());
        body.put("max_completion_tokens", props.maxCompletionTokens());
        body.putObject("response_format").put("type", "json_object");
        // Existing schemas include conditional allOf/if/then and optional fields, which strict OpenAI schemas
        // do not support. JSON mode plus the existing local validator preserves the full domain contract.
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system()
                + "\nReturn one JSON object satisfying this JSON Schema:\n" + request.jsonSchema());
        messages.addObject().put("role", "user").put("content", request.user());
        var response = transport.post("/chat/completions", body);
        var choice = response.path("choices").path(0);
        if (!choice.path("message").path("refusal").isMissingNode()
                && !choice.path("message").path("refusal").isNull()) {
            throw new AiException("OPENAI_REFUSAL", "OpenAI declined this request", false);
        }
        if (!"stop".equals(choice.path("finish_reason").asText())) {
            throw OpenAiTransport.invalid("completion did not finish normally");
        }
        var content = choice.path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw OpenAiTransport.invalid("completion content is missing");
        }
        return new AiCompletion(content.asText(), response.path("model").asText(props.chatModel()),
                OpenAiTransport.tokens(response.path("usage"), "prompt_tokens"),
                OpenAiTransport.tokens(response.path("usage"), "completion_tokens"));
    }
}
