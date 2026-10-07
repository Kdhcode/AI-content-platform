package com.aicontent.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/** Provider failures contain no response body or API key, since they are persisted in job/AI logs. */
final class OpenAiTransport {
    private final OpenAiProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;

    OpenAiTransport(OpenAiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("AI_PROVIDER=openai requires OPENAI_API_KEY");
        }
        if (props.timeoutSeconds() < 1 || props.maxCompletionTokens() < 1) {
            throw new IllegalStateException("OpenAI timeout and token limit must be positive");
        }
        URI base = URI.create(props.baseUrl());
        boolean local = "http".equals(base.getScheme()) &&
                ("localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost()) || "[::1]".equals(base.getHost()));
        if (base.getHost() == null || (!"https".equals(base.getScheme()) && !local)
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalStateException("OpenAI base URL must use HTTPS (HTTP allowed only on loopback)");
        }
        baseUrl = props.baseUrl().replaceAll("/+$", "");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(props.timeoutSeconds())).build();
    }

    JsonNode post(String path, JsonNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                    .header("Authorization", "Bearer " + props.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
                throw new AiException("OPENAI_HTTP_" + status, "OpenAI request failed (HTTP " + status + ")", retryable);
            }
            JsonNode result = mapper.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw invalid("expected a JSON response object");
            }
            return result;
        } catch (HttpTimeoutException e) {
            throw new AiException("OPENAI_TIMEOUT", "OpenAI request timed out", true);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid("invalid response JSON");
        } catch (IOException e) {
            throw new AiException("OPENAI_NETWORK", "OpenAI network request failed", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("OPENAI_INTERRUPTED", "OpenAI request interrupted", true);
        }
    }

    static AiException invalid(String detail) {
        return new AiException("OPENAI_RESPONSE_INVALID", "OpenAI: " + detail, false);
    }

    static Integer tokens(JsonNode usage, String field) {
        JsonNode n = usage.path(field);
        return n.canConvertToInt() ? n.intValue() : null;
    }
}
