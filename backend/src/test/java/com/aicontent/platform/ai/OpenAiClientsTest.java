package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiClientsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private OpenAiProperties props;
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private String response;
    private int status;

    @BeforeEach void start() throws Exception {
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        props = new OpenAiProperties("test-secret", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-chat", "text-embedding-3-small", 5, 1000);
    }

    @AfterEach void stop() { server.stop(0); }

    private AiRequest request() throws Exception {
        return new AiRequest(AiCallType.ARTICLE_ANALYSIS, "Analyze article", "Article text",
                mapper.readTree("{\"type\":\"object\"}"), 0.1, Map.of());
    }

    @Test void sendsJsonModeAndFullSchemaAndReadsUsage() throws Exception {
        // Build the content with Jackson to preserve JSON escaping in the wire response.
        var payload = mapper.createObjectNode();
        payload.put("model", "returned-model");
        payload.putArray("choices").addObject().put("finish_reason", "stop")
                .putObject("message").put("content", "{\"summary\":\"ok\"}");
        payload.putObject("usage").put("prompt_tokens", 12).put("completion_tokens", 7);
        response = payload.toString();
        var result = new OpenAiClient(props, mapper).complete(request());
        assertEquals("{\"summary\":\"ok\"}", result.text());
        assertEquals("returned-model", result.model());
        assertEquals(12, result.inputTokens());
        assertEquals(7, result.outputTokens());
        assertEquals("Bearer test-secret", authorization.get());
        assertEquals("json_object", received.get().path("response_format").path("type").asText());
        assertTrue(received.get().path("messages").path(0).path("content").asText().contains("JSON Schema"));
    }

    @Test void reads1536DimensionalEmbedding() {
        var payload = mapper.createObjectNode();
        payload.put("model", "embedding-model");
        var vector = payload.putArray("data").addObject().putArray("embedding");
        for (int i = 0; i < 1536; i++) vector.add(i == 0 ? 1.0 : 0.0);
        payload.putObject("usage").put("total_tokens", 11);
        response = payload.toString();
        var result = new OpenAiEmbeddingClient(props, mapper, 1536).embed("article");
        assertEquals(1536, result.vector().length);
        assertEquals(1f, result.vector()[0]);
        assertEquals(11, result.inputTokens());
        assertEquals(1536, received.get().path("dimensions").asInt());
        assertEquals("float", received.get().path("encoding_format").asText());
    }

    @Test void retriesRateLimitsAndServerErrorsButNotAuthFailuresAndRedactsBody() throws Exception {
        response = "{\"error\":\"test-secret provider diagnostics\"}";
        for (int code : new int[]{401, 429, 503}) {
            status = code;
            var error = assertThrows(AiException.class, () -> new OpenAiClient(props, mapper).complete(request()));
            assertEquals("OPENAI_HTTP_" + code, error.code());
            assertEquals(code != 401, error.retryable());
            assertFalse(error.getMessage().contains("test-secret"));
        }
    }

    @Test void rejectsTruncatedRefusedAndMalformedResponses() {
        for (String body : new String[]{"not JSON", "null", "{}",
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"declined\"}}]}"}) {
            response = body;
            assertThrows(AiException.class, () -> new OpenAiClient(props, mapper).complete(request()));
        }
    }

    @Test void rejectsWrongEmbeddingDimensionAndNonNumericCoordinates() {
        response = "{\"data\":[{\"embedding\":[1,2]}]}";
        assertThrows(AiException.class, () -> new OpenAiEmbeddingClient(props, mapper, 1536).embed("article"));
        var payload = mapper.createObjectNode();
        var vector = payload.putArray("data").addObject().putArray("embedding");
        for (int i = 0; i < 1536; i++) vector.add("bad");
        response = payload.toString();
        assertThrows(AiException.class, () -> new OpenAiEmbeddingClient(props, mapper, 1536).embed("article"));
    }

    @Test void failsAtStartupWithoutKeyAndRejectsInsecureRemoteUrl() {
        var emptyKey = new OpenAiProperties("", props.baseUrl(), "chat", "embed", 5, 1000);
        assertThrows(IllegalStateException.class, () -> new OpenAiClient(emptyKey, mapper));
        var remote = new OpenAiProperties("secret", "http://example.test/v1", "chat", "embed", 5, 1000);
        assertThrows(IllegalStateException.class, () -> new OpenAiClient(remote, mapper));
        assertThrows(IllegalStateException.class, () -> new OpenAiEmbeddingClient(props, mapper, 768));
    }
}
