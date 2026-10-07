package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NoneProviderTest {

    @Test
    void noneProvidersFailLoudlyWithANonRetryableCode() {
        var ai = assertThrows(AiException.class, () -> new NoneAiClient().complete(null));
        var emb = assertThrows(AiException.class, () -> new NoneEmbeddingClient().embed("x"));
        assertEquals(AiException.NOT_CONFIGURED, ai.code());
        assertEquals(AiException.NOT_CONFIGURED, emb.code());
        assertFalse(ai.retryable());
        assertFalse(emb.retryable());
    }

    /** Regression: one object implementing both interfaces made Spring report two candidates per injection point. */
    @Test
    void noneAiClientAndEmbeddingClientAreDifferentTypes() {
        Object ai = new NoneAiClient();
        Object emb = new NoneEmbeddingClient();
        assertFalse(ai instanceof EmbeddingClient);
        assertFalse(emb instanceof AiClient);
    }
}
