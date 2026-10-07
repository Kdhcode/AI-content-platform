package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicontent.platform.news.NewsSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Real provider HTTP adapters and pgvector pipeline against a local API contract server; no paid API calls. */
@AutoConfigureMockMvc
class OpenAiFlowIntegrationTest extends IntegrationTestBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger analysisCalls = new AtomicInteger();
    private static final AtomicInteger embeddingCalls = new AtomicInteger();
    private static final AtomicInteger classifierCalls = new AtomicInteger();
    private static HttpServer api;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;

    @DynamicPropertySource static void provider(DynamicPropertyRegistry registry) throws IOException {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            String response;
            String type = "application/json";
            if (exchange.getRequestURI().getPath().equals("/rss")) {
                response = """
                        <rss version="2.0"><channel><title>Local news</title>
                        <item><title>가상시 상가 화재 주민 대피</title><link>https://example.test/openai/1</link>
                        <description>상가 화재로 주민이 대피했고 소방대가 출동했습니다.</description></item>
                        <item><title>가상시 상가 화재 소방대 출동</title><link>https://example.test/openai/2</link>
                        <description>소방대가 화재를 진압하고 있습니다.</description></item>
                        </channel></rss>""";
                type = "application/rss+xml";
            } else {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer contract-test-key");
                var request = JSON.readTree(exchange.getRequestBody());
                var result = JSON.createObjectNode();
                if (exchange.getRequestURI().getPath().equals("/v1/embeddings")) {
                    embeddingCalls.incrementAndGet();
                    assertThat(request.path("dimensions").asInt()).isEqualTo(1536);
                    result.put("model", "contract-embedding");
                    var vector = result.putArray("data").addObject().putArray("embedding");
                    for (int i = 0; i < 1536; i++) vector.add(i == 0 ? 1 : 0);
                    result.putObject("usage").put("total_tokens", 20);
                } else {
                    assertThat(exchange.getRequestURI().getPath()).isEqualTo("/v1/chat/completions");
                    assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
                    String content;
                    if (request.path("messages").path(0).path("content").asText().contains("issue-classifier.v1")) {
                        classifierCalls.incrementAndGet();
                        content = """
                                {"decision":"REVIEW","confidence":0.5,"matchedIssueId":null,"reason":"동일 사건인지 관리자 검토가 필요합니다."}""";
                    } else {
                        analysisCalls.incrementAndGet();
                        content = """
                                {"summary":"가상시 상가에서 화재가 발생하여 주민이 대피했습니다.",
                                "keyFacts":["상가 화재 발생","소방대 출동"],"entities":[{"name":"가상시","type":"PLACE"}],
                                "category":"SOCIETY","eventType":"화재","multiEvent":false,"confidence":0.9}""";
                    }
                    result.put("model", "contract-chat");
                    result.putArray("choices").addObject().put("finish_reason", "stop")
                            .putObject("message").put("content", content);
                    result.putObject("usage").put("prompt_tokens", 30).put("completion_tokens", 40);
                }
                response = result.toString();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        api.start();
        registry.add("app.ai.provider", () -> "openai");
        registry.add("app.ai.openai.api-key", () -> "contract-test-key");
        registry.add("app.ai.openai.base-url", () -> "http://127.0.0.1:" + api.getAddress().getPort() + "/v1");
    }

    @AfterAll static void stopApi() { if (api != null) api.stop(0); }

    @Test void rssThroughOpenAiHttpAndPgvectorToAdminConfirmationAndAudit() throws Exception {
        jdbc.sql("INSERT INTO app_user (username, password_hash) VALUES ('openai-flow', :hash) ON CONFLICT (lower(username)) DO UPDATE SET password_hash=EXCLUDED.password_hash")
                .param("hash", encoder.encode("test-password")).update();
        jdbc.sql("INSERT INTO user_role (user_id, role) SELECT id, 'SYSTEM_ADMIN' FROM app_user WHERE username='openai-flow' ON CONFLICT DO NOTHING").update();
        sources.upsertDefinition("contract-rss", NewsSourceType.RSS,
                "http://127.0.0.1:" + api.getAddress().getPort() + "/rss", true, 600, Map.of());
        long sourceId = count("SELECT id FROM news_source WHERE name='contract-rss'");
        mvc.perform(post("/api/admin/sources/" + sourceId + "/collect").with(httpBasic("openai-flow", "test-password")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.success").value(true));
        assertThat(drain()).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM async_job WHERE status='SUCCESS'")).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM news_article WHERE vector_dims(embedding)=1536")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ai_log WHERE provider='openai' AND success")).isEqualTo(5);
        assertThat(analysisCalls.get()).isEqualTo(2);
        assertThat(embeddingCalls.get()).isEqualTo(2);
        assertThat(classifierCalls.get()).isEqualTo(1);
        long issueId = count("SELECT id FROM issue WHERE status='REVIEW'");
        mvc.perform(patch("/api/admin/issues/" + issueId).with(httpBasic("openai-flow", "test-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"reason\":\"검수 완료\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(count("SELECT count(*) FROM news_article WHERE classification_status='CLASSIFIED'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action='ISSUE_UPDATE' AND actor='openai-flow'")).isEqualTo(1);
    }
}
