package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicontent.platform.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** What one embedding call leaves in ai_log, including failures (model name must not be lost). */
class EmbeddingServiceLogTest {

    /** Defaults from the @DefaultValue annotations (embedding dimension 1536, no limits, no prices). */
    private static final AppProperties PROPS =
            new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("app", AppProperties.class);

    private static final class CapturingLogs extends AiLogRepository {
        final List<Entry> entries = new ArrayList<>();

        CapturingLogs() {
            super(null);
        }

        @Override
        public void insert(Entry e) {
            entries.add(e);
        }
    }

    private static EmbeddingClient client(Supplier<EmbeddingResult> answer) {
        return new EmbeddingClient() {
            @Override public String provider() { return "test"; }
            @Override public String model() { return "requested-model"; }
            @Override public EmbeddingResult embed(String text) { return answer.get(); }
        };
    }

    private static EmbeddingService service(EmbeddingClient client, CapturingLogs logs) {
        var limiter = new AiUsageLimiter(logs, PROPS.ai().limits(), false);
        return new EmbeddingService(client, logs, PROPS, new AiCostCalculator(Map.of()), limiter);
    }

    @Test
    void providerFailureRecordsTheRequestedModel() {
        var logs = new CapturingLogs();
        var svc = service(client(() -> { throw new AiException("OPENAI_HTTP_503", "unavailable", true); }), logs);

        var e = assertThrows(AiException.class, () -> svc.embed("text", AiCallContext.of(1L, 2L)));
        assertEquals("OPENAI_HTTP_503", e.code());
        assertEquals(1, logs.entries.size());
        var entry = logs.entries.get(0);
        assertFalse(entry.success());
        assertEquals("requested-model", entry.model());
        assertEquals("test", entry.provider());
        assertEquals(2L, entry.articleId());
        assertNull(entry.inputTokens());
    }

    @Test
    void wrongDimensionRecordsTheReturnedModelAndTokens() {
        var logs = new CapturingLogs();
        var svc = service(client(() -> new EmbeddingResult(new float[3], "returned-model", 7)), logs);

        var e = assertThrows(AiException.class, () -> svc.embed("text", null));
        assertEquals("EMBEDDING_DIMENSION_MISMATCH", e.code());
        assertFalse(e.retryable());
        var entry = logs.entries.get(0);
        assertFalse(entry.success());
        assertEquals("returned-model", entry.model());
        assertEquals(7, entry.inputTokens());
        assertEquals("EMBEDDING_DIMENSION_MISMATCH", entry.errorCode());
    }

    @Test
    void successRecordsTheReturnedModel() {
        var logs = new CapturingLogs();
        var svc = service(client(() -> new EmbeddingResult(new float[PROPS.ai().embeddingDimension()], "returned-model", 5)), logs);

        svc.embed("text", null);
        var entry = logs.entries.get(0);
        assertTrue(entry.success());
        assertEquals("returned-model", entry.model());
        assertEquals(5, entry.inputTokens());
        assertEquals(AiCallType.EMBEDDING, entry.type());
    }
}
